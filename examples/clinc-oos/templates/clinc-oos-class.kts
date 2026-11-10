// The paper's other way: out of scope as a 151st intent, taught from 100 examples, with a threshold on top.
// Kept only when validation says it is better; otherwise rollback() makes the version before current again.
// main.kts calls it as a function: it answers "kept" with the threshold and the two test numbers, or "rolled back"
fun rows(file: String) = sourceFile(file).second.values.map { it[0] to it[1] }
fun inScope(scored: List<Pair<Boolean, Float>>, t: Double) = scored.count { (right, s) -> right && s >= t }.toDouble() / scored.size
fun oosRecall(scored: List<Pair<String, Float>>, t: Double) = scored.count { (guess, s) -> guess == "oos" || s < t }.toDouble() / scored.size

// The model as it is now, scored on validation at its own best threshold
fun validation(): Pair<List<Pair<Boolean, Float>>, List<Pair<String, Float>>> = quietly { sessionMod("clinc") {
    fun best(text: String) = scores(text).entries.first().let { it.key to it.value }
    rows("clinc-val.csv").map { (text, label) -> best(text).let { (g, s) -> (g == label) to s } } to
        rows("clinc-val-oos.csv").map { (text, _) -> best(text) }
} }
fun bestThreshold(scored: Pair<List<Pair<Boolean, Float>>, List<Pair<String, Float>>>) =
    (0..99).map { it / 100.0 }.maxBy { (inScope(scored.first, it) + oosRecall(scored.second, it)) / 2 }
fun score(scored: Pair<List<Pair<Boolean, Float>>, List<Pair<String, Float>>>, t: Double) =
    (inScope(scored.first, t) + oosRecall(scored.second, t)) / 2

val old = validation()
val oldScore = score(old, bestThreshold(old))
val keptVersion = versions("clinc").first { it.current }.number

fineTune("clinc", source("clinc-oos-train.csv"))
calibrate("clinc", source("clinc-val.csv"))

val new = validation()
val t = bestThreshold(new)
val newScore = score(new, t)
log("validation, mean of in-scope accuracy and out-of-scope recall: threshold only %.3f, with the oos intent %.3f".format(oldScore, newScore))

if (newScore <= oldScore) {
    rollback("clinc", keptVersion)
    log("kept the threshold-only model")
    output("rolled back")
} else quietly { sessionMod("clinc") {
    fun best(text: String) = scores(text).entries.first().let { it.key to it.value }
    val testIn = rows("clinc-test.csv").map { (text, label) -> best(text).let { (g, s) -> (g == label) to s } }
    val testOos = rows("clinc-test-oos.csv").map { (text, _) -> best(text) }
    log("Test with the oos intent, threshold %.2f: in-scope accuracy %.3f, out-of-scope recall %.3f"
        .format(t, inScope(testIn, t), oosRecall(testOos, t)))
    output("kept $t ${inScope(testIn, t)} ${oosRecall(testOos, t)}")
} }
