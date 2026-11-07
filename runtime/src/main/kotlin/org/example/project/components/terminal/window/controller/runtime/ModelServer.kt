package org.example.project.components.terminal.window.controller.runtime

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.example.project.components.terminal.integrations.amiePilot.InitMod
import org.example.project.components.terminal.integrations.amiePilot.InteractionModel
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.Embedder
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.LabelQueue
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.ModelBuildProgress
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.fineTuneClassifier
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.regradeQueue
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.teach
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.util.Secrets
import org.example.project.components.terminal.window.controller.sourceFile.DataSource
import org.example.project.components.terminal.window.controller.modules.ModelVersions
import org.example.project.components.terminal.window.controller.modules.moduleDir
import org.example.project.components.terminal.window.controller.modules.moduleExists
import org.example.project.components.terminal.window.controller.modules.modulesDir
import org.example.project.components.terminal.window.controller.modules.readModule
import org.example.project.components.terminal.window.controller.modules.readRegistry
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** How a module is served; kept in AMP/models/intents/<name>/serving.json */
@Serializable
data class ServingSettings(
    val enabled: Boolean = false,
    // Loopback by default so only this machine can reach the model
    val host: String = "127.0.0.1",
    val port: Int = 8765,
    val path: String = "",
    val methods: List<String> = listOf("POST", "GET"),
    // Under this best score the model abstains, answering [fallbackLabel] or no label; unset or 0, it always answers
    val minScore: Double? = null,
    val fallbackLabel: String? = null,
    // Who may ask and how much, each unset by default, which serves as before: the key a request sends as
    // "Authorization: Bearer <key>" (401 without it); the origins a browser page may call it from, scheme://host[:port]
    // or * (an origin on the list gets the CORS headers and its preflight, another 403; a request with no Origin isn't
    // a browser's and goes on); the most bytes a body or a GET text may have (413 past it); and how many requests a
    // minute it answers, every client together (429 past it, with Retry-After)
    val apiKey: String? = null,
    val cors: List<String>? = null,
    val maxBodyBytes: Int? = null,
    val rateLimitPerMinute: Int? = null,
) {
    /**
     * These settings with what a script's serve() gives; null keeps a setting as it is, a [minScore] of 0 and a blank
     * [fallbackLabel] take the saved one away. A min score outside 0 to 1 is refused
     */
    fun with(port: Int?, host: String?, path: String?, methods: List<String>?, minScore: Double?, fallbackLabel: String?): ServingSettings {
        require(minScore == null || minScore in 0.0..1.0) { "minScore is a score from 0 to 1 (0 serves without one), not $minScore" }
        return copy(
            host = host ?: this.host, port = port ?: this.port, path = path ?: this.path, methods = methods ?: this.methods,
            minScore = if (minScore == null) this.minScore else minScore.takeIf { it > 0 },
            fallbackLabel = if (fallbackLabel == null) this.fallbackLabel else fallbackLabel.trim().ifEmpty { null },
        )
    }

    /** Where the model is served, without the route: `/models/<name>` unless [path] says otherwise */
    fun basePath(model: String) = (path.ifBlank { "/models/$model" }).let { if (it.startsWith("/")) it else "/$it" }.trimEnd('/')

    /** The path of one [route] of the model: `/predict` (the default), `/teach` or `/retrain` under [basePath] */
    fun pathFor(model: String, route: String = "predict") = basePath(model) + "/" + route.trim('/')
    fun url(model: String, route: String = "predict") = "http://$host:$port${pathFor(model, route)}"

    /**
     * What is answered for the model's [predictions]: they as they are when there is no [minScore] (or 0) or the best
     * of them reaches it; under it the model abstains, answering [fallbackLabel] alone, scored at the gate, or nothing,
     * and its own scores go under the ranking
     */
    fun gate(predictions: List<Pair<String, Double>>): Gated {
        val min = minScore?.takeIf { it > 0 } ?: return Gated(predictions, abstained = null)
        val best = predictions.maxOfOrNull { it.second }
        if (best != null && best >= min) return Gated(predictions, abstained = false)
        return Gated(listOfNotNull(fallbackLabel?.takeIf { it.isNotBlank() }?.let { it to min }), abstained = true, ranking = predictions)
    }
}

/** A served answer: the [predictions] given, whether the model [abstained] (null when it has no gate), and its own [ranking] when it did */
data class Gated(val predictions: List<Pair<String, Double>>, val abstained: Boolean?, val ranking: List<Pair<String, Double>>? = null)

/**
 * At most [limit] requests in any minute: [take] counts one more when there is room, else says in how many
 * milliseconds the oldest of the minute's leaves it. [now] is the clock, the tests' own in tests
 */
internal class RequestWindow(private val limit: Int, private val now: () -> Long = System::currentTimeMillis) {
    private val taken = ArrayDeque<Long>()

