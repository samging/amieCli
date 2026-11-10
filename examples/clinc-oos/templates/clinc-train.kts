// A head on the frozen encoder: a linear one and one hidden layer, picked by macro F1 on clinc-val.csv
val heads = listOf(
    architecture(hidden = emptyList(), epochs = 60, batchSize = 64, learningRate = 0.01, patience = 8, name = "linear"),
    architecture(hidden = listOf(256), epochs = 60, batchSize = 64, learningRate = 0.005, dropout = 0.2, patience = 8, name = "hidden-256"),
)
modelBuilder("clinc", source("clinc-train.csv"), buildOptions(candidates = heads))

// A temperature fitted on validation, so a score of 0.8 is right about 80% of the time
calibrate("clinc", source("clinc-val.csv"))

val report = validateMetrics("clinc")
log("validation: accuracy %.3f, macro F1 %.3f".format(report.accuracy, report.macroF1))
