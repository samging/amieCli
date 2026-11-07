package org.example.project.components.terminal.window.controller.huggingFace

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.example.project.amp.ModelFolders
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.util.bytes
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/** Where a download stands: the file coming in, its place in the list, and the bytes in so far of it and of all */
data class HfProgress(val path: String, val index: Int, val count: Int, val fileDone: Long, val fileSize: Long?, val done: Long, val total: Long) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * The Hugging Face Hub, asked over [http]: what a repo holds ([look]) and its files brought into `AMP/models/imported`
 * ([download]). [token] (from `HF_TOKEN`, see [HfImports.token]) goes only to [base]'s own host, never to the storage a
 * download is sent on to, and is never written to a message, a log or a file.
 *
 * A download goes to `AMP/models/imported/.incoming/<name>/`, each file first as `<path>.part`: its size is checked
 * against the Hub's before it takes its own name, and only once every file is there and `source.json` is written does the
 * folder move to `imported/<name>` in one rename. So a model folder is whole or not there, and a closed app, a lost
 * connection or a full disk leaves at most `.incoming`, which the next download of the same commit goes on from: a
 * file already in is kept, and a `.part` is resumed with a `Range` request.
 */
class HfHub(private val http: HttpClient, val base: String = BASE, private val token: String? = null) {
    private val host = URI(base).host

    /** What [ref] names: the repo's own id, the commit of its revision (the default branch when none was named), its files and sizes */
    suspend fun look(ref: HfRef): HfRepo {
        val repo = ref.repo
        val infoUrl = "$base/api/models/$repo" + (ref.revision?.let { "/revision/${segment(it)}" } ?: "")
        val info = HfJson.info(text(infoUrl, repo, ref.revision, null))
        val tree = mutableListOf<HfFile>()
        var page: String? = "$base/api/models/${info.id}/tree/${segment(info.sha)}?recursive=true"
        var pages = 0
        while (page != null && pages++ < MAX_PAGES) {
            val (body, next) = get(page, offline = ::unreachable) { response ->
                if (!response.status.isSuccess()) throw failure(response.status, response.headers, info.id, info.sha, null)
                response.bodyAsText() to HfJson.nextLink(response.headers["Link"])
            }
            tree += HfJson.tree(body)
            page = next?.let { URI(page!!).resolve(it).toString() }
        }
        return HfRepo(info.id, info.sha, HfJson.files(info, tree), info.gated, info.private, ref, info.task)
    }