    /** Null when the request may go, counted; else the milliseconds until one may */
    @Synchronized
    fun take(): Long? {
        val at = now()
        while (taken.isNotEmpty() && at - taken.first() >= MINUTE_MS) taken.removeFirst()
        if (taken.size >= limit) return MINUTE_MS - (at - taken.first())
        taken.addLast(at)
        return null
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}

// A gate left unset is left out of the file, which then reads and writes as it did before there were gates
@OptIn(ExperimentalSerializationApi::class)
private val servingJson = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

fun readServing(name: String): ServingSettings =
    runCatching { servingJson.decodeFromString<ServingSettings>(File(moduleDir(name), "serving.json").readText()) }.getOrElse { ServingSettings() }

fun writeServing(name: String, settings: ServingSettings) {
    val dir = moduleDir(name).apply { mkdirs() }
    File(dir, "serving.json").writeText(servingJson.encodeToString(settings))
}

/**
 * Serves modules over HTTP: POST {"text": "..."} (or a plain-text body) or GET ?text=... to <path>/predict returns
 * {"model", "predictions": [{"label", "score"}]}, with "abstained" (and "ranking" when it did) for a model with a
 * minScore. Requests run on background threads and inference goes through OrtSessions.
 * Every request is logged as internal and appended to AMP/models/intents/<name>/incoming.jsonl; for a model that doesn't keep
 * texts (it answers other people) only the length of the text is, never the text. A request the access settings refuse
 * (no key, an origin not on the list, too big, too many) is logged without its text and never recorded.
 * Every request, whatever came of it, is also one line of AMP/models/intents/<name>/access.log ([AccessLog]): when, its id
 * (sent back as X-Request-Id), who asked, the method and path without the query, the status, how long it took, the
 * bytes in and out and what came of it; never the text, for any model. Starting and stopping are lines there too.
 *
 * Beside <path>/predict a listening model takes lessons: <path>/teach adds a sentence with its label to the model's
 * queue of texts to label (POST), takes one out (DELETE) or lists the queue (GET), and <path>/retrain (POST) fine-tunes
 * the model on the labeled rows of that queue in the background while the model goes on answering with the head it
 * has; GET says how far it got ([Route]). When the fine-tune has written the new head, a model is opened on it, put
 * in the listener's place and the old one closed once its answers in flight are done ([reload]), so no request
 * meets a closed model or a refused connection.
 */
object ModelServer {
    /** The routes of a served model, each under its base path: what it takes and the methods it answers */
    internal enum class Route(val suffix: String, val methods: List<String>) {
        /** The text's labels and scores; the methods are the model's settings */
        PREDICT("predict", emptyList()),
        /** POST {"text", "label"} teaches, DELETE {"text"} (or ?text=) forgets, GET lists the queue */
        TEACH("teach", listOf("POST", "DELETE", "GET")),
        /** POST starts a fine-tune on the queue (?wait=true or {"wait": true} waits for it), GET says how it goes */
        RETRAIN("retrain", listOf("POST", "GET"));

        fun allowed(settings: ServingSettings): List<String> = if (this == PREDICT) settings.methods else methods
    }

    private class Listener(val name: String, val settings: ServingSettings, model: InteractionModel, val open: () -> InteractionModel, val keepTexts: Boolean = true) {
        // The model that answers, which a new head takes the place of while the listener goes on ([reload])
        val model = LiveModel(model)
        val startedAt: Instant = Instant.now()
        val requests = AtomicInteger()
        val errors = AtomicInteger()
        // Answers under minScore
        val abstained = AtomicInteger()
        // Requests the access settings turned away: 401, 403, 413 and 429
        val refused = AtomicInteger()
        // Sentences taught or forgotten over /teach, and fine-tunes started over /retrain
        val taught = AtomicInteger()
        val retrains = AtomicInteger()
        val window = settings.rateLimitPerMinute?.let(::RequestWindow)
        val lastRequestAt = AtomicLong(0)
        // Inference latency in milliseconds: total, worst and last, for the statistics page
        val latencyTotalMs = AtomicLong(0)
        val latencyMaxMs = AtomicLong(0)
        val latencyLastMs = AtomicLong(0)
        // The version of the head answering, when the module says; sent back with every answer so a client knows which head it got
        @Volatile var version: Int? = versionOf(name)
    }

    /** What a listening model has done since it started: shown on the models page */
    data class ServingStats(
        val name: String,
        val startedAt: Instant,
        val url: String,
        val methods: List<String>,
        val requests: Int,
        val errors: Int,
        val lastRequestAt: Instant?,
        val averageLatencyMs: Double,
        val maxLatencyMs: Long,
        val lastLatencyMs: Long,
        // How many requests were answered under minScore, and whether there is one
        val abstained: Int = 0,
        val gated: Boolean = false,
        // How many the access settings turned away
        val refused: Int = 0,
        // Sentences taught or forgotten over /teach, fine-tunes started over /retrain, and how many times a new head
        // took the place of the one answering, without a stop
        val taught: Int = 0,
        val retrains: Int = 0,
        val swaps: Int = 0,
        // The version of the head answering now, when the module says
        val version: Int? = null,
    ) {
        val uptime: java.time.Duration get() = java.time.Duration.between(startedAt, Instant.now())
    }

