package com.kdt.filter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.kdt.kafka.ConsumedMessage

/**
 * Evaluates a [FilterNode] against a single [ConsumedMessage].
 * Used for live (streaming) filter previews and for offline evaluation in tests.
 *
 * For large-volume filtering, prefer the SQL compiler path so DuckDB can index/scan.
 */
class InMemoryEvaluator(
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    fun matches(message: ConsumedMessage, node: FilterNode?): Boolean {
        if (node == null) return true
        return eval(message, node)
    }

    private fun eval(msg: ConsumedMessage, node: FilterNode): Boolean = when (node) {
        is Group -> when (node.logic) {
            Logic.AND -> node.children.all { eval(msg, it) }
            Logic.OR -> node.children.any { eval(msg, it) }
            Logic.NOT -> node.children.none { eval(msg, it) }
        }
        is Condition -> evalCondition(msg, node)
    }

    private fun evalCondition(msg: ConsumedMessage, c: Condition): Boolean {
        val left: Any? = extract(msg, c.field)
        return when (c.operator) {
            Operator.IS_NULL -> left == null
            Operator.IS_NOT_NULL -> left != null
            Operator.EQ -> compareEq(left, c.value)
            Operator.NE -> !compareEq(left, c.value)
            Operator.LT -> compareNum(left, c.value) { a, b -> a < b }
            Operator.GT -> compareNum(left, c.value) { a, b -> a > b }
            Operator.LE -> compareNum(left, c.value) { a, b -> a <= b }
            Operator.GE -> compareNum(left, c.value) { a, b -> a >= b }
            Operator.CONTAINS -> (left as? String)?.contains(strOf(c.value)) == true
            Operator.LIKE -> left is String && likeMatches(left, strOf(c.value))
            Operator.REGEX -> left is String && Regex(strOf(c.value)).containsMatchIn(left)
            Operator.BETWEEN -> {
                val (lo, hi) = (c.value as Value.Range).let { it.low to it.high }
                val n = toDouble(left) ?: return false
                n in lo..hi
            }
            Operator.IN -> {
                val items = (c.value as Value.List_).v
                left?.toString() in items
            }
        }
    }

    private fun extract(msg: ConsumedMessage, field: Field): Any? = when (field) {
        Field.Key -> msg.keyAsString()
        Field.ValueRaw -> msg.valueAsString()
        is Field.Header -> msg.headers[field.name]?.toString(Charsets.UTF_8)
        Field.Partition -> msg.partition
        Field.Offset -> msg.offset
        Field.Timestamp -> msg.timestampMs
        is Field.JsonPath -> jsonPath(msg.valueAsString(), field.path)
    }

    private fun jsonPath(json: String?, path: String): Any? {
        if (json.isNullOrEmpty()) return null
        return try {
            val root = mapper.readTree(json)
            navigate(root, path)?.let { nodeToValue(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun navigate(root: JsonNode, path: String): JsonNode? {
        var node: JsonNode = root
        val parts = path.removePrefix("$").trim('.').split(".").filter { it.isNotEmpty() }
        for (p in parts) {
            val arrayMatch = Regex("(\\w+)\\[(\\d+)\\]").matchEntire(p)
            node = if (arrayMatch != null) {
                val (name, idx) = arrayMatch.destructured
                val container = if (name.isEmpty()) node else node.get(name) ?: return null
                container.get(idx.toInt()) ?: return null
            } else {
                node.get(p) ?: return null
            }
        }
        return node
    }

    private fun nodeToValue(node: JsonNode): Any? = when {
        node.isNull -> null
        node.isBoolean -> node.asBoolean()
        node.isIntegralNumber -> node.asLong()
        node.isFloatingPointNumber -> node.asDouble()
        node.isTextual -> node.asText()
        else -> node.toString()
    }

    private fun compareEq(left: Any?, v: Value): Boolean = when (v) {
        Value.Null -> left == null
        is Value.Str -> left?.toString() == v.v
        is Value.Num -> toDouble(left) == v.v
        is Value.Bool -> (left as? Boolean) == v.v
        else -> false
    }

    private fun compareNum(left: Any?, v: Value, op: (Double, Double) -> Boolean): Boolean {
        val a = toDouble(left) ?: return false
        val b = (v as? Value.Num)?.v ?: strOf(v).toDoubleOrNull() ?: return false
        return op(a, b)
    }

    private fun toDouble(any: Any?): Double? = when (any) {
        null -> null
        is Number -> any.toDouble()
        is String -> any.toDoubleOrNull()
        is Boolean -> if (any) 1.0 else 0.0
        else -> null
    }

    private fun strOf(v: Value): String = when (v) {
        is Value.Str -> v.v
        is Value.Num -> v.v.toString()
        is Value.Bool -> v.v.toString()
        else -> ""
    }

    private fun likeMatches(left: String, pattern: String): Boolean {
        // Translate SQL LIKE wildcards to a regex
        val sb = StringBuilder("^")
        for (ch in pattern) when (ch) {
            '%' -> sb.append(".*")
            '_' -> sb.append('.')
            in setOf('.', '\\', '+', '*', '?', '(', ')', '[', ']', '{', '}', '|', '^', '$') -> sb.append('\\').append(ch)
            else -> sb.append(ch)
        }
        sb.append('$')
        return Regex(sb.toString()).matches(left)
    }
}
