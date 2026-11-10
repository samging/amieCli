environment(SettingsEnv("clinc-oos"))

// The project ran clinc-data, clinc-train and clinc-errors; these three templates are called here like functions,
// each answering one line with what it found
val threshold by template("clinc-threshold")
val oosClass by template("clinc-oos-class")
val ask by template("clinc-serve")

val (t, noThreshold, inScope, oosRecall) = threshold("measure").split(" ").map { it.toDouble() }
val taught = oosClass("teach").split(" ")
val kept = taught.first() == "kept"
val keptThreshold = if (kept) taught[1].toDouble() else t

// Served with the threshold of the model kept: under it the answer is "oos"; serve() saves both with the model
serve("clinc", port = 8765, minScore = keptThreshold, fallbackLabel = "oos")
val url = ask("ask")
stopServing("clinc")

fun pct(x: Double) = "%.1f%%".format(x * 100)
log("CLINC150 test: 4500 in-scope and 1000 out-of-scope queries")
log("  %-36s %-10s %-10s %s".format("model", "threshold", "in-scope", "out-of-scope recall"))
log("  %-36s %-10s %-10s %s".format("150 intents, no threshold", "-", pct(noThreshold), "0.0%"))
log("  %-36s %-10.2f %-10s %s".format("150 intents + threshold", t, pct(inScope), pct(oosRecall)))
if (kept) log("  %-36s %-10.2f %-10s %s".format("151 intents (oos taught) + threshold", keptThreshold, pct(taught[2].toDouble()), pct(taught[3].toDouble())))
log("The model kept: " + if (kept) "151 intents, out of scope taught from 100 examples" else "150 intents with the threshold")
log("Served at $url while it was asked; serve(\"clinc\") serves it again with the same threshold")