    fun stats(name: String): ServingStats? = listeners[name]?.let { l ->
        val n = l.requests.get()
        ServingStats(
            name = l.name,
            startedAt = l.startedAt,
            url = l.settings.url(l.name),
            methods = l.settings.methods,
            requests = n,
            errors = l.errors.get(),
            lastRequestAt = l.lastRequestAt.get().takeIf { it > 0 }?.let(Instant::ofEpochMilli),
            averageLatencyMs = if (n == 0) 0.0 else l.latencyTotalMs.get().toDouble() / n,
            maxLatencyMs = l.latencyMaxMs.get(),
            lastLatencyMs = l.latencyLastMs.get(),
            abstained = l.abstained.get(),
            gated = l.settings.minScore != null,
            refused = l.refused.get(),
            taught = l.taught.get(),
            retrains = l.retrains.get(),
            swaps = l.model.swaps,
            version = l.version,
        )
    }

    val listening: List<String> get() = listeners.keys.sorted()

    private val threads = AtomicInteger()
    private val executor = Executors.newFixedThreadPool(4) { Thread(it, "amie-serve-${threads.incrementAndGet()}").apply { isDaemon = true } }
    private val servers = ConcurrentHashMap<String, HttpServer>()
    private val listeners = ConcurrentHashMap<String, Listener>()

    fun isListening(name: String) = listeners.containsKey(name)

    /** Whether any model is being served, which keeps a bundle's run alive after its script ends */
    val anyListening: Boolean get() = listeners.isNotEmpty()
    fun requests(name: String) = listeners[name]?.requests?.get() ?: 0
    fun lastRequestAt(name: String): Instant? = listeners[name]?.lastRequestAt?.get()?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)

    /** Opens the module's session and starts listening; the setting is saved so it resumes on the next start */
    fun start(name: String, settings: ServingSettings = readServing(name)): Boolean = start(name, settings) { InitMod(name).create() }

    // [open] makes the model that answers, now and again after every new head; the tests hand in one of their own
    @Synchronized
    internal fun start(name: String, settings: ServingSettings, open: () -> InteractionModel): Boolean {
        stop(name, persist = false)
        val model = try {
            open()
        } catch (e: Exception) {
            logInternal("serve '$name': can't load the model: ${e.message}", LogLevel.ERROR)
            return false
        }
        val key = "${settings.host}:${settings.port}"
        val server = try {
            servers.getOrPut(key) {
                HttpServer.create(InetSocketAddress(settings.host, settings.port), 0).apply {
                    executor = this@ModelServer.executor
                    start()
                }
            }
        } catch (e: Exception) {
            model.close()
            logInternal("serve '$name': can't listen on $key: ${e.message}", LogLevel.ERROR)
            return false
        }
        val listener = Listener(name, settings, model, open, keepTexts = readModule(name)?.second?.keepTexts ?: true)
        val made = ArrayList<String>()
        for (route in Route.entries) {
            val path = settings.pathFor(name, route.suffix)
            try {
                server.createContext(path) { exchange -> handle(listener, route, exchange) }
                made += path
            } catch (e: IllegalArgumentException) {
                made.forEach { runCatching { server.removeContext(it) } }
                if (listeners.values.none { "${it.settings.host}:${it.settings.port}" == key }) {
                    server.stop(0)
                    servers.remove(key)
                }
                model.close()
                logInternal("serve '$name': $path is already served on $key", LogLevel.ERROR)
                return false
            }
        }
        listeners[name] = listener
        writeServing(name, settings.copy(enabled = true))
        logInternal("serve '$name': listening on ${settings.methods.joinToString("/")} ${settings.url(name)}")
        AccessLog.note(name, "listening on ${settings.methods.joinToString("/")} ${settings.url(name)}" + listOfNotNull(
            settings.apiKey?.let { "key required" }, settings.rateLimitPerMinute?.let { "$it a minute" },
            settings.maxBodyBytes?.let { "up to $it bytes" }, settings.cors?.let { "origins ${it.joinToString()}" },
        ).joinToString("") { " · $it" })
        return true
    }

    @Synchronized
    fun stop(name: String, persist: Boolean = true) {
        val listener = listeners.remove(name) ?: run {
            if (persist) writeServing(name, readServing(name).copy(enabled = false))
            return
        }
        val key = "${listener.settings.host}:${listener.settings.port}"
        servers[key]?.let { server ->
            Route.entries.forEach { route -> runCatching { server.removeContext(listener.settings.pathFor(name, route.suffix)) } }
            // The last model on a port takes the server down with it
            if (listeners.values.none { "${it.settings.host}:${it.settings.port}" == key }) {
                server.stop(0)
                servers.remove(key)
            }
        }
        listener.model.close()
        // What serving.json holds now, not what the listener started with: settings saved while it listened stay
        if (persist) writeServing(name, readServing(name).copy(enabled = false))
        logInternal("serve '$name': stopped")
        AccessLog.note(name, "stopped after ${listener.requests.get()} requests (${listener.errors.get()} failed, ${listener.refused.get()} refused)")
    }

