import java.io.File
import java.util.Properties
import org.example.project.getDirectory

// The paper's other way: out of scope as a 151st intent, taught from 100 examples, with the threshold on top.
// Kept only when validation says it is better; otherwise the model goes back to the version before
fun rows(file: String) = sourceFile(file).second.values.map { it[0] to it[1] }
fun inScope(scored: List<Pair<Boolean, Float>>, t: Double) = scored.count { (right, s) -> right && s >= t }.toDouble() / scored.size
fun oosRecall(scored: List<Pair<String, Float>>, t: Double) = scored.count { (guess, s) -> guess == "oos" || s < t }.toDouble() / scored.size

val results = File(getDirectory().toString(), "clinc-oos")
val before = Properties().apply { File(results, "threshold.properties").reader().use(::load) }
val beforeT = before.getProperty("threshold").toDouble()

// The version before, scored on validation the same way, to compare against
fun validation(): Pair<List<Pair<Boolean, Float>>, List<Pair<String, Float>>> = quietly { sessionMod("clinc") {
    fun best(text: String) = scores(text).entries.first().let { it.key to it.value }
    rows("clinc-val.csv").map { (text, label) -> best(text).let { (g, s) -> (g == label) to s } } to
        rows("clinc-val-oos.csv").map { (text, _) -> best(text) }
} }
val (oldIn, oldOos) = validation()
val oldScore = (inScope(oldIn, beforeT) + oosRecall(oldOos, beforeT)) / 2
val keptVersion = versions("clinc").first { it.current }.number

fineTune("clinc", source("clinc-oos-train.csv"))
calibrate("clinc", source("clinc-val.csv"))

val (newIn, newOos) = validation()
val t = (0..99).map { it / 100.0 }.maxBy { (inScope(newIn, it) + oosRecall(newOos, it)) / 2 }
val newScore = (inScope(newIn, t) + oosRecall(newOos, t)) / 2
log("validation, mean of in-scope accuracy and out-of-scope recall: threshold only %.3f, with the oos intent %.3f".format(oldScore, newScore))

if (newScore <= oldScore) {
    rollback("clinc", keptVersion)
    log("kept the threshold-only model")
} else quietly { sessionMod("clinc") {
    fun best(text: String) = scores(text).entries.first().let { it.key to it.value }
    val testIn = rows("clinc-test.csv").map { (text, label) -> best(text).let { (g, s) -> (g == label) to s } }
    val testOos = rows("clinc-test-oos.csv").map { (text, _) -> best(text) }
    log("Test with the oos intent, threshold %.2f".format(t))
    log("  in-scope accuracy     %.3f (threshold only: %.3f)".format(inScope(testIn, t), before.getProperty("test.inScope").toDouble()))
    log("  out-of-scope recall   %.3f (threshold only: %.3f)".format(oosRecall(testOos, t), before.getProperty("test.oosRecall").toDouble()))
    File(results, "oos-class.properties").writeText(
        "threshold=$t\ntest.inScope=${inScope(testIn, t)}\ntest.oosRecall=${oosRecall(testOos, t)}\n"
    )
} }
