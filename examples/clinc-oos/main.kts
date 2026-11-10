import java.io.File
import java.util.Properties
import org.example.project.getDirectory

environment(SettingsEnv("clinc-oos"))

// The results of the templates before, side by side
val results = File(getDirectory().toString(), "clinc-oos")
fun read(name: String) = File(results, name).takeIf { it.isFile }?.let { f -> Properties().apply { f.reader().use(::load) } }
val threshold = read("threshold.properties") ?: throw IllegalStateException("run the project first: amie run clinc-oos")
val oosClass = read("oos-class.properties")
fun pct(p: Properties, key: String) = "%.1f%%".format(p.getProperty(key).toDouble() * 100)

log("CLINC150 test: 4500 in-scope and 1000 out-of-scope queries")
log("  %-36s %-10s %-10s %s".format("model", "threshold", "in-scope", "out-of-scope recall"))
log("  %-36s %-10s %-10s %s".format("150 intents, no threshold", "-", pct(threshold, "test.inScope.noThreshold"), "0.0%"))
log("  %-36s %-10s %-10s %s".format("150 intents + threshold", threshold.getProperty("threshold"), pct(threshold, "test.inScope"), pct(threshold, "test.oosRecall")))
oosClass?.let { log("  %-36s %-10s %-10s %s".format("151 intents (oos taught) + threshold", it.getProperty("threshold"), pct(it, "test.inScope"), pct(it, "test.oosRecall"))) }
log("The model kept: " + if (oosClass != null) "151 intents, out of scope taught from 100 examples" else "150 intents with the threshold")
