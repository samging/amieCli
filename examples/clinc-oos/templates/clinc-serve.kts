// The model over HTTP, asked with curl through shell(): under the threshold it answers "oos" instead of an intent.
// main.kts serves it with the threshold it chose; alone, serve() starts it with the threshold saved then
val url = servingUrl("clinc") ?: serve("clinc", port = 8765) ?: throw IllegalStateException("the model could not be served")

// One of the 150 intents, then two questions that are none of them
for (text in listOf("how do i say thank you in french", "can you recommend a good dentist", "what is the best way to learn guitar")) {
    val answer = shell("curl -s $url -H 'Content-Type: application/json' -d '{\"text\": \"$text\"}'")
    log("$text -> ${answer.out}")
}
accessLog("clinc", last = 3)
output(url)
