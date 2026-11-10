import java.io.File
import org.example.project.getDirectory

// A Google assistant in one script: a model trained to tell which service a request is for, its metrics, then each
// request answered by that package: google-calendar, google-tasks, google-drive, gmail-reader, google-translate and
// browser. A package that isn't set up says how to set it up; nothing is created, changed or deleted
val model = "assistant-router"
val minScore = 0.6

// 1. The data: requests written for each action, kept here and written to AMP once
val amp = File(getDirectory().toString())
fun writeCsv(name: String, rows: Map<String, List<String>>) {
    val file = File(amp, name)
    if (!file.isFile) file.writeText("text,label\n" + rows.flatMap { (label, texts) -> texts.map { "\"$it\",$label\n" } }.joinToString(""))
}
writeCsv("assistant-train.csv", trainRows())
writeCsv("assistant-test.csv", testRows())
val train = source("assistant-train.csv")
log(train)
relate(train, "assistant-test.csv")

// 2. The model: the bge encoder with a small head, the best of the default heads by macro F1 on the test file;
// five folds put a range on the scores and find the rows that look mislabeled
modelBuilder(model, train, buildOptions(folds = 5))

// 3. Its metrics
validateMetrics(model)
metricsChart(model)
confusionMatrix(model)
calibrationChart(model)
mistakes(model)
suspects(model)

// 4. The assistant: each request goes to the package of the action the model gives, with the command it understands
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

val requests = listOf(
    "what's on my calendar this week",
    "show my to-do list",
    "any new emails today",
    "find the spreadsheet called budget in my drive",
    "translate good morning to german",
    "how do you say thank you very much in japanese",
    "who was ada lovelace",
    "latest news about artificial intelligence",
    "play some jazz music",
)

val router = sessionMod(model)
for (request in requests) {
    val (action, score) = quietly { router.scores(request).entries.first() }
    if (score < minScore) {
        log("\"$request\" -> not sure (${action} %.2f, under %.2f); no package asked".format(score, minScore))
        continue
    }
    val route = routes.getValue(action)
    val command = route.command(request)
    log("\"$request\" -> $action %.2f -> ${route.jar} \"$command\"".format(score))
    val answer = handleBy({ e -> listOf("  needs setup: " + (e.message ?: "").substringAfter("can't run: ").substringBefore(" · ") +
        "; amie package setup ${route.jar}") }) {
        initMod(route.jar).let { pkg -> try { pkg.run(command).take(3).map { "  " + it.take(160) } } finally { pkg.close() } }
    }
    answer.forEach(::log)
}

