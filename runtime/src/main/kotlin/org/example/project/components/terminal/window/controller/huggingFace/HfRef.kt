package org.example.project.components.terminal.window.controller.huggingFace

import java.net.URI
import java.net.URLDecoder

/**
 * A model on the Hugging Face Hub as it was typed: its repo id (`owner/name`, or a bare `name` the Hub still knows,
 * such as `gpt2`), and the revision and path a huggingface.co address named. [path] is a folder for a `/tree/` address
 * and a file for a `/blob/` or `/resolve/` one ([file] says which); both are null for a plain repo id.
 */
data class HfRef(val repo: String, val revision: String? = null, val path: String? = null, val file: Boolean = false) {
    /** The name an imported model gets by default: the repo's last part, with what a folder name can't hold made a dash */
    val defaultName: String get() = localName(repo.substringAfterLast('/'))

    companion object {
        private val part = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
        private val hosts = setOf("huggingface.co", "www.huggingface.co", "hf.co")

        /**
         * What [input] names: `owner/name`, `https://huggingface.co/owner/name`, `…/tree/<rev>/<folder>`,
         * `…/blob/<rev>/<file>` or `…/resolve/<rev>/<file>` (with or without `https://`, a query or a trailing slash).
         * Anything else, a dataset or a Space included, is an [HfException] of [HfProblem.BAD_INPUT] saying what to write.
         */
        fun parse(input: String): HfRef {
            val text = input.trim().trimEnd('/')
            if (text.isEmpty()) throw bad(input, "write owner/name, or paste its huggingface.co address")
            val host = text.substringAfter("://").substringBefore('/').lowercase()
            return if (text.contains("://") || host in hosts) fromUrl(input, text) else fromId(input, text)
        }

        private fun fromId(input: String, text: String): HfRef {
            val parts = text.split('/')
            if (parts.size > 2 || parts.any { !part.matches(it) } || parts.any { it.contains("..") }) throw bad(input, "write owner/name, or paste its huggingface.co address")
            return HfRef(text)
        }

        private fun fromUrl(input: String, text: String): HfRef {
            val uri = runCatching { URI(if (text.contains("://")) text else "https://$text") }.getOrNull() ?: throw bad(input, "that address can't be read")
            if (uri.host?.lowercase() !in hosts) throw bad(input, "it is not a huggingface.co address")
            val segments = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it, Charsets.UTF_8) }
            when (segments.firstOrNull()) {
                null -> throw bad(input, "the address names no model; it should go on to owner/name")
                "datasets" -> throw bad(input, "it is a dataset, not a model")
                "spaces" -> throw bad(input, "it is a Space, an app, not a model")
            }
            val rest = if (segments.first() == "models") segments.drop(1) else segments
            val kinds = setOf("tree", "blob", "resolve")
            val repoParts = rest.takeWhile { it !in kinds }.take(2)
            val repo = fromId(input, repoParts.joinToString("/")).repo
            val after = rest.drop(repoParts.size)
            if (after.isEmpty()) return HfRef(repo)
            val kind = after.first()
            if (kind !in kinds) throw bad(input, "the address goes on past the model in a way that can't be read")
            val revision = after.getOrNull(1) ?: throw bad(input, "the address names no revision after /$kind/")
            val path = after.drop(2).joinToString("/").ifEmpty { null }
            if (path != null && path.split('/').any { it == ".." }) throw bad(input, "the path in the address goes up a folder")
            return HfRef(repo, revision, path, file = kind != "tree" && path != null)
        }

        private fun bad(input: String, why: String) = HfException(HfProblem.BAD_INPUT, "\"${input.trim()}\" is not a Hugging Face model: $why")

        /** [raw] as a folder name under `AMP/models/imported`: letters, digits, `.`, `_` and `-`, anything else a dash; never empty */
        fun localName(raw: String): String = raw.trim()
            .map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-") it else '-' }.joinToString("")
            .replace(Regex("-{2,}"), "-")
            .trimStart('.', '-')
            .trimEnd('-')
            .take(96)
            .ifEmpty { "model" }
    }
}
