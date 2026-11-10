import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Properties
import org.example.project.getDirectory

// The model over HTTP: under the threshold it answers "oos" instead of an intent
val results = File(getDirectory().toString(), "clinc-oos")
val kept = listOf("oos-class.properties", "threshold.properties").map { File(results, it) }.first { it.isFile }
val threshold = Properties().apply { kept.reader().use(::load) }.getProperty("threshold").toDouble()

val url = serve("clinc", port = 8765, minScore = threshold, fallbackLabel = "oos")
    ?: throw IllegalStateException("the model could not be served")
val http = HttpClient.newHttpClient()
// One of the 150 intents, then two questions that are none of them
for (text in listOf("how do i say thank you in french", "can you recommend a good dentist", "what is the best way to learn guitar")) {
    val request = HttpRequest.newBuilder(URI(url)).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("""{"text": "$text"}""")).build()
    val body = http.send(request, HttpResponse.BodyHandlers.ofString()).body()
    val label = Regex(""""label":"([^"]+)"""").find(body)?.groupValues?.get(1)
    log("$text -> $label" + if ("\"abstained\":true" in body) " (under the threshold)" else "")
}
log("curl -s $url -H 'Content-Type: application/json' -d '{\"text\": \"set an alarm for 7\"}'")
stopServing("clinc")
