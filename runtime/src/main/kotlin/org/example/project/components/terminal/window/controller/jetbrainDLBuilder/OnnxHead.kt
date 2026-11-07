package org.example.project.components.terminal.window.controller.jetbrainDLBuilder

import org.example.project.components.terminal.window.controller.modules.DenseExport
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads a head back out of its model.onnx: the dense layers (MatMul, Add, then Relu, Tanh, Sigmoid or Softmax)
 * that `denseStackToOnnx` writes, in the order the graph runs them, so a trained model can be fine-tuned or
 * calibrated without its training data. A graph with any other kind of node is refused with the reason.
 */
object OnnxHead {
    /** The layers of a head and the activation between its hidden layers (null for a linear head) */
    class Head(val layers: List<DenseExport>) {
        val inputDim: Int get() = layers.first().inDim.toInt()
        val outputDim: Int get() = layers.last().outDim.toInt()
        val hidden: List<Int> get() = layers.dropLast(1).map { it.outDim.toInt() }
        val hiddenActivation: String? get() = layers.dropLast(1).firstOrNull()?.activation
        /** What the last layer's output goes through: Softmax for a trained head, Sigmoid for the dummy, null for logits */
        val outputActivation: String? get() = layers.last().activation

        /** The head as a DenseNet with these weights, ready to train on; its scores are softmax ones whatever the file had */
        fun toDenseNet(): DenseNet {
            val activation = hiddenActivation ?: "Relu"
            val net = DenseNet(inputDim, hidden, outputDim, if (activation.equals("Tanh", true)) "Tanh" else "Relu")
            net.loadWeights(layers.flatMap { listOf(it.kernel, it.bias ?: FloatArray(it.outDim.toInt())) })
            return net
        }
    }

    fun read(file: File): Head = read(file.readBytes(), file.name)

    fun read(bytes: ByteArray, name: String = "model.onnx"): Head {
        val model = Message(bytes)
        val graphBytes = model.bytes(7).firstOrNull() ?: throw IllegalArgumentException("$name has no graph")
        val graph = Message(graphBytes)
        val tensors = graph.bytes(5).map(::tensor).associateBy { it.name }
        val layers = ArrayList<Building>()
        val nodes = graph.bytes(1).map(::node)
        for (n in nodes) {
            when (n.op) {
                "MatMul" -> {
                    val w = n.inputs.getOrNull(1)?.let { tensors[it] } ?: throw IllegalArgumentException("$name: MatMul ${n.name} has no weights in the file")
                    require(w.dims.size == 2) { "$name: the weights of ${n.name} are not a matrix (${w.dims})" }
                    layers += Building(w.dims[0], w.dims[1], w.values)
                }
                "Add" -> {
                    val layer = layers.lastOrNull() ?: throw IllegalArgumentException("$name: Add before any MatMul")
                    val b = n.inputs.firstNotNullOfOrNull { tensors[it] } ?: throw IllegalArgumentException("$name: Add ${n.name} has no bias in the file")
                    require(b.values.size.toLong() == layer.outDim) { "$name: the bias of ${n.name} has ${b.values.size} values for ${layer.outDim} outputs" }
                    layer.bias = b.values
                }
                "Relu", "Tanh", "Sigmoid", "Softmax" -> {
                    val layer = layers.lastOrNull() ?: throw IllegalArgumentException("$name: ${n.op} before any MatMul")
                    layer.activation = n.op
                }
                "Identity" -> {}
                else -> throw IllegalArgumentException("$name has a ${n.op} node; only heads of dense layers (MatMul, Add, Relu/Tanh, Softmax) can be read back")
            }
        }
        require(layers.isNotEmpty()) { "$name has no dense layer" }
        layers.zipWithNext().forEach { (a, b) -> require(a.outDim == b.inDim) { "$name: a layer of ${a.outDim} outputs feeds one of ${b.inDim} inputs" } }
        val hiddenActivations = layers.dropLast(1).map { it.activation }.distinct()
        require(hiddenActivations.size <= 1 && hiddenActivations.all { it == "Relu" || it == "Tanh" }) {
            "$name: the hidden layers use ${hiddenActivations.map { it ?: "no activation" }}; a head this app trains has relu or tanh throughout"
        }
        return Head(layers.map { DenseExport(it.kernel, it.bias, it.inDim, it.outDim, it.activation) })
    }