fun trainRows(): Map<String, List<String>> = mapOf(
    "calendar" to listOf(
        "what's on my calendar today", "do i have any meetings today", "show me my schedule for this week",
        "what are my appointments tomorrow", "am i free this afternoon", "list my events for the week",
        "when is my next meeting", "what does my day look like", "check my calendar", "anything scheduled for friday",
        "what's coming up on my agenda", "do i have plans tonight", "show upcoming events", "how busy am i this week",
        "is there a meeting at 3", "what meetings do i have on monday", "open my google calendar",
        "read me today's events", "what time is my dentist appointment", "do i have anything booked this weekend",
        "what's my first meeting tomorrow", "show my calendar for next week", "any events on my calendar",
        "when am i meeting the team", "what's planned for today", "list today's appointments",
        "is my afternoon clear", "what's next on my schedule", "tell me my meetings", "calendar for the week please",
    ),
    "tasks" to listOf(
        "show my to-do list", "what tasks do i have", "list my tasks", "what's on my todo list",
        "what do i still need to do", "show my open tasks", "any tasks due today", "read my task list",
        "which tasks are left", "what's on my google tasks", "show me my pending to-dos",
        "what should i work on next", "list everything i have to do", "how many tasks are open", "what are my chores",
        "check my todo list", "show unfinished tasks", "what's due this week on my list", "my tasks please",
        "do i have tasks overdue", "what's left on my checklist", "show all my task lists",
        "remind me what i need to finish", "what items are on my to do", "open google tasks",
        "what's on my list for today", "tasks for today", "show my reminders list", "what jobs are still open",
        "list my to-dos",
    ),
    "drive" to listOf(
        "show my recent files in drive", "find a file in google drive", "what files did i edit recently",
        "search my drive for the budget", "list my google drive files", "open my drive",
        "find the document called report", "where is my presentation", "show recent documents",
        "search drive for invoice", "what's in my google drive", "find my spreadsheet named expenses",
        "list files in my drive folder", "look for the slides about the project", "find the pdf i uploaded",
        "show me my latest drive files", "search my files for contract", "what documents do i have",
        "find the file named resume", "browse my drive", "recent files please", "which files were changed today",
        "find my notes in drive", "get the file called meeting notes", "search google drive for photos",
        "show the newest files", "locate my thesis document", "list documents in drive", "find a sheet about sales",
        "drive files from this week",
    ),
    "email" to listOf(
        "do i have new emails", "check my inbox", "any unread mail", "read my latest emails",
        "show unread messages in gmail", "how many unread emails do i have", "did anyone email me", "check gmail",
        "what's in my inbox", "any new messages from work", "show my recent emails", "read the last email",
        "do i have mail", "search my email for the invoice", "any emails from my boss", "list my unread gmail",
        "open my mailbox", "what emails came in today", "check for new mail", "show me emails about the trip",
        "find the email from amazon", "any important emails", "read my unread messages",
        "is there an email from the bank", "inbox summary please", "show my gmail", "count my unread emails",
        "what did i get in the mail today", "new email check", "look at my email",
    ),
    "translate" to listOf(
        "translate hello into spanish", "how do you say thank you in french", "translate good night to german",
        "what is cheese in italian", "say where is the bathroom in japanese", "translate i love you to portuguese",
        "how do i say good morning in chinese", "translate this to korean: see you soon", "what's dog in dutch",
        "translate how much does it cost into russian", "how do you say happy birthday in arabic",
        "translate excuse me to polish", "say nice to meet you in swedish", "translate the bill please into turkish",
        "how do you say goodbye in greek", "translate welcome into hindi", "what does bonjour mean in english",
        "translate i am hungry to spanish", "how to say water in french",
        "translate where is the station into german", "say thank you very much in italian",
        "translate one coffee please to portuguese", "how do you say friend in japanese",
        "translate i don't understand into chinese", "what's the french word for apple",
        "translate good luck to korean", "how do i say help in spanish", "translate see you tomorrow into dutch",
        "say cheers in german", "translate my name is sam to french",
    ),
    "web_lookup" to listOf(
        "who was alan turing", "what is quantum computing", "tell me about the eiffel tower",
        "look up the history of rome", "who invented the telephone", "what is machine learning",
        "find information about black holes", "who is the president of france", "what is the capital of australia",
        "tell me about marie curie", "search the web for python programming", "what is photosynthesis",
        "who wrote war and peace", "look up the great wall of china", "what does dna stand for",
        "tell me about the moon landing", "who painted the mona lisa", "what is bitcoin",
        "find out about climate change", "who is ada lovelace", "what is the tallest mountain",
        "look up mount everest", "tell me about ancient egypt", "what are neural networks",
        "who discovered penicillin", "information about the roman empire", "what is a transformer model",
        "search for facts about octopuses", "who founded google", "what is the theory of relativity",
    ),
    "news" to listOf(
        "what's the news today", "show me the headlines", "latest news please", "what's happening in the world",
        "news about technology", "any news on the economy", "read me the top stories", "what are today's headlines",
        "sports news", "latest news about artificial intelligence", "what's new in science", "give me the news",
        "headlines about the election", "what happened today", "breaking news", "news on climate",
        "business headlines", "what's trending in the news", "latest world news", "news about space exploration",
        "today's top headlines", "any news about apple", "tech headlines please", "what's in the news this morning",
        "news from europe", "latest stories about health", "show recent news", "news about football",
        "current events please", "morning news briefing",
    ),
)

fun testRows(): Map<String, List<String>> = mapOf(
    "calendar" to listOf(
        "what's on for tomorrow morning", "do i have a call later", "show this week's meetings",
        "am i busy on thursday", "what's my schedule like", "next appointment please", "my events today",
        "anything on the calendar tonight", "when do i meet the client", "plans for saturday",
    ),
    "tasks" to listOf(
        "what's left to do today", "show my todos", "open tasks please", "anything overdue on my list",
        "what's on my to do list this week", "read my tasks", "tasks still pending", "what do i have to finish",
        "my checklist", "list open to-dos",
    ),
    "drive" to listOf(
        "find my report in drive", "recent drive documents", "search drive for the lease", "where's my spreadsheet",
        "files i opened recently", "find slides called roadmap", "my google drive", "look for the pdf named receipt",
        "documents changed this week", "search files for passport",
    ),
    "email" to listOf(
        "anything new in my inbox", "unread emails please", "read me my mail", "emails from john",
        "check messages in gmail", "new mail today", "any emails about the order", "what's unread",
        "latest message in my inbox", "show emails from the school",
    ),
    "translate" to listOf(
        "translate good evening into french", "how do you say beer in german", "what's hello in japanese",
        "say thank you in korean", "translate where is the hotel to spanish", "how to say love in italian",
        "translate yes please into dutch", "what is bread in portuguese", "say good morning in russian",
        "translate see you later to chinese",
    ),
    "web_lookup" to listOf(
        "who was isaac newton", "what is an atom", "tell me about the amazon rainforest", "look up the pyramids",
        "who invented the light bulb", "what is cloud computing", "facts about whales", "who is serena williams",
        "what is the speed of light", "information about the titanic",
    ),
    "news" to listOf(
        "today's news", "what are the headlines", "news about the stock market", "latest science news",
        "anything new in tech", "top stories today", "world headlines please", "news about the weather",
        "sports headlines", "recent news about electric cars",
    ),
)
