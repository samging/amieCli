package org.example.project.components.terminal.window.controller.envBuilder

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/** One artifact Maven Central's search found: its coordinates without a version, and its newest version */
data class MavenHit(val group: String, val artifact: String, val latestVersion: String, val packaging: String, val updated: Instant?, val hasJar: Boolean = true) {
    val coordinate: String get() = "$group:$artifact:$latestVersion"
    val page: String get() = "https://central.sonatype.com/artifact/$group/$artifact/$latestVersion"
}

/**
 * Maven Central's search (the solrsearch API, served by central.sonatype.com; the old search.maven.org host
 * no longer answers everywhere), for the Packages tab's "From Maven Central…" form.
 * A query with a colon is read as `group:artifact` (the artifact optional) and matched exactly; anything else
 * is searched as text in group, artifact and class names, the way the website's box does.
 */
object MavenCentral {
    const val SEARCH = "https://central.sonatype.com/solrsearch/select"

    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Hits for what the user typed. `group:artifact` lists that artifact's versions, newest first. Anything else
     * is searched twice (as free text, and as an artifact name) and ranked here, because the server's own order
     * puts obscure matches first: an artifact named exactly like the query wins, then one that starts with it,
     * then a group that mentions it; ties go to the most recently published.
     */
    fun search(query: String, rows: Int = 25): List<MavenHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        if (':' in q) {
            val (group, artifact) = q.split(':', limit = 3).let { it[0].trim() to it.getOrNull(1)?.trim().orEmpty() }
            val solr = buildString {
                append("g:\"").append(group).append('"')
                if (artifact.isNotEmpty()) append(" AND a:\"").append(artifact).append('"')
            }
            // core=gav: one row per version
            val versions = if (artifact.isEmpty()) ask(solr, 100, core = null) else ask(solr, 200, core = "gav")
            return versions
                .distinctBy { it.coordinate }
                .sortedWith(compareBy<MavenHit> { it.group }.thenBy { it.artifact }.thenByDescending { it.latestVersion }.let { byName ->
                    Comparator { a, b -> if (a.group == b.group && a.artifact == b.artifact) compareVersions(b.latestVersion, a.latestVersion) else byName.compare(a, b) }
                })
                .take(rows)
        }
        // Three asks at once: free text, the exact artifact name, and artifacts starting with it (how the
        // kotlinx-serialization-json or jackson-databind of a name are found)
        val wildcard = q.replace(Regex("[^A-Za-z0-9._-]"), "")
        val asks = listOfNotNull(
            http.sendAsync(request(q, 100, null), HttpResponse.BodyHandlers.ofString()),
            http.sendAsync(request("a:\"$q\"", 50, null), HttpResponse.BodyHandlers.ofString()),
            wildcard.takeIf { it.length >= 3 }?.let { http.sendAsync(request("a:$it*", 200, null), HttpResponse.BodyHandlers.ofString()) },
        )
        val hits = asks.flatMap { parse(it.join()) }.distinctBy { "${it.group}:${it.artifact}" }
        val needle = q.lowercase()
        fun rank(hit: MavenHit): Int {
            val a = hit.artifact.lowercase()
            val g = hit.group.lowercase()
            var score = when {
                a == needle -> 30
                a.startsWith(needle) || a.endsWith(needle) -> 20
                a.contains(needle) -> 10
                else -> 0
            }
            if (g.contains(needle)) score += 4
            // The server has no popularity signal (versionCount is always 0), so the groups most people mean come first
            if (wellKnownGroups.any { g == it || g.startsWith("$it.") }) score += 25
            // A POM-only artifact (a BOM, a parent) has nothing to put on a classpath; a Kotlin multiplatform target
            // (…-jvm, …-iosarm64) is picked by Gradle, never typed; the plain artifact is what is wanted
            if (!hit.hasJar || a.endsWith("-bom") || a.endsWith("-parent")) score -= 15
            if (platformSuffix.containsMatchIn(a)) score -= 12
            return score
        }
        return hits.sortedWith(compareByDescending<MavenHit> { rank(it) }.thenByDescending { it.updated?.toEpochMilli() ?: 0L }).take(rows)
    }

    private val platformSuffix = Regex("-(jvm|js|wasmjs|wasmwasi|android|androidnative\\w*|ios\\w*|macos\\w*|watchos\\w*|tvos\\w*|linux\\w*|mingw\\w*|native)$")

    /** Groups a plain name usually means: a hit from one of them outranks an obscure artifact of the same name */
    private val wellKnownGroups = setOf(
        "org.jetbrains.kotlin", "org.jetbrains.kotlinx", "org.jetbrains", "com.google.code.gson", "com.google.guava", "com.google.protobuf",
        "com.google.inject", "com.fasterxml.jackson.core", "com.fasterxml.jackson.module", "com.fasterxml.jackson.datatype", "tools.jackson.core",
        "com.squareup.okhttp3", "com.squareup.retrofit2", "com.squareup.moshi", "com.squareup.okio", "io.ktor", "org.apache.commons",
        "commons-io", "commons-codec", "commons-cli", "org.apache.httpcomponents", "org.apache.httpcomponents.client5", "org.apache.logging.log4j",
        "org.slf4j", "ch.qos.logback", "junit", "org.junit.jupiter", "org.mockito", "org.assertj", "io.netty", "org.json", "org.yaml",
        "org.ow2.asm", "io.reactivex.rxjava3", "org.jsoup", "com.github.ben-manes.caffeine", "org.xerial", "org.postgresql", "com.mysql",
        "com.zaxxer", "org.mongodb", "redis.clients", "org.springframework", "org.springframework.boot", "io.micrometer", "org.apache.kafka",
        "com.microsoft.onnxruntime", "ai.djl", "org.tensorflow", "org.bytedeco", "org.deeplearning4j", "org.nd4j", "com.opencsv", "org.apache.poi",
        "com.h2database", "org.xerial.snappy", "com.google.code.findbugs", "javax.inject", "jakarta.inject", "org.apache.pdfbox", "org.apache.lucene",
    )

    private fun request(solr: String, rows: Int, core: String?): HttpRequest {
        val url = "$SEARCH?q=${URLEncoder.encode(solr, Charsets.UTF_8)}&rows=$rows&wt=json" + (core?.let { "&core=$it" } ?: "")
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Accept", "application/json").header("User-Agent", "amie-pilot").GET().build()
    }

    private fun ask(solr: String, rows: Int, core: String?): List<MavenHit> = parse(http.send(request(solr, rows, core), HttpResponse.BodyHandlers.ofString()))

    private fun parse(response: HttpResponse<String>): List<MavenHit> {
        if (response.statusCode() != 200) throw IOException("Maven Central search returned ${response.statusCode()}")
        val docs = json.parseToJsonElement(response.body()).jsonObject["response"]?.jsonObject?.get("docs")?.jsonArray ?: return emptyList()
        return docs.mapNotNull { doc ->
            val o = doc.jsonObject
            val group = o["g"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val artifact = o["a"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val version = o["latestVersion"]?.jsonPrimitive?.contentOrNull ?: o["v"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            MavenHit(
                group, artifact, version,
                o["p"]?.jsonPrimitive?.contentOrNull ?: "jar",
                o["timestamp"]?.jsonPrimitive?.longOrNull?.let { Instant.ofEpochMilli(it) },
                hasJar = o["ec"]?.jsonArray?.any { it.jsonPrimitive.contentOrNull == ".jar" } ?: true,
            )
        }
    }

    /** "group:artifact:version" with three non-empty parts, or null */
    fun parse(notation: String): Triple<String, String, String>? {
        val parts = notation.trim().split(':')
        if (parts.size != 3 || parts.any { it.isBlank() }) return null
        return Triple(parts[0].trim(), parts[1].trim(), parts[2].trim())
    }

    fun page(coordinate: String): String? = parse(coordinate)?.let { (g, a, v) -> "https://central.sonatype.com/artifact/$g/$a/$v" }
}
