package org.example.project.components.terminal.window.controller.jetbrainDLBuilder

/**
 * What modelBuilder does to the data before anything is embedded: it cleans what can be cleaned, drops what
 * cannot be learned from (with a reason per row), measures the variance of the set, and refuses to train when
 * the set cannot give a usable classifier. Every decision is a sentence a person can read, because a build that
 * stops here has to say why.
 */
object Preprocessor {
    const val MIN_LABELS = 2
    const val MIN_SAMPLES_PER_LABEL = 5
    const val MIN_DISTINCT_TEXTS_PER_LABEL = 3
    const val MAX_LABEL_IMBALANCE = 10.0
    const val MAX_TEXT_CHARS = 1000
    const val MAX_LABEL_CHARS = 60

    /** One row that was left out, and why */
    data class Dropped(val text: String, val label: String, val reason: String)

    data class Result(
        /** The rows that go on to the encoder */
        val samples: List<LabeledText>,
        val dropped: List<Dropped>,
        /** What was changed or found, one line each (kept with the model's metrics) */
        val notes: List<String>,
        /** Soft problems: training goes on, the lines are stored with the metrics */
        val warnings: List<String>,
        /** Hard problems: training does not start */
        val errors: List<String>,
    ) {
        val ok: Boolean get() = errors.isEmpty()

        /** The report as console lines: the summary first, then what was dropped, then the warnings and errors */
        /** What the check found; with [examples] off, dropped rows are counted without quoting their text */
        fun lines(examples: Boolean = true): List<String> = notes + dropped.groupBy { it.reason }.map { (reason, rows) ->
            "dropped ${rows.size} ${if (rows.size == 1) "row" else "rows"}: $reason" +
                if (examples) rows.take(3).joinToString("") { " · \"${it.text.take(40)}\" (${it.label})" } else ""
        } + warnings.map { "warning: $it" } + errors.map { "error: $it" }
    }

    private val whitespace = Regex("\\s+")
    private val control = Regex("[\\p{Cntrl}&&[^\\n\\t]]")

    /**
     * What a set has to have to be trained on. The defaults are what a build asks; a fine-tune adds rows to a head
     * that knows its labels already, so one label, or a single correction, is enough there
     */
    data class Rules(
        val minLabels: Int = MIN_LABELS,
        val minSamplesPerLabel: Int = MIN_SAMPLES_PER_LABEL,
        val minDistinctTextsPerLabel: Int = MIN_DISTINCT_TEXTS_PER_LABEL,
        /** Refuse a set whose labels look like texts (a label per row); off when the labels are known to be the model's */
        val checkSwapped: Boolean = true,
    )