    /**
     * The `config.json` of [repo] read, the shallowest when there are several, so the page can show a model's kind
     * and labels before anything is downloaded; null when the repo has none, it is too big to be one, or it can't be
     * read now (a config is a convenience, never what stops a look-up)
     */
    suspend fun config(repo: HfRepo): HfConfig? {
        val file = repo.files.filter { it.name == "config.json" && (it.size ?: 0) <= CONFIG_MOST }.minByOrNull { it.path.count { c -> c == '/' } } ?: return null
        val url = "$base/${repo.id}/resolve/${segment(repo.sha)}/${file.path.split('/').joinToString("/") { segment(it) }}"
        return try {
            get(url, offline = ::unreachable) { response -> if (response.status.isSuccess()) HfJson.config(response.bodyAsText()) else null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HfException) {
            null
        }
    }

    /**
     * Models of the Hub matching [text] (blank: any) in [format], the most downloaded first: one request per tag the
     * format asks for (all four for "All supported"), at once, merged. Each asks for the repos' files too (`full=true`),
     * so a format is told by the files it has, not by a tag. With [cursors] (a page's [HfSearchPage.cursors]) it is the
     * next page of each tag that has one.
     */
    suspend fun search(text: String, format: HfSearchFormat, kind: HfSearchKind = HfSearchKind.ANY, cursors: Map<String, String>? = null, limit: Int = SEARCH_LIMIT): HfSearchPage {
        val tags = cursors?.keys?.toList() ?: format.tags
        val pages = coroutineScope {
            tags.map { tag -> async { tag to searchPage(cursors?.get(tag) ?: searchUrl(text, tag, kind, limit)) } }.awaitAll()
        }
        return HfSearchPage(HfSearchJson.merge(pages.map { it.second.first }, format), pages.mapNotNull { (tag, page) -> page.second?.let { tag to it } }.toMap())
    }

    /** `/api/models?search=…&filter=<tag>&pipeline_tag=<task>&sort=downloads&direction=-1&limit=…&full=true`, the task only when one is asked */
    fun searchUrl(text: String, tag: String, kind: HfSearchKind = HfSearchKind.ANY, limit: Int = SEARCH_LIMIT): String =
        "$base/api/models?" + listOfNotNull(
            text.trim().takeIf { it.isNotEmpty() }?.let { "search=${URLEncoder.encode(it, Charsets.UTF_8)}" },
            "filter=${URLEncoder.encode(tag, Charsets.UTF_8)}",
            kind.query?.let { "pipeline_tag=${URLEncoder.encode(it, Charsets.UTF_8)}" },
            "sort=downloads", "direction=-1", "limit=$limit", "full=true",
        ).joinToString("&")

    private suspend fun searchPage(url: String): Pair<List<HfSearchResult>, String?> = get(url, offline = ::unreachable) { response ->
        when {
            response.status.value == 429 -> throw HfException(HfProblem.SERVER, "Hugging Face is turning requests away for now, too many came; try again in a minute")
            !response.status.isSuccess() -> throw HfException(HfProblem.SERVER, "Hugging Face answered ${response.status.value} to the search; try again in a while")
        }
        HfSearchJson.results(response.bodyAsText()) to HfJson.nextLink(response.headers["Link"])?.let { URI(url).resolve(it).toString() }
    }

    /**
     * Brings [pick]'s files of [repo] into `AMP/models/imported/<name>` of [folders] (a text pick's into
     * `AMP/models/text/<name>`, by file name) with a `source.json` beside them, and
     * returns that folder; [onProgress] hears how far it got, a few times a second. Failures are [HfException]s; a
     * cancel leaves `.incoming` as it is, for [ImportedList.discard] or a later download to go on from. [free] says
     * how many bytes a folder's disk has free (tests hand in their own).
     */
    suspend fun download(
        repo: HfRepo,
        pick: HfPick,
        name: String,
        folders: ModelFolders,
        onProgress: (HfProgress) -> Unit = {},
        free: (File) -> Long = { it.usableSpace },
    ): File {
        val target = targetOf(folders, pick, name)
        if (target.exists()) throw HfException(HfProblem.TAKEN, "${ampPath(pick, name)} is there already: give this one another name")
        val at: (HfFile) -> String = { f -> if (pick.text) f.name else if (pick.speech) speechPath(f) else f.path }
        val staging = ImportedList.stagingOf(folders, name)
        val mark = File(staging, PENDING)
        val commit = "${repo.id}@${repo.sha}"
        disk(name) {
            // What another repo or commit left here is not this download's to go on from
            if (staging.isDirectory && (!mark.isFile || mark.readText().trim() != commit)) staging.deleteRecursively()
            staging.mkdirs()
            mark.writeText(commit)
        }
        val files = pick.files
        val needed = files.sumOf { f -> (f.size ?: 0) - have(staging, f, at(f)) }
        val room = free(generateSequence(staging) { it.parentFile }.first { it.exists() })
        if (needed > room) throw HfException(HfProblem.DISK, "Not enough room for $name: it needs ${bytes(needed)} more, and the disk AMP is on has ${bytes(room)} free")

        val total = files.sumOf { it.size ?: 0 }
        var before = 0L
        var last = 0L
        files.forEachIndexed { i, f ->
            fun report(fileDone: Long, force: Boolean = false) {
                val now = System.nanoTime()
                if (force || now - last > REPORT_NANOS) {
                    last = now
                    onProgress(HfProgress(f.path, i, files.size, fileDone, f.size, before + fileDone, total))
                }
            }
            fetch(repo, f, staging, name, at(f)) { report(it) }
            report(f.size ?: File(staging, at(f)).length(), force = true)
            before += f.size ?: File(staging, at(f)).length()
        }

        if (pick.speech) {
            val lacking = disk(name) { PiperVoice.prepare(staging, File(staging, at(pick.candidate.main)), folders.speech) }
            lacking.forEach { logInternal("huggingFace: $name: $it", LogLevel.ERROR) }
        }
        val source = ImportedSource(
            repo = repo.id,
            revision = repo.sha,
            format = pick.format.key,
            files = files.map { ImportedFile(at(it), File(staging, at(it)).length()) },
            downloadedAt = Instant.now().toString(),
            task = repo.task,
            labels = labelsOf(staging, pick),
        )
        disk(name) {
            ImportedList.write(staging, source)
            mark.delete()
            target.parentFile.mkdirs()
            try {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), target.toPath())
            }
        }
        return target
    }

    /** The labels of the `config.json` that came with [pick], the nearest to its model: what the imported model answers with */
    private fun labelsOf(staging: File, pick: HfPick): List<String> {
        val config = pick.companions.filter { it.name == "config.json" }.maxByOrNull { it.path.count { c -> c == '/' } } ?: return emptyList()
        return runCatching { HfJson.config(File(staging, config.path).readText()).labels }.getOrDefault(emptyList())
    }

    /** The bytes of [f] already in [staging]: the whole file once it has its name, else its `.part` */
    private fun have(staging: File, f: HfFile, at: String = f.path): Long {
        val done = File(staging, at)
        if (done.isFile) return done.length()
        return File(staging, at + PART).takeIf { it.isFile }?.length()?.coerceAtMost(f.size ?: Long.MAX_VALUE) ?: 0
    }

    /** One file into [staging]: as `<path>.part`, resumed when one is there, checked, then given its name */
    private suspend fun fetch(repo: HfRepo, f: HfFile, staging: File, name: String, at: String, onBytes: (Long) -> Unit) {
        val dest = File(staging, at)
        if (!dest.canonicalPath.startsWith(staging.canonicalPath + File.separator)) throw HfException(HfProblem.SERVER, "Hugging Face listed a file outside the repo, ${f.path}; nothing was written")
        if (dest.isFile && (f.size == null || dest.length() == f.size)) return
        val part = File(dest.path + PART)
        disk(name) { dest.parentFile.mkdirs() }
        if (f.size != null && part.isFile && part.length() > f.size) disk(name) { part.delete() }
        val url = "$base/${repo.id}/resolve/${segment(repo.sha)}/${f.path.split('/').joinToString("/") { segment(it) }}"
        var restarted = false
        while (true) {
            val from = if (part.isFile) part.length() else 0L
            val complete = get(url, from, offline = { brokeOff(f.path, it) }) { response ->
                when {
                    response.status.value == 416 -> false
                    response.status == HttpStatusCode.PartialContent -> { stream(response, part, append = true, name, onBytes); true }
                    response.status.isSuccess() -> { stream(response, part, append = false, name, onBytes); true }
                    else -> throw failure(response.status, response.headers, repo.id, repo.sha, f.path)
                }
            }
            if (complete) break
            // 416: the part asked past its end. Already whole, it is kept; otherwise it starts over, once
            if (f.size != null && part.isFile && part.length() == f.size) break
            if (restarted) throw HfException(HfProblem.SERVER, "Hugging Face would not send ${f.path} from the start; try again in a while")
            restarted = true
            disk(name) { part.delete() }
        }
        val got = part.length()
        if (f.size != null && got != f.size) throw HfException(
            HfProblem.INCOMPLETE,
            "${f.path} came in at ${bytes(got)} where Hugging Face lists ${bytes(f.size)}; nothing took its name, and the next download goes on from what came",
        )
        disk(name) { Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    private suspend fun stream(response: HttpResponse, part: File, append: Boolean, name: String, onBytes: (Long) -> Unit) {
        val channel = response.bodyAsChannel()
        var written = if (append && part.isFile) part.length() else 0L
        val out = disk(name) { FileOutputStream(part, append) }
        out.use {
            val buffer = ByteArray(BUFFER)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = channel.readAvailable(buffer, 0, buffer.size)
                if (n < 0) break
                if (n == 0) { if (channel.isClosedForRead) break else continue }
                disk(name) { out.write(buffer, 0, n) }
                written += n
                onBytes(written)
            }
        }
    }

    /** The body of an API answer, or the failure its status means */
    private suspend fun text(url: String, repo: String, revision: String?, path: String?): String = get(url, offline = ::unreachable) { response ->
        if (!response.status.isSuccess()) throw failure(response.status, response.headers, repo, revision, path)
        response.bodyAsText()
    }

    /**
     * A GET of [start] that follows redirects itself, so the token goes only to the Hub's own host (a relative
     * redirect stays there; the storage a file is sent on to gets no token). [from] > 0 asks for the bytes from there.
     * A connection that can't be made or breaks off is [offline]'s message.
     */
    private suspend fun <T> get(start: String, from: Long = 0, offline: (IOException) -> HfException, block: suspend (HttpResponse) -> T): T {
        var url = start
        for (hop in 0 until MAX_REDIRECTS) {
            val asked = url
            val step: Hop<T> = try {
                http.prepareGet(asked) {
                    if (token != null && URI(asked).host == host) header(HttpHeaders.Authorization, "Bearer $token")
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    if (from > 0) header(HttpHeaders.Range, "bytes=$from-")
                    if ("/api/" in asked) timeout { requestTimeoutMillis = API_TIMEOUT_MS }
                }.execute<Hop<T>> { response ->
                    if (response.status.value in 300..399 && response.status != HttpStatusCode.NotModified) {
                        val location = response.headers[HttpHeaders.Location] ?: throw HfException(HfProblem.SERVER, "Hugging Face sent a redirect with nowhere to go")
                        Hop.Next(URI(asked).resolve(location).toString())
                    } else Hop.Done(block(response))
                }
            } catch (e: HfException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw offline(e)
            }
            when (step) {
                is Hop.Next -> url = step.url
                is Hop.Done -> return step.value
            }
        }
        throw HfException(HfProblem.SERVER, "Hugging Face sent the request round more than $MAX_REDIRECTS redirects")
    }

    /** Where one request of [get] got: sent on to another address, or answered */
    private sealed interface Hop<out T> {
        class Next(val url: String) : Hop<Nothing>
        class Done<T>(val value: T) : Hop<T>
    }

    /** What a status the Hub answered with means, in the app's words */
    private fun failure(status: HttpStatusCode, headers: Headers, repo: String, revision: String?, path: String?): HfException {
        val code = headers["X-Error-Code"]
        val page = "huggingface.co/$repo"
        return when (status.value) {
            401, 403 -> HfException(HfProblem.GATED, when {
                code == "GatedRepo" && token != null -> "$repo is gated, and HF_TOKEN's account has no access yet: accept its terms on $page, then try again"
                code == "GatedRepo" -> "$repo is gated: accept its terms on $page, then start the app with HF_TOKEN set to a token of that account"
                token != null -> "HF_TOKEN can't read $repo: it is private to another account, gated, or not there"
                else -> "$repo is private or gated, or not there: for one you can read, start the app with HF_TOKEN set to a token of your account"
            })
            404 -> HfException(HfProblem.NOT_FOUND, when {
                code == "RevisionNotFound" && revision != null -> "$repo has no revision $revision on Hugging Face"
                code == "EntryNotFound" && path != null -> "$repo has no file $path at ${revision?.take(7) ?: "its last commit"}"
                path != null -> "Hugging Face has no $path in $repo"
                else -> "Hugging Face has no model $repo; check the spelling, owner/name"
            })
            429 -> HfException(HfProblem.SERVER, "Hugging Face is turning requests away for now, too many came; try again in a minute")
            in 500..599 -> HfException(HfProblem.SERVER, "Hugging Face answered ${status.value} for $repo; try again in a while")
            else -> HfException(HfProblem.SERVER, "Hugging Face answered ${status.value} for ${path ?: repo}")
        }
    }

    private fun unreachable(e: IOException) = HfException(HfProblem.OFFLINE, "Hugging Face can't be reached: check the connection and try again (${why(e)})", e)

    private fun brokeOff(path: String, e: IOException) = HfException(HfProblem.OFFLINE, "The download of $path broke off (${why(e)}); the next download goes on from where it stopped", e)

    private fun why(e: IOException) = e.javaClass.simpleName + (e.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")

    /** A file operation of the download, its failure said as one: a full disk, a folder that can't be written */
    private inline fun <T> disk(name: String, block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw HfException(HfProblem.DISK, "$name could not be written in AMP/models/imported: ${e.message ?: e.javaClass.simpleName}", e)
    } catch (e: SecurityException) {
        throw HfException(HfProblem.DISK, "$name could not be written in AMP/models/imported: ${e.message ?: e.javaClass.simpleName}", e)
    }

    companion object {
        /** Where [pick] lands as [name]: a text model in `AMP/models/text`, a voice in `AMP/models/speech`, anything else in `AMP/models/imported` */
        fun targetOf(folders: ModelFolders, pick: HfPick, name: String): File = File(if (pick.text) folders.text else if (pick.speech) folders.speech else folders.imported, name)

        /** "AMP/models/text/qwen3" or "AMP/models/imported/bert": the folder [targetOf] names, as the log and notices say it */
        fun ampPath(pick: HfPick, name: String): String = "AMP/models/${if (pick.text) "text" else if (pick.speech) "speech" else "imported"}/$name"

        /** A voice's file in its folder: by name, but a file of `espeak-ng-data/` or `voices/` keeps its place in that folder */
        fun speechPath(f: HfFile): String {
            val parts = f.path.split('/')
            val at = parts.indexOfFirst { it in HfListing.COMPANION_DIRS }
            return if (at >= 0) parts.drop(at).joinToString("/") else f.name
        }

        const val BASE = "https://huggingface.co"
        /** The suffix of a file still coming in; it takes its own name only once its size checks */
        const val PART = ".part"
        /** In the staging folder while a download is under way: the repo and commit it is of */
        const val PENDING = ".pending"
        private const val USER_AGENT = "amie-pilot"
        private const val BUFFER = 64 * 1024
        private const val MAX_REDIRECTS = 10
        private const val MAX_PAGES = 100
        private const val API_TIMEOUT_MS = 30_000L
        private const val REPORT_NANOS = 100_000_000L
        const val SEARCH_LIMIT = 20
        /** A `config.json` bigger than this is not read for its labels */
        private const val CONFIG_MOST = 1L shl 20

        /** A client for the Hub on [engine]: no redirects of its own (see [get]), no limit on how long a big file takes */
        fun client(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 60_000
            }
        }

        /** A path segment as a URL holds it: `refs/pr/1` as `refs%2Fpr%2F1`, a space as `%20` */
        fun segment(text: String): String = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")
    }
}
