import java.io.File
import org.example.project.getDirectory

// Below the threshold the model says "out of scope" instead of picking one of the 150 intents
fun rows(file: String) = sourceFile(file).second.values.map { it[0] to it[1] }

val results = File(getDirectory().toString(), "clinc-oos").apply { mkdirs() }

// quietly: 8,600 answers without a console line each
quietly { sessionMod("clinc") {
    fun best(text: String) = scores(text).entries.first().let { it.key to it.value }

    val valIn = rows("clinc-val.csv").map { (text, label) -> best(text).let { (guess, score) -> (guess == label) to score } }
    val valOos = rows("clinc-val-oos.csv").map { (text, _) -> best(text).second }

    // The threshold that does best on both at once: the mean of in-scope accuracy and out-of-scope recall
    fun inScope(scored: List<Pair<Boolean, Float>>, t: Double) = scored.count { (right, s) -> right && s >= t }.toDouble() / scored.size
    fun oosRecall(scored: List<Float>, t: Double) = scored.count { it < t }.toDouble() / scored.size
    val threshold = (0..99).map { it / 100.0 }.maxBy { t -> (inScope(valIn, t) + oosRecall(valOos, t)) / 2 }
    log("threshold %.2f, chosen on validation: in-scope %.3f, out-of-scope recall %.3f"
        .format(threshold, inScope(valIn, threshold), oosRecall(valOos, threshold)))

    val testIn = rows("clinc-test.csv").map { (text, label) -> best(text).let { (guess, score) -> (guess == label) to score } }
    val testOos = rows("clinc-test-oos.csv").map { (text, _) -> best(text).second }

    log("Test, ${testIn.size} in-scope and ${testOos.size} out-of-scope queries")
    log("  in-scope accuracy, no threshold   %.3f".format(inScope(testIn, 0.0)))
    log("  in-scope accuracy, threshold      %.3f".format(inScope(testIn, threshold)))
    log("  out-of-scope recall, threshold    %.3f".format(oosRecall(testOos, threshold)))
    log("The trade-off on test")
    log("  threshold  in-scope  out-of-scope recall")
    listOf(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9).forEach { t ->
        log("  %.1f        %.3f     %.3f".format(t, inScope(testIn, t), oosRecall(testOos, t)))
    }

    File(results, "threshold.properties").writeText(
        "threshold=$threshold\n" +
        "test.inScope.noThreshold=${inScope(testIn, 0.0)}\n" +
        "test.inScope=${inScope(testIn, threshold)}\n" +
        "test.oosRecall=${oosRecall(testOos, threshold)}\n"
    )
} }
