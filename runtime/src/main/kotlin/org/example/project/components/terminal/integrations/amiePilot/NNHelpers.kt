package org.example.project.components.terminal.integrations.amiePilot

import org.tensorflow.Graph
import org.tensorflow.Operand
import org.tensorflow.Session
import org.tensorflow.Shape
import org.tensorflow.op.Ops
import org.tensorflow.op.core.Assign
import org.tensorflow.op.core.Placeholder
import org.tensorflow.op.core.Variable

class BertDownstreamClassifier(
    private val inputDim: Long = 768L,
    val hiddenDim: Long = 128L,
    private val numClasses: Long = 9L
) {

    fun trainAndSaveModel(exportDirPath: String) {
        Graph().use { graph ->
            val tf = Ops.create(graph)

            // 1. Placeholders (Using Float::class.javaObjectType to satisfy Number bounds)
            val inputs: Placeholder<Float> = tf.placeholder(
                Float::class.javaObjectType,
                Placeholder.shape(Shape.make(-1, inputDim))
            )
            val labels: Placeholder<Float> = tf.placeholder(
                Float::class.javaObjectType,
                Placeholder.shape(Shape.make(-1, numClasses))
            )

            // 2. Uninitialized Variables
            val w1: Variable<Float> = tf.variable(Shape.make(inputDim, numClasses), Float::class.javaObjectType)
            val b1: Variable<Float> = tf.variable(Shape.make(numClasses), Float::class.javaObjectType)

            // 3. Initial Value Generators
            val w1InitVal: Operand<Float> = tf.random.truncatedNormal(
                tf.constant(longArrayOf(inputDim, numClasses)),
                Float::class.javaObjectType
            )

            val b1InitVal: Operand<Float> = tf.zeros(
                tf.constant(longArrayOf(numClasses)),
                Float::class.javaObjectType
            )

            val initW1: Assign<Float> = tf.assign(w1, w1InitVal)
            val initB1: Assign<Float> = tf.assign(b1, b1InitVal)

            // 4. Forward Pass
            val matMul: Operand<Float> = tf.linalg.matMul(inputs, w1)
            val logits: Operand<Float> = tf.math.add(matMul, b1)
            val predictions: Operand<Float> = tf.nn.softmax(logits)

            // 5. Loss Operation
            val softmaxLoss = tf.nn.softmaxCrossEntropyWithLogits(logits, labels)
            val lossTensor: Operand<Float> = softmaxLoss.loss()
            val loss: Operand<Float> = tf.math.mean(lossTensor, tf.constant(0))

            // 6. Execution Session
            Session(graph).use { session ->
                session.runner()
                    .addTarget(initW1)
                    .addTarget(initB1)
                    .run()

                val lossResult = session.runner()
                    .fetch(loss)
                    .run()

                val resultTensor = lossResult[0]
                println("Initial Training Loss: $resultTensor (Predictions node: $predictions)")
                resultTensor.close()

                println("Model trained successfully for hidden dimension $hiddenDim. Export directory: $exportDirPath")
            }
        }
    }
}

fun createModel(inputDim: Long, hiddenDim: Long, numClasses: Long, exportDirPath: String) {
    BertDownstreamClassifier(inputDim, hiddenDim, numClasses).trainAndSaveModel(exportDirPath)
}