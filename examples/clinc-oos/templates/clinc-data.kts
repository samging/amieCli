import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.getDirectory

// CLINC150 (Larson et al., 2019): 150 intents and out-of-scope queries, github.com/clinc/oos-eval, CC BY 3.0.
// Downloaded into AMP once with shell(), then one csv per split that source() and sourceFile() read
val amp = File(getDirectory().toString())
val splits = mapOf(
    "clinc-train.csv" to "train", "clinc-val.csv" to "val", "clinc-val-oos.csv" to "oos_val",
    "clinc-test.csv" to "test", "clinc-test-oos.csv" to "oos_test", "clinc-oos-train.csv" to "oos_train",
)
if (splits.keys.any { !File(amp, it).isFile }) {
    val download = shell("curl -sfL -o clinc.json https://raw.githubusercontent.com/clinc/oos-eval/master/data/data_full.json",
        dir = amp.path, timeoutSeconds = 300)
    check(download.ok) { "CLINC150 download failed: ${download.err}" }
    val data = Json.parseToJsonElement(File(amp, "clinc.json").readText()).jsonObject
    fun cell(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    splits.forEach { (file, key) ->
        val rows = data.getValue(key).jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.content } }
        File(amp, file).writeText("text,label\n" + rows.joinToString("") { (text, label) -> "${cell(text)},${cell(label)}\n" })
    }
}

val train = source("clinc-train.csv")
log(train)

// Validation picks the head and the threshold; the test files stay unseen until the model is done
relate(train, "clinc-val.csv")

for (file in splits.keys.drop(2)) log("$file: ${sourceFile(file).first} rows")