    /**
     * The listener of [name], if there is one, answers with the model as it is on disk now, without a stop: a model
     * is opened on the head it has (as the listener's own was), put in the place of the one answering once its
     * answers in flight are done, and that one is closed. Requests meanwhile are answered by one head or the other,
     * never refused. The listener keeps its port, its settings and its counts. A new head that can't be opened
     * leaves the old one answering, with the reason in the console and the access log. A model that stopped
     * keeping texts gets a fresh listener instead, so its log follows the policy. True when the listener answers
     * with the new head.
     */
    fun reload(name: String, note: String): Boolean {
        val listener = listeners[name] ?: return false
        val manifest = readModule(name)?.second
        if (listener.keepTexts && manifest?.keepTexts == false) {
            logInternal("serve '$name': the model keeps no texts any more, so the listener is started afresh on the new head")
            return start(name)
        }
        val next = try {
            listener.open()
        } catch (e: Exception) {
            logInternal("serve '$name': the new head could not be opened, the listener keeps answering with the old one: ${e.message}", LogLevel.ERROR)
            AccessLog.note(name, "new head not opened (${e.message}); still answering with the old one")
            return false
        }
        val old = synchronized(this) {
            // Stopped while the new head was opened: nothing to swap it into
            if (listeners[name] !== listener) {
                runCatching { next.close() }
                return false
            }
            listener.version = manifest?.let(ModelVersions::numberOf)
            listener.model.swap(next)
        }
        runCatching { old.close() }.onFailure { logInternal("serve '$name': the head before could not be closed: ${it.message}", LogLevel.ERROR) }
        logInternal("serve '$name': $note")
        AccessLog.note(name, note)
        return true
    }

    /** Resumes every module that was listening when the app closed */
    fun restore() {
        val names = (readRegistry().modules.keys + (modulesDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList())).distinct()
        names.filter { readServing(it).enabled }.forEach { start(it) }
    }

    fun stopAll() = listeners.keys.toList().forEach { stop(it, persist = false) }

    private fun handle(listener: Listener, route: Route, exchange: HttpExchange) {
        val begun = System.nanoTime()
        val requestId = AccessLog.newId()
        exchange.responseHeaders.add("X-Request-Id", requestId)
        // What the access log says of this request; an exchange's attributes are its context's, shared by every request
        val trace = AccessLog.Trace().also(AccessLog.current::set)
        exchange.use {
            try {
                val settings = listener.settings
                val method = exchange.requestMethod.uppercase()
                val allowed = route.allowed(settings)
                // A browser's request from a page: an origin on the list gets the CORS headers, on every answer, and its
                // preflight; another origin is turned away. Without a list nothing changes
                val origin = exchange.requestHeaders.getFirst("Origin")
                if (settings.cors != null && origin != null) {
                    if (!allows(settings.cors, origin)) return refuse(listener, exchange, 403, "the origin $origin is not allowed", method)
                    exchange.responseHeaders.add("Access-Control-Allow-Origin", origin)
                    exchange.responseHeaders.add("Vary", "Origin")
                    exchange.responseHeaders.add("Access-Control-Expose-Headers", "Retry-After")
                    if (method == "OPTIONS") {
                        exchange.responseHeaders.add("Access-Control-Allow-Methods", (allowed + "OPTIONS").joinToString(", "))
                        exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type, Authorization")
                        exchange.responseHeaders.add("Access-Control-Max-Age", "600")
                        exchange.sendResponseHeaders(204, -1)
                        return
                    }
                }
                if (method !in allowed) {
                    logInternal("serve '${listener.name}': $method from ${exchange.remoteAddress.address.hostAddress} not allowed on /${route.suffix} (405)")
                    respond(exchange, 405, error("${method} not allowed, use $allowed"))
                    return
                }
                if (settings.apiKey != null && !keyMatches(Secrets.resolve(settings.apiKey), exchange.requestHeaders.getFirst("Authorization"))) {
                    exchange.responseHeaders.add("WWW-Authenticate", "Bearer realm=\"amie\"")
                    return refuse(listener, exchange, 401, "send the key as the header Authorization: Bearer <key>", method)
                }
                // A lesson changes the model, so from another machine it needs a key; this machine's own are taken as before
                if (route != Route.PREDICT && settings.apiKey == null && exchange.remoteAddress?.address?.isLoopbackAddress == false) {
                    return refuse(listener, exchange, 403, "teaching from another machine needs a key: set one under Access and send it as Authorization: Bearer <key>", method)
                }
                // Counted after the key, so requests without it can't use up the minute of those with it
                listener.window?.take()?.let { waitMs ->
                    val seconds = (waitMs + 999) / 1000
                    exchange.responseHeaders.add("Retry-After", seconds.toString())
                    return refuse(listener, exchange, 429, "over ${settings.rateLimitPerMinute} requests a minute; try again in $seconds s", method)
                }
                // What was sent, the GET's text or the body; null when it is past maxBodyBytes
                val max = settings.maxBodyBytes
                val sent = when (method) {
                    "GET" -> (query(exchange)["text"] ?: "").takeIf { max == null || it.toByteArray(Charsets.UTF_8).size <= max }
                    else -> bodyOf(exchange, max)
                } ?: return refuse(listener, exchange, 413, "the request is over $max bytes", method)
                when (route) {
                    Route.PREDICT -> handlePredict(listener, exchange, method, sent, trace)
                    Route.TEACH -> handleTeach(listener, exchange, method, sent, trace)
                    Route.RETRAIN -> handleRetrain(listener, exchange, method, sent, trace)
                }
            } catch (e: Exception) {
                listener.errors.incrementAndGet()
                logInternal("serve '${listener.name}': ${e.message}", LogLevel.ERROR)
                trace.outcome = "failed: ${e.javaClass.simpleName}"
                runCatching { respond(exchange, 500, error(e.message ?: e.toString())) }
            } finally {
                AccessLog.request(listener.name, requestId, exchange, trace, (System.nanoTime() - begun) / 1_000_000)
                AccessLog.current.remove()
            }
        }
    }

