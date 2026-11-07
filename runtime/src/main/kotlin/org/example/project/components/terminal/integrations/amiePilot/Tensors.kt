package org.example.project.components.terminal.integrations.amiePilot

/**
 * One tensor a model takes or gives, as far as the model says: [shape] has -1 for a size it leaves open and is null
 * when the model doesn't say (a TorchScript model before its first run), [type] is `float32`, `int64` and so on, null
 * when unknown.
 */
data class TensorSpec(val name: String, val shape: List<Long>?, val type: String?) {
    override fun toString(): String = "$name ${type ?: "?"} ${shape?.joinToString(", ", "[", "]") ?: "[?]"}"
}

/**
 * A tensor's values with its name, shape and type: what a model's tensor run gives, and what a script may hand one when
 * the shape is not the model's own (`Tensor.of(floatArrayOf(…), 2, 3)`). [data] is a FloatArray, LongArray, IntArray,
 * DoubleArray, ByteArray, BooleanArray or, for a text tensor, an Array of String.
 */
class Tensor(val name: String, val shape: List<Long>, val type: String, val data: Any) {
    /** How many values it has */
    val size: Int get() = when (data) {
        is FloatArray -> data.size
        is LongArray -> data.size
        is IntArray -> data.size
        is DoubleArray -> data.size
        is ByteArray -> data.size
        is BooleanArray -> data.size
        is Array<*> -> data.size
        else -> 0
    }

    /** Its values as floats, whatever their type; a text tensor has none */
    fun floats(): FloatArray = when (data) {
        is FloatArray -> data
        is LongArray -> FloatArray(data.size) { data[it].toFloat() }
        is IntArray -> FloatArray(data.size) { data[it].toFloat() }
        is DoubleArray -> FloatArray(data.size) { data[it].toFloat() }
        is ByteArray -> FloatArray(data.size) { data[it].toFloat() }
        is BooleanArray -> FloatArray(data.size) { if (data[it]) 1f else 0f }
        else -> throw IllegalStateException("$name is a $type tensor, which has no numbers")
    }

    /** Its values as longs, a float cut to its whole part */
    fun longs(): LongArray = when (data) {
        is LongArray -> data
        is IntArray -> LongArray(data.size) { data[it].toLong() }
        is ByteArray -> LongArray(data.size) { data[it].toLong() }
        else -> floats().let { f -> LongArray(f.size) { f[it].toLong() } }
    }

    /** `logits float32 [1, 2]: 0.12, 0.88`, the first ten values */
    override fun toString(): String {
        val values = when (data) {
            is Array<*> -> data.take(10).joinToString()
            else -> runCatching { floats().take(10).joinToString() }.getOrDefault("")
        }
        return "$name $type ${shape.joinToString(", ", "[", "]")}: $values${if (size > 10) " …" else ""}"
    }

    companion object {
        /** A tensor of [values] in [shape]; no shape is one row of them */
        fun of(values: FloatArray, vararg shape: Long): Tensor = Tensor("", shapeOr(shape, values.size), "float32", values)
        fun of(values: LongArray, vararg shape: Long): Tensor = Tensor("", shapeOr(shape, values.size), "int64", values)
        fun of(values: IntArray, vararg shape: Long): Tensor = Tensor("", shapeOr(shape, values.size), "int32", values)
        fun of(values: DoubleArray, vararg shape: Long): Tensor = Tensor("", shapeOr(shape, values.size), "float64", values)

        private fun shapeOr(shape: LongArray, size: Int): List<Long> = if (shape.isEmpty()) listOf(1L, size.toLong()) else shape.toList()

        /**
         * The shape [count] values take in a tensor the model declares as [declared]: the sizes it fixes are kept, and
         * of those it leaves open (-1) the last takes what the values need, the others 1 (a batch of one). Unknown, the
         * values are one row. Refused when they can't fill it.
         */
        fun shapeFor(declared: List<Long>?, count: Int, what: String = "the input"): List<Long> {
            if (declared == null) return listOf(1L, count.toLong())
            if (declared.isEmpty()) {
                require(count == 1) { "$what is a single value, not $count" }
                return declared
            }
            val fixed = declared.filter { it > 0 }.fold(1L) { a, b -> a * b }
            val open = declared.indices.filter { declared[it] <= 0 }
            if (open.isEmpty()) {
                require(fixed == count.toLong()) { "$what takes ${declared.joinToString(", ", "[", "]")}, which is $fixed values, not $count" }
                return declared
            }
            require(fixed > 0 && count % fixed == 0L) { "$what takes ${declared.joinToString(", ", "[", "]")}: $count values don't fill it" }
            val last = open.last()
            return declared.mapIndexed { i, d -> if (d > 0) d else if (i == last) count / fixed else 1L }
        }
    }
}