    private class Building(val inDim: Long, val outDim: Long, val kernel: FloatArray) {
        var bias: FloatArray? = null
        var activation: String? = null
    }

    private class Tensor(val name: String, val dims: List<Long>, val values: FloatArray)
    private class Node(val inputs: List<String>, val name: String, val op: String)

    private fun node(bytes: ByteArray): Node {
        val m = Message(bytes)
        return Node(m.strings(1), m.strings(3).firstOrNull() ?: "", m.strings(4).firstOrNull() ?: "")
    }

    private fun tensor(bytes: ByteArray): Tensor {
        val m = Message(bytes)
        val dims = m.longs(1)
        val type = m.longs(2).firstOrNull() ?: 1L
        val name = m.strings(8).firstOrNull() ?: ""
        require(type == 1L) { "tensor $name is not float32 (type $type)" }
        val raw = m.bytes(9).firstOrNull()
        val values = if (raw != null) {
            val fb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            FloatArray(fb.remaining()).also { fb.get(it) }
        } else m.floats(4)
        val expected = dims.fold(1L) { a, d -> a * d }
        require(values.size.toLong() == expected) { "tensor $name holds ${values.size} values for its shape $dims" }
        return Tensor(name, dims, values)
    }

    /**
     * The fields of one protobuf message by number: varints (wire type 0), fixed 64 (1), length-delimited (2) and
     * fixed 32 (5); repeated numbers may come packed in a length-delimited field
     */
    private class Message(bytes: ByteArray) {
        private val varints = HashMap<Int, MutableList<Long>>()
        private val delimited = HashMap<Int, MutableList<ByteArray>>()
        private val fixed32 = HashMap<Int, MutableList<Int>>()

        init {
            var pos = 0
            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    require(pos < bytes.size) { "the file ends inside a number" }
                    val b = bytes[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    require(shift < 64) { "a number is too long" }
                }
            }
            while (pos < bytes.size) {
                val tag = varint()
                val field = (tag ushr 3).toInt()
                when ((tag and 7).toInt()) {
                    0 -> varints.getOrPut(field) { mutableListOf() } += varint()
                    1 -> { require(pos + 8 <= bytes.size) { "the file ends inside a field" }; pos += 8 }
                    2 -> {
                        val length = varint().toInt()
                        require(length >= 0 && pos + length <= bytes.size) { "a field runs past the end of the file" }
                        delimited.getOrPut(field) { mutableListOf() } += bytes.copyOfRange(pos, pos + length)
                        pos += length
                    }
                    5 -> {
                        require(pos + 4 <= bytes.size) { "the file ends inside a field" }
                        fixed32.getOrPut(field) { mutableListOf() } += ByteBuffer.wrap(bytes, pos, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        pos += 4
                    }
                    else -> throw IllegalArgumentException("unsupported protobuf wire type ${tag and 7}")
                }
            }
        }

        fun bytes(field: Int): List<ByteArray> = delimited[field] ?: emptyList()
        fun strings(field: Int): List<String> = bytes(field).map { String(it, Charsets.UTF_8) }

        /** Varints of the field, unpacked from a length-delimited run when they were packed */
        fun longs(field: Int): List<Long> {
            val plain = varints[field] ?: emptyList()
            val packed = delimited[field]?.flatMap { run ->
                val out = ArrayList<Long>()
                var pos = 0
                while (pos < run.size) {
                    var result = 0L
                    var shift = 0
                    while (true) {
                        val b = run[pos++].toInt() and 0xFF
                        result = result or ((b and 0x7F).toLong() shl shift)
                        if (b and 0x80 == 0) break
                        shift += 7
                    }
                    out += result
                }
                out
            } ?: emptyList()
            return plain + packed
        }

        /** Floats of the field, packed or one by one */
        fun floats(field: Int): FloatArray {
            val single = fixed32[field]?.map { java.lang.Float.intBitsToFloat(it) } ?: emptyList()
            val packed = delimited[field]?.flatMap { run ->
                val fb = ByteBuffer.wrap(run).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                FloatArray(fb.remaining()).also { fb.get(it) }.asList()
            } ?: emptyList()
            return (single + packed).toFloatArray()
        }
    }
}