    /**
     * The check of [raw] under [rules]. With [keepTexts] off (a model that answers other people) no line names a
     * label that looks like a text, one longer than [MAX_LABEL_CHARS]: with the columns swapped it is a row's text
     */
    fun run(raw: List<LabeledText>, rules: Rules = Rules(), keepTexts: Boolean = true): Result {
        val notes = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val dropped = mutableListOf<Dropped>()
        // A label as the lines name it: a long one by its length alone when the model keeps no texts
        fun shown(label: String) = if (!keepTexts && label.length > MAX_LABEL_CHARS) "a label of ${label.length} characters" else label

        if (raw.isEmpty()) {
            errors += "no labeled rows at all: the data must have a text column and a label column (return rows of [text, label], List<Pair<text, label>> or Map<label, texts>)"
            return Result(emptyList(), dropped, notes, warnings, errors)
        }

        // 1. the text and the label as the encoder and the labels list will see them
        val cleaned = raw.map { LabeledText(it.text.replace(control, " ").replace(whitespace, " ").trim(), it.label.replace(whitespace, " ").trim()) }

        // 2. one spelling per label: "Lights" and "lights" are the same label, the more frequent spelling wins
        val spellings = cleaned.map { it.label }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
        val canonical = spellings.keys.groupBy { it.lowercase() }.mapValues { (_, forms) -> forms.maxByOrNull { spellings.getValue(it) }!! }
        val respelled = canonical.values.toSet().let { kept -> spellings.keys.filter { it !in kept }.map { other -> "label '${shown(other)}' read as '${shown(canonical.getValue(other.lowercase()))}'" } }
        val relabeled = cleaned.map { if (it.label.isEmpty()) it else it.copy(label = canonical.getValue(it.label.lowercase())) }

        // Swapped columns show up as a label per row: say so before the conflict step would drop every row. The
        // labels are texts then, so none is named, not even as two spellings of one
        val distinctLabels = relabeled.map { it.label }.filter { it.isNotEmpty() }.distinct().size
        if (rules.checkSwapped && relabeled.size > 3 && distinctLabels >= relabeled.size * 0.8) {
            errors += "almost every row has its own label ($distinctLabels labels for ${relabeled.size} rows): the text and label columns are probably swapped"
            notes.add(0, "${raw.size} rows in; the labels look like texts")
            return Result(emptyList(), dropped, notes, warnings, errors)
        }
        notes += respelled

        // 3. rows that cannot be learned from
        val usable = relabeled.filter { row ->
            val reason = when {
                row.text.isEmpty() -> "empty text"
                row.label.isEmpty() -> "empty label"
                row.text.none { it.isLetterOrDigit() } -> "no letters or digits in the text"
                row.text.length < 2 -> "a single character"
                else -> null
            }
            if (reason != null) dropped += Dropped(row.text, row.label, reason)
            reason == null
        }

        // 4. exact repeats of a row, and the same text under different labels
        val key = { s: LabeledText -> s.text.lowercase() }
        val byText = usable.groupBy(key)
        val kept = mutableListOf<LabeledText>()
        for ((_, rows) in byText) {
            val labels = rows.groupingBy { it.label }.eachCount()
            if (labels.size == 1) {
                kept += rows.first()
                rows.drop(1).forEach { dropped += Dropped(it.text, it.label, "repeated row") }
                continue
            }
            val top = labels.maxOf { it.value }
            val winners = labels.filterValues { it == top }.keys
            if (winners.size == 1) {
                val winner = winners.first()
                kept += rows.first { it.label == winner }
                rows.filter { it.label != winner }.forEach { dropped += Dropped(it.text, it.label, "same text under another label, kept as '${shown(winner)}'") }
                rows.filter { it.label == winner }.drop(1).forEach { dropped += Dropped(it.text, it.label, "repeated row") }
            } else {
                rows.forEach { dropped += Dropped(it.text, it.label, "same text under ${labels.size} labels with no majority (${labels.keys.joinToString(transform = ::shown)})") }
            }
        }
        // Back in the order they came in; a lookup instead of indexOf, which made this quadratic on big files
        val position = HashMap<LabeledText, Int>(relabeled.size * 2)
        relabeled.forEachIndexed { i, s -> position.putIfAbsent(s, i) }
        val samples = kept.sortedBy { position[it] ?: -1 }.ifEmpty { kept }

        // 5. what is left, and whether a classifier can come out of it
        val counts = samples.groupingBy { it.label }.eachCount()
        notes.add(0, "${raw.size} rows in, ${samples.size} kept" + (if (dropped.isNotEmpty()) ", ${dropped.size} dropped" else "") +
            "; ${counts.size} labels: " + counts.entries.sortedByDescending { it.value }.joinToString { "${shown(it.key)} ${it.value}" })
        if (samples.isEmpty()) {
            errors += "nothing is left after cleaning; every row was empty, repeated or in conflict"
            return Result(samples, dropped, notes, warnings, errors)
        }
        if (counts.size < rules.minLabels) errors += "a classifier needs at least ${rules.minLabels} labels, the data has ${counts.size} (${counts.keys.joinToString(transform = ::shown)}); add rows of another label"
        if (rules.checkSwapped && counts.size == samples.size && samples.size > 3) errors += "every row has its own label: the text and label columns are probably swapped"
        counts.keys.filter { it.length > MAX_LABEL_CHARS }.take(2).forEach {
            errors += (if (keepTexts) "label '${it.take(40)}…' is ${it.length} characters long" else "a label is ${it.length} characters long") +
                ", which looks like a text: the text and label columns are probably swapped"
        }
        counts.filterValues { it < rules.minSamplesPerLabel }.forEach { (label, n) ->
            errors += "label '${shown(label)}' has $n ${if (n == 1) "row" else "rows"} after cleaning, at least ${rules.minSamplesPerLabel} are needed to train and validate it"
        }
        val distinct = samples.groupBy { it.label }.mapValues { (_, rows) -> rows.map { it.text.lowercase().filter { c -> c.isLetterOrDigit() } }.toSet().size }
        distinct.filterValues { it < rules.minDistinctTextsPerLabel }.forEach { (label, n) ->
            if (counts.getValue(label) >= rules.minSamplesPerLabel) errors += "label '${shown(label)}' has only $n distinct ${if (n == 1) "text" else "texts"}; the rest differ only in case or punctuation, so there is nothing to generalise from"
        }
        if (counts.size >= 2) {
            val ratio = counts.values.max().toDouble() / counts.values.min()
            if (ratio > MAX_LABEL_IMBALANCE) warnings += "the labels are imbalanced ${"%.1f".format(java.util.Locale.US, ratio)}x (${shown(counts.entries.maxBy { it.value }.key)} ${counts.values.max()} vs ${shown(counts.entries.minBy { it.value }.key)} ${counts.values.min()}); the small ones will be learned worse"
        }
        val words = samples.map { it.text.split(' ').size }
        val meanWords = words.average()
        if (meanWords < 2.0) warnings += "the texts are very short (${"%.1f".format(java.util.Locale.US, meanWords)} words on average); single words carry little for the encoder"
        if (words.distinct().size == 1 && samples.size > 10) warnings += "every text has exactly ${words.first()} words; real inputs will vary more"
        val long = samples.count { it.text.length > MAX_TEXT_CHARS }
        if (long > 0) warnings += "$long ${if (long == 1) "text is" else "texts are"} longer than $MAX_TEXT_CHARS characters; the encoder reads only the first 512 tokens"
        val nearDuplicates = samples.size - samples.map { it.text.lowercase().filter { c -> c.isLetterOrDigit() } }.toSet().size
        if (nearDuplicates > samples.size / 5) warnings += "$nearDuplicates texts differ from another only in case or punctuation; add more varied examples"
        val lengths = samples.map { it.text.length.toDouble() }
        val lengthVariance = lengths.map { (it - lengths.average()).let { d -> d * d } }.average()
        notes += "texts: ${"%.1f".format(java.util.Locale.US, meanWords)} words on average, length spread ${"%.0f".format(java.util.Locale.US, Math.sqrt(lengthVariance))} characters; ${distinct.values.sum()} distinct texts"

        return Result(samples, dropped, notes, warnings, errors)
    }
}
