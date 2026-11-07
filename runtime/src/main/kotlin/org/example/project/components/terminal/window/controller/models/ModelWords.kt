package org.example.project.components.terminal.window.controller.models

import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.FineTunePlan
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.LabelQueue
import org.example.project.components.terminal.window.controller.modules.MetricsRun

/*
 * The words a script and the model card share with the Models page: which labels a run mixes up, a queue's rows, a
 * fine-tune's plan. Apart from ModelsDecisions, which reaches the page's widgets, so the script API stays clear of the
 * UI: a bundle runs these with no Compose on its class path.
 */

/** Rows of label [truth] that a run answered [answered]: [count] of them, [kept] among the wrong answers the run lists */
internal data class MixUp(val truth: String, val answered: String, val count: Int, val kept: Int) {
    /** How WHAT WOULD HELP and Show the rows name it */
    val words: String get() = "“$truth” taken for “$answered”"
}

/**
 * The labels [run] mixes up, from its confusion matrix (confusion[truth][answer], indexed like its labels): each pair off
 * the diagonal with two rows or more, the most first (ties in the labels' order), and how many of those rows are among
 * the wrong answers it lists (a run keeps only those it was surest about, 25 by default)
 */
internal fun mixUps(run: MetricsRun?): List<MixUp> {
    if (run == null) return emptyList()
    val labels = run.labels.keys.toList()
    val mistakes = run.analysis?.mistakes.orEmpty()
    return labels.flatMapIndexed { t, truth ->
        labels.mapIndexedNotNull { a, answered ->
            val count = run.confusion.getOrNull(t)?.getOrNull(a) ?: 0
            if (t == a || count < 2) null else MixUp(truth, answered, count, mistakes.count { it.label == truth && it.answered == answered })
        }
    }.sortedByDescending { it.count }
}

/** What a row says the model answers: [right] when it answers the label given (a check before it), else its guess; null when it has none */
internal data class QueueGuess(val right: Boolean, val text: String)

internal fun queueGuess(row: LabelQueue.Row): QueueGuess? {
    val score = row.score.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
    return when {
        row.labeled && row.guess.equals(row.label, ignoreCase = true) -> QueueGuess(true, "Answers it$score")
        row.guess.isNotBlank() -> QueueGuess(false, "Guessed ${row.guess}$score")
        else -> null
    }
}

/**
 * A queue as a script prints it (queue(model)): the counts and the file, then a line per row in the file's order with
 * its label (· while it has none), its text (cut past 60 letters) and what the model answers, as the Improve tab says it
 */
internal fun queueLines(model: String, file: String, rows: List<LabelQueue.Row>): List<String> {
    if (rows.isEmpty()) return listOf("queue '$model' · no texts yet; teach and textsToLabel put them in $file")
    // A text on one line, however many it was written over, so the columns stay put
    fun cut(s: String, n: Int) = s.replace(Regex("""\s+"""), " ").trim().let { if (it.length <= n) it else it.take(n - 1) + "…" }
    val labels = rows.map { cut(it.label.ifBlank { "·" }, 20) }
    val texts = rows.map { cut(it.text, 60) }
    val labelWidth = labels.maxOf { it.length }
    val textWidth = texts.maxOf { it.length }
    return listOf("queue '$model' · ${rows.size} ${if (rows.size == 1) "text" else "texts"}, ${rows.count { it.labeled }} labeled · $file") +
        rows.indices.map { i -> ("  " + labels[i].padEnd(labelWidth) + "  " + texts[i].padEnd(textWidth) + (queueGuess(rows[i])?.let { "  ${it.text}" } ?: "")).trimEnd() }
}

/* ---------- the plan strip ---------- */

/** The plan in one line: the rows, the labels they are and add, what is replayed, what is dropped and what stays */
internal fun planFacts(plan: FineTunePlan): String = listOfNotNull(
    "${plan.rows} ${if (plan.rows == 1) "row" else "rows"}",
    plan.known.takeIf { it.isNotEmpty() }?.let { k -> "of its labels: " + k.entries.joinToString { "${it.key} ${it.value}" } },
    plan.newLabels.takeIf { it.isNotEmpty() }?.let { n -> (if (n.size == 1) "new label " else "new labels ") + n.entries.joinToString { "${it.key} ${it.value}" } },
    if (plan.replayRows > 0) "replays ${plan.replayRows} earlier rows of ${plan.replayedFrom.joinToString()}" else null,
    plan.dropped.values.sum().takeIf { it > 0 }?.let { n -> "drops $n (${plan.dropped.keys.joinToString()})" },
    "v${plan.version} stays, to go back to",
).joinToString("  ·  ")

/** What is worth a second look before it starts: a new label spelled like one it has, rows that relabel a text, nothing to replay */
internal fun planWarnings(plan: FineTunePlan): List<String> = buildList {
    plan.nearMisses.forEach { (new, known) -> add("“$new” would be a new label: if it means “$known”, spell it so") }
    if (plan.relabeled > 0) add("${plan.relabeled} ${if (plan.relabeled == 1) "row gives a text" else "rows give texts"} it learned under another label; the new label wins")
    if (plan.replayRows == 0 && plan.rows > 0) add(
        if (plan.notFound.isNotEmpty()) "${plan.notFound.joinToString()} ${if (plan.notFound.size == 1) "is" else "are"} not in AMP/ any more, so nothing it learned from is replayed: what it knows may fade"
        else "It has no earlier data to replay, so only its weights hold what it knows; validate it afterwards"
    )
}

/** What keeps it from starting */
internal fun planBlocks(plan: FineTunePlan): List<String> =
    plan.problems + plan.tooFew.map { label -> plan.newLabels.getValue(label).let { n -> "“$label” has $n ${if (n == 1) "row" else "rows"}; a new label needs ${plan.minNewLabelRows}" } }

/**
 * The plan as a script prints it (planFineTune), in the strip's words: what it is, its facts (none for no rows), what
 * is worth a second look, what keeps it from starting, then whether it can start. [on] says what it is planned on
 */
internal fun planLines(plan: FineTunePlan, on: String): List<String> = buildList {
    add("planFineTune: fine-tune ${plan.modelName} on $on")
    if (plan.rows > 0) add("  " + planFacts(plan))
    planWarnings(plan).forEach { add("  $it") }
    planBlocks(plan).forEach { add("  can't start: $it") }
    add(if (plan.ok) "  it can start" else "  it would not start")
}
