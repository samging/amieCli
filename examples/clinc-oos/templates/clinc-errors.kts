// What the model gets wrong on validation: the surest mistakes, and the intents it mixes up most
fun rows(file: String) = sourceFile(file).second.values.map { it[0] to it[1] }

mistakes("clinc")

quietly { sessionMod("clinc") {
    val wrong = rows("clinc-val.csv").mapNotNull { (text, label) -> predict(text).takeIf { it != label }?.let { label to it } }
    log("${wrong.size} of 3000 validation queries wrong. The pairs mixed up most, the right intent first:")
    wrong.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(10).forEach { (pair, n) ->
        log("  %-28s -> %-28s %d".format(pair.first, pair.second, n))
    }
} }