    // <path>/predict: the text through the model, gated, logged, recorded and answered
    private fun handlePredict(listener: Listener, exchange: HttpExchange, method: String, sent: String, trace: AccessLog.Trace) {
        val text = (if (method == "GET") sent else textOf(sent))?.trim()
        if (text.isNullOrEmpty()) {
            logInternal("serve '${listener.name}': $method from ${exchange.remoteAddress.address.hostAddress} sent no text (400)")
            respond(exchange, 400, error("send text as {\"text\": \"...\"}, a plain-text body or ?text="))
            return
        }
        trace.sent = text.length
        listener.requests.incrementAndGet()
        listener.lastRequestAt.set(System.currentTimeMillis())

        val begun = System.nanoTime()
        val predictions = listener.model.inference(text).map { entry ->
            val label = entry.substringBeforeLast(':')
            label to (entry.substringAfterLast(':').toDoubleOrNull() ?: 0.0)
        }
        val tookMs = (System.nanoTime() - begun) / 1_000_000
        listener.latencyTotalMs.addAndGet(tookMs)
        listener.latencyLastMs.set(tookMs)
        listener.latencyMaxMs.accumulateAndGet(tookMs, ::maxOf)
        val top = predictions.firstOrNull()
        val gated = listener.settings.gate(predictions)
        val abstained = gated.abstained == true
        if (abstained) listener.abstained.incrementAndGet()
        val shown = if (listener.keepTexts) "\"${text.take(60)}\"" else "(${text.length} characters, not kept)"
        val answered = if (!abstained) top?.first ?: "-"
        else "${gated.predictions.firstOrNull()?.first ?: "no label"} (abstained: " +
            (top?.let { "${it.first} ${String.format(Locale.US, "%.3f", it.second)}" } ?: "no score") + " under ${listener.settings.minScore})"
        logInternal("serve '${listener.name}': $method from ${exchange.remoteAddress.address.hostAddress} $shown -> $answered")
        trace.outcome = if (abstained) "abstained" else top?.let { "${it.first} ${String.format(Locale.US, "%.3f", it.second)}" } ?: "no label"
        record(listener.name, method, text.takeIf { listener.keepTexts }, text.length, top, abstained)

        respond(exchange, 200, buildJsonObject {
            put("model", listener.name)
            put("predictions", scored(gated.predictions))
            gated.abstained?.let { put("abstained", it) }
            gated.ranking?.let { put("ranking", scored(it)) }
            listener.version?.let { put("version", it) }
        })
    }

