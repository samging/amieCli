// A Google assistant on the amie API: a model routes each request to the package that answers it, google-calendar,
// google-tasks, google-drive, gmail-reader, google-translate or the browser. Every command only reads.
// It answers the requests below, then each line typed at the terminal; a script can call it as a function:
//   val assistant by template("google-assistant"); log(assistant("translate good morning to german"))
val model = "assistant-router"
val minScore = 0.6

// 1. The data: 30 requests per action to train on, 10 more per action to test on (setup.sh puts both in AMP)
val train = source("assistant-train.csv")
log(train)
relate(train, "assistant-test.csv")

// 2. The model, built once: the default heads compared over 5 folds, then validated on the test file.
// amie model remove assistant-router builds it again
if (!hasModel(model)) modelBuilder(model, train, buildOptions(folds = 5))
validateMetrics(model)
metricsChart(model)
confusionMatrix(model)
calibrationChart(model)
mistakes(model)
suspects(model)

// 3. What each action asks its package. A jar that isn't in AMP/pluginDir is fetched from GitHub by initMod
class Route(val jar: String, val command: (String) -> String)

val languages = listOf("spanish", "french", "german", "italian", "portuguese", "japanese", "chinese", "korean", "dutch",
    "russian", "arabic", "hindi", "polish", "swedish", "turkish", "greek", "english")
fun clean(text: String) = text.lowercase().trim(' ', '?', '.', '!')
fun translateCommand(text: String): String {
    val lower = clean(text)
    val language = languages.firstOrNull { Regex("\\b$it\\b") in lower } ?: "spanish"
    val phrase = lower.substringAfter(": ", lower)
        .replace(Regex("^(please )?(translate|how do (you|i) say|how to say|say|what is|what's)\\s+"), "")
        .replace(Regex("\\s+(in|into|to)\\s+$language.*$"), "").trim()
    return "to $language: $phrase"
}
fun lookupCommand(text: String) = "find " + clean(text)
    .replace(Regex("^(please )?(who (is|was)|what (is|are)|tell me about|look up|search (the web )?for|find( out)?( about)?|information about|facts about)\\s+"), "")
fun newsCommand(text: String) = "news " + (Regex("(?:news|headlines|stories) (?:about|on|from) (.+)").find(clean(text))?.groupValues?.get(1) ?: "")
fun driveCommand(text: String) = Regex("(?:called|named|for|about) (?:the )?(.+)").find(clean(text))?.groupValues?.get(1)
    ?.replace(Regex("\\s+(in|on|from) (my )?(google )?drive$"), "")?.let { "search $it" } ?: "recent 5"

val routes = mapOf(
    "calendar" to Route("google-calendar.jar") { if ("week" in it.lowercase()) "week" else "today" },
    "tasks" to Route("google-tasks.jar") { "tasks" },
    "drive" to Route("google-drive.jar", ::driveCommand),
    "email" to Route("gmail-reader.jar") { "unread 5" },
    "translate" to Route("google-translate.jar", ::translateCommand),
    "web_lookup" to Route("browser.jar", ::lookupCommand),
    "news" to Route("browser.jar", ::newsCommand),
)

// 4. The assistant. ortClosable closes the model and every package it opened when the block ends
ortClosable("assistant") {
    val router = sessionMod(model)
    fun answer(request: String): String {
        val (action, score) = quietly { router.scores(request).entries.first() }
        if (score < minScore) return "\"$request\" -> not sure (%s %.2f, under %.2f); no package asked".format(action, score, minScore)
        val route = routes.getValue(action)
        val command = route.command(request)
        val lines = handleBy({ e -> listOf(if ("can't run" in "${e.message}") "needs setup: amie package setup ${route.jar}" else "failed: ${e.message}") }) {
            initMod(route.jar).run(command).take(3).map { it.take(160) }
        }
        return "\"$request\" -> $action %.2f -> ${route.jar} \"$command\"\n  ".format(score) + lines.joinToString("\n  ")
    }

    listOf(
        "what's on my calendar this week",
        "show my to-do list",
        "any new emails today",
        "find the spreadsheet called budget in my drive",
        "translate good morning to german",
        "how do you say thank you very much in japanese",
        "who was ada lovelace",
        "latest news about artificial intelligence",
        "play some jazz music",
    ).forEach { log(answer(it)) }

    // Then requests typed at the terminal, or sent by a script through template(); end of input (Ctrl-D) ends it
    log("Type a request, Ctrl-D to end")
    while (true) output(answer(input() ?: break))
}