    // <path>/teach: POST {"text", "label"} puts the sentence, labeled, at the top of the model's queue (the text it has
    // already gets the label); DELETE {"text"} or ?text= takes it out; GET lists the queue. The queue is what the next
    // /retrain learns from. The lesson is logged without its text in the access log, as every request is
    private fun handleTeach(listener: Listener, exchange: HttpExchange, method: String, sent: String, trace: AccessLog.Trace) {
        val name = listener.name
        val who = exchange.remoteAddress.address.hostAddress
        val queue = LabelQueue.fileFor(name)
        when (method) {
            "GET" -> {
                val rows = LabelQueue.rows(queue)
                trace.outcome = "queue: ${rows.count { it.labeled }} labeled, ${rows.size - rows.count { it.labeled }} to label"
                respond(exchange, 200, buildJsonObject {
                    put("model", name)
                    listener.version?.let { put("version", it) }
                    put("queue", queue.name)
                    put("labeled", rows.count { it.labeled })
                    put("unlabeled", rows.count { !it.labeled })
                    put("rows", buildJsonArray {
                        rows.forEach { r ->
                            add(buildJsonObject {
                                put("text", r.text)
                                put("label", r.label)
                                if (r.guess.isNotEmpty()) put("guess", r.guess)
                                r.score.toDoubleOrNull()?.let { put("score", it) }
                                if (r.runnerUp.isNotEmpty()) put("runnerUp", r.runnerUp)
                            })
                        }
                    })
                })
            }
            "POST" -> {
                val fields = fieldsOf(sent)
                val text = fields["text"]?.trim().orEmpty()
                val label = fields["label"]?.trim().orEmpty()
                if (text.isEmpty() || label.isEmpty()) {
                    logInternal("serve '$name': teach from $who without a ${if (text.isEmpty()) "text" else "label"} (400)")
                    respond(exchange, 400, error("send {\"text\": \"...\", \"label\": \"...\"}"))
                    return
                }
                trace.sent = text.length
                var reason = "not written"
                val file = teach(name, text, label, onRefused = { reason = it })
                if (file == null) {
                    trace.outcome = "teach refused: $reason"
                    respond(exchange, 409, error(reason))
                    return
                }
                listener.taught.incrementAndGet()
                val rows = LabelQueue.rows(file)
                val labeled = rows.count { it.labeled }
                // In the model's spelling of a label it knows, as the queue has it: "Lights" is its "lights"
                val spelled = rows.firstOrNull { it.text.equals(text, ignoreCase = true) }?.label?.takeIf { it.isNotEmpty() } ?: label
                logInternal("serve '$name': teach from $who \"${text.take(60)}\" is $spelled; $labeled labeled ${if (labeled == 1) "row" else "rows"} in ${file.name}")
                trace.outcome = "taught: $spelled"
                respond(exchange, 200, buildJsonObject {
                    put("model", name)
                    listener.version?.let { put("version", it) }
                    put("text", text)
                    put("label", spelled)
                    put("queue", file.name)
                    put("labeled", labeled)
                })
            }
            "DELETE" -> {
                val text = (fieldsOf(sent)["text"] ?: query(exchange)["text"])?.trim().orEmpty()
                if (text.isEmpty()) {
                    logInternal("serve '$name': forget from $who without a text (400)")
                    respond(exchange, 400, error("send {\"text\": \"...\"} or ?text="))
                    return
                }
                trace.sent = text.length
                val had = LabelQueue.rows(queue).firstOrNull { it.text.equals(text, ignoreCase = true) }
                val file = LabelQueue.ofModel(name) { if (had != null) LabelQueue.remove(it, text) else it }
                if (file == null) {
                    trace.outcome = "forget refused: the model is gone"
                    respond(exchange, 409, error("'$name' was renamed or destroyed meanwhile; nothing was taken out of its queue"))
                    return
                }
                if (had != null) listener.taught.incrementAndGet()
                val labeled = LabelQueue.rows(file).count { it.labeled }
                logInternal("serve '$name': forget from $who \"${text.take(60)}\"" + (if (had != null) " (was ${had.label.ifEmpty { "unlabeled" }}); $labeled labeled left in ${file.name}" else ": not in ${file.name}"))
                trace.outcome = if (had != null) "forgot: ${had.label.ifEmpty { "unlabeled" }}" else "forgot: not in the queue"
                respond(exchange, 200, buildJsonObject {
                    put("model", name)
                    listener.version?.let { put("version", it) }
                    put("text", text)
                    put("removed", had != null)
                    had?.label?.takeIf { it.isNotEmpty() }?.let { put("label", it) }
                    put("queue", file.name)
                    put("labeled", labeled)
                })
            }
        }
    }

    // <path>/retrain: POST starts a fine-tune on the labeled rows of the model's queue, in the background, and answers
    // 202 at once (200 or 500 with its result when asked to wait); GET says how it goes. The model answers meanwhile
    // with the head it has; the new one is swapped in once written ([reload], from writeModule)
    private fun handleRetrain(listener: Listener, exchange: HttpExchange, method: String, sent: String, trace: AccessLog.Trace) {
        val name = listener.name
        if (method == "GET") {
            val job = retrains[name]
            trace.outcome = "retrain: ${job?.state ?: "idle"}"
            respond(exchange, 200, retrainStatus(name, job))
            return
        }
        val fields = fieldsOf(sent)
        val wait = fields["wait"]?.toBooleanStrictOrNull() ?: query(exchange)["wait"]?.toBooleanStrictOrNull() ?: false
        val job = synchronized(retrains) {
            retrains[name]?.takeIf { it.state == RetrainJob.RUNNING }?.let {
                trace.outcome = "retrain refused: one is running"
                respond(exchange, 409, retrainStatus(name, it, "a retrain of '$name' is running since ${it.startedAt}; GET this path to follow it"))
                return
            }
            ModelBuildProgress.current.value?.let {
                trace.outcome = "retrain refused: busy"
                respond(exchange, 409, retrainStatus(name, retrains[name], "busy ${it.verb} ${it.modelName}; try again when it is done"))
                return
            }
            val queue = LabelQueue.fileFor(name)
            val labeled = LabelQueue.rows(queue).count { it.labeled }
            if (labeled == 0) {
                trace.outcome = "retrain refused: nothing to learn"
                respond(exchange, 409, retrainStatus(name, retrains[name], "nothing to learn: ${queue.name} has no labeled rows; POST a text and its label to ${listener.settings.pathFor(name, Route.TEACH.suffix)} first"))
                return
            }
            val from = versionOf(name) ?: 1
            RetrainJob(name, from, labeled, queue).also {
                retrains[name] = it
                listener.retrains.incrementAndGet()
                retrainExecutor.execute { runRetrain(it) }
            }
        }
        logInternal("serve '$name': retrain from ${exchange.remoteAddress.address.hostAddress}: fine-tuning v${job.from} on the ${job.rows} labeled rows of ${job.queue.name}; the model answers with v${job.from} meanwhile")
        if (wait) job.done.await()
        trace.outcome = "retrain: ${job.state}" + (job.to?.let { " v${job.from} → v$it" } ?: "")
        respond(exchange, if (!wait) 202 else if (job.state == RetrainJob.DONE) 200 else 500, retrainStatus(name, job))
    }

    /** One fine-tune started over /retrain: what it began from, what it learned on and how it ended */
    private class RetrainJob(val model: String, val from: Int, val rows: Int, val queue: File) {
        val startedAt: Instant = Instant.now()
        @Volatile var finishedAt: Instant? = null
        @Volatile var state: String = RUNNING
        @Volatile var detail: String = ""
        @Volatile var to: Int? = null
        val done = CountDownLatch(1)

        fun end(state: String, detail: String) {
            this.state = state
            this.detail = detail
            finishedAt = Instant.now()
            done.countDown()
        }

        companion object {
            const val RUNNING = "running"
            const val DONE = "done"
            const val FAILED = "failed"
        }
    }

    private val retrains = ConcurrentHashMap<String, RetrainJob>()
    // One at a time: a fine-tune takes the encoder and the build progress, which the page shows, for itself
    private val retrainExecutor = Executors.newSingleThreadExecutor { Thread(it, "amie-retrain").apply { isDaemon = true } }

    /** The embedder a retrain fine-tunes with: the model's encoder unless the tests hand in one of their own */
    internal var embedderFor: (String) -> Embedder? = { null }

    // The fine-tune of one job, on the retrain thread. writeModule swaps the new head into the listener (and the
    // model's open session) once it is written; the queue is graded again so it shows what the new head answers
    private fun runRetrain(job: RetrainJob) {
        try {
            val source = DataSource.read(job.queue.path) ?: throw IllegalStateException("${job.queue.name} has no text and label columns")
            val embedder = embedderFor(job.model)
            val file = fineTuneClassifier(job.model, source.samples(), dataName = job.queue.name, embedder = embedder)
            val last = ModelBuildProgress.history.value.firstOrNull { it.modelName == job.model && it.job == "fineTune" }
            if (file == null) {
                job.end(RetrainJob.FAILED, last?.detail?.takeIf { it.isNotBlank() } ?: "not fine-tuned; the model's console says why")
                logInternal("serve '${job.model}': retrain failed: ${job.detail}; still answering with v${job.from}", LogLevel.ERROR)
                return
            }
            job.to = readModule(job.model)?.second?.let(ModelVersions::numberOf)
            runCatching { regradeQueue(job.model, embedder) }
            job.end(RetrainJob.DONE, last?.detail?.takeIf { it.isNotBlank() } ?: "fine-tuned v${job.from} → v${job.to}")
            logInternal("serve '${job.model}': retrain done: ${job.detail}")
        } catch (e: Throwable) {
            job.end(RetrainJob.FAILED, e.message ?: e.javaClass.simpleName)
            logInternal("serve '${job.model}': retrain failed: ${job.detail}; still answering with v${job.from}", LogLevel.ERROR)
        }
    }

    /** The last retrain of [name] as GET <path>/retrain tells it, or idle; [error] is the reason a POST was refused */
    private fun retrainStatus(name: String, job: RetrainJob?, error: String? = null): JsonObject = buildJsonObject {
        put("model", name)
        put("status", job?.state ?: "idle")
        versionOf(name)?.let { put("version", it) }
        put("labeled", LabelQueue.rows(LabelQueue.fileFor(name)).count { it.labeled })
        if (job != null) {
            put("from", job.from)
            job.to?.let { put("to", it) }
            put("rows", job.rows)
            put("startedAt", job.startedAt.toString())
            job.finishedAt?.let { put("finishedAt", it.toString()) }
            if (job.detail.isNotBlank()) put("detail", job.detail)
            if (job.state == RetrainJob.RUNNING) ModelBuildProgress.current.value?.takeIf { it.modelName == name }?.let { put("phase", it.describe()) }
        }
        error?.let { put("error", it) }
    }

    // The version of the head the module has, quietly: null for a name no module is exported under (the tests' fakes)
    private fun versionOf(name: String): Int? = if (moduleExists(name)) readModule(name)?.second?.let(ModelVersions::numberOf) else null

    // The string fields of a JSON object body: {"text": "...", "label": "...", "wait": true}; empty for anything else
    private fun fieldsOf(body: String): Map<String, String> =
        runCatching { Json.parseToJsonElement(body).jsonObject.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap() }.getOrDefault(emptyMap())

    private fun scored(predictions: List<Pair<String, Double>>) = buildJsonArray {
        predictions.forEach { (label, score) -> add(buildJsonObject { put("label", label); put("score", score) }) }
    }

    // Incoming data is kept per model so it can be reviewed or labeled later; the label and score are the model's own
    private fun record(name: String, method: String, text: String?, length: Int, top: Pair<String, Double>?, abstained: Boolean) {
        val line = buildJsonObject {
            put("time", Instant.now().toString())
            put("method", method)
            if (text != null) put("text", text) else put("length", length)
            top?.let { put("label", it.first); put("score", it.second) }
            if (abstained) put("abstained", true)
        }
        runCatching { File(moduleDir(name), "incoming.jsonl").appendText(Json.encodeToString(JsonObject.serializer(), line) + "\n") }
    }

    // The body, or null when it has more than [max] bytes: read no further than one past it, nothing when its length says so
    private fun bodyOf(exchange: HttpExchange, max: Int?): String? {
        if (max == null) return exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        val declared = exchange.requestHeaders.getFirst("Content-Length")?.trim()?.toLongOrNull()
        if (declared != null && declared > max) return null
        val bytes = exchange.requestBody.readNBytes(minOf(max.toLong() + 1, Int.MAX_VALUE.toLong()).toInt())
        return if (bytes.size > max) null else bytes.toString(Charsets.UTF_8)
    }

    // The text a body sends: JSON {"text": ...} when it parses as such, otherwise the body itself; null when it is blank
    private fun textOf(body: String): String? {
        if (body.isBlank()) return null
        return runCatching { Json.parseToJsonElement(body).jsonObject["text"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: body
    }

    // An origin on the list, as the browser sends it (scheme://host[:port]), or any when the list has *
    private fun allows(list: List<String>, origin: String) = list.any { it == "*" || it.trimEnd('/').equals(origin.trimEnd('/'), ignoreCase = true) }

    // The key in "Authorization: Bearer <key>", compared in constant time so how long it takes tells nothing of the key
    private fun keyMatches(key: String, header: String?): Boolean {
        val sent = header?.trim()?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim() ?: return false
        return MessageDigest.isEqual(sent.toByteArray(Charsets.UTF_8), key.toByteArray(Charsets.UTF_8))
    }

    // A request the access settings turn away: counted and logged, without its text, never recorded, and answered [status]
    private fun refuse(listener: Listener, exchange: HttpExchange, status: Int, message: String, method: String) {
        listener.refused.incrementAndGet()
        logInternal("serve '${listener.name}': $method from ${exchange.remoteAddress.address.hostAddress} refused ($status): $message")
        AccessLog.current.get()?.outcome = "refused: $message"
        respond(exchange, status, error(message))
    }

    private fun query(exchange: HttpExchange): Map<String, String> =
        exchange.requestURI.rawQuery?.split("&")?.mapNotNull { pair ->
            val (k, v) = pair.split("=", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
        }?.toMap() ?: emptyMap()

    private fun error(message: String) = buildJsonObject { put("error", message) }

    private fun respond(exchange: HttpExchange, status: Int, body: JsonObject) {
        val bytes = Json.encodeToString(JsonObject.serializer(), body).toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        AccessLog.current.get()?.let { trace ->
            trace.bytes = bytes.size
            if (status >= 400 && trace.outcome == null) trace.outcome = body["error"]?.jsonPrimitive?.contentOrNull ?: "error"
        }
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}

/**
 * The access log of a served model, AMP/models/intents/<name>/access.log: one line a request, tab-separated so it reads in a
 * terminal and splits in a spreadsheet, and a line when it starts or stops listening. Past [MAX_BYTES] it moves to
 * access.1.log (the one before that goes), so it never grows without end. Never a text: only its length.
 */
object AccessLog {
    /** What became of one request, filled in as it is handled: the text's length, the answer's bytes, the outcome */
    internal class Trace(var sent: Int? = null, var bytes: Int = 0, var outcome: String? = null)

    /** The request the thread is handling right now; a request is handled on one thread from start to end */
    internal val current = ThreadLocal<Trace>()
    const val MAX_BYTES = 2_000_000L
    private val random = java.security.SecureRandom()

    fun file(name: String): File = File(moduleDir(name), "access.log")

    /** A short id for a request: 8 hex digits, sent back as X-Request-Id so a client can name the line */
    fun newId(): String = "%08x".format(random.nextInt())

    /** The request's line: time, id, client, method, path (no query: a GET's text is in it), status, ms, bytes in and out, outcome */
    internal fun request(name: String, id: String, exchange: HttpExchange, trace: Trace, millis: Long) {
        val status = exchange.responseCode.takeIf { it > 0 } ?: 0
        val line = listOf(
            Instant.now().toString(), id,
            exchange.remoteAddress?.address?.hostAddress ?: "-",
            exchange.requestMethod.uppercase(),
            exchange.requestURI.rawPath ?: "-",
            status.toString(),
            "${millis}ms",
            "in ${trace.sent?.let { "$it chars" } ?: "-"}",
            "out ${trace.bytes}B",
            (trace.outcome ?: if (status == 204) "preflight" else "-").replace('\t', ' ').replace('\n', ' '),
        ).joinToString("\t")
        append(name, line)
    }

    /** A line of its own: listening, stopped */
    fun note(name: String, what: String) = append(name, "${Instant.now()}\t-\t-\t-\t-\t-\t-\t-\t-\t$what")

    /** The last [count] lines, oldest first */
    fun tail(name: String, count: Int = 50): List<String> =
        runCatching { file(name).readLines().takeLast(count) }.getOrDefault(emptyList())

    @Synchronized
    private fun append(name: String, line: String) {
        runCatching {
            val file = file(name)
            if (!file.parentFile.isDirectory) return
            if (file.length() > MAX_BYTES) {
                val older = File(file.parentFile, "access.1.log")
                older.delete()
                file.renameTo(older)
            }
            file.appendText(line + "\n")
        }.onFailure { logInternal("serve '$name': access log not written: ${it.message}", LogLevel.ERROR) }
    }
}
