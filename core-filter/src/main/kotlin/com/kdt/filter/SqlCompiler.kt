package com.kdt.filter

/**
 * Compiles a [FilterNode] tree into a DuckDB WHERE clause + ordered parameter list.
 *
 * Schema contract (must match [com.kdt.storage.MessageRepository] schema):
 *   - key_str TEXT, value_str TEXT, value_json JSON, headers JSON
 *   - partition INTEGER, offset BIGINT, ts TIMESTAMP
 */
class SqlCompiler {

    data class Compiled(val whereClause: String, val params: List<Any?>) {
        fun isEmpty() = whereClause.isBlank()
    }

    fun compile(node: FilterNode?): Compiled {
        if (node == null) return Compiled("", emptyList())
        val params = mutableListOf<Any?>()
        val sql = render(node, params)
        return Compiled(sql, params)
    }

    private fun render(node: FilterNode, params: MutableList<Any?>): String = when (node) {
        is Group -> renderGroup(node, params)
        is Condition -> renderCondition(node, params)
    }

    private fun renderGroup(g: Group, params: MutableList<Any?>): String {
        if (g.children.isEmpty()) return "1=1"
        return when (g.logic) {
            Logic.AND, Logic.OR -> {
                val joiner = if (g.logic == Logic.AND) " AND " else " OR "
                g.children.joinToString(joiner, prefix = "(", postfix = ")") { render(it, params) }
            }
            Logic.NOT -> {
                // NOT(a, b, c) == NOT (a OR b OR c)
                if (g.children.size == 1) {
                    "NOT (${render(g.children.first(), params)})"
                } else {
                    val inner = g.children.joinToString(" OR ", prefix = "(", postfix = ")") { render(it, params) }
                    "NOT $inner"
                }
            }
        }
    }

    private fun renderCondition(c: Condition, params: MutableList<Any?>): String {
        val column = columnFor(c.field)
        return when (c.operator) {
            Operator.IS_NULL -> "$column IS NULL"
            Operator.IS_NOT_NULL -> "$column IS NOT NULL"
            Operator.EQ -> emitBinary(column, "=", c.value, params)
            Operator.NE -> emitBinary(column, "<>", c.value, params)
            Operator.LT -> emitBinary(column, "<", c.value, params)
            Operator.GT -> emitBinary(column, ">", c.value, params)
            Operator.LE -> emitBinary(column, "<=", c.value, params)
            Operator.GE -> emitBinary(column, ">=", c.value, params)
            Operator.CONTAINS -> {
                params.add("%${strOf(c.value)}%")
                "$column LIKE ?"
            }
            Operator.LIKE -> {
                params.add(strOf(c.value))
                "$column LIKE ?"
            }
            Operator.REGEX -> {
                params.add(strOf(c.value))
                "regexp_matches($column, ?)"
            }
            Operator.BETWEEN -> {
                val r = c.value as Value.Range
                params.add(r.low)
                params.add(r.high)
                "$column BETWEEN ? AND ?"
            }
            Operator.IN -> {
                val items = (c.value as Value.List_).v
                if (items.isEmpty()) "1=0"
                else {
                    items.forEach(params::add)
                    val placeholders = items.joinToString(",") { "?" }
                    "$column IN ($placeholders)"
                }
            }
        }
    }

    private fun emitBinary(column: String, op: String, v: Value, params: MutableList<Any?>): String {
        val (param, cast) = when (v) {
            Value.Null -> return "$column $op NULL"
            is Value.Str -> v.v to ""
            is Value.Num -> v.v to "::DOUBLE"
            is Value.Bool -> v.v to ""
            else -> error("Unsupported value $v for $op")
        }
        params.add(param)
        return "${column}${cast} $op ?${cast}"
    }

    private fun columnFor(field: Field): String = when (field) {
        Field.Key -> "key_str"
        Field.ValueRaw -> "value_str"
        is Field.Header -> "json_extract_string(headers, '$.${escape(field.name)}')"
        Field.Partition -> "partition"
        Field.Offset -> "\"offset\""
        Field.Timestamp -> "ts"
        is Field.JsonPath -> "json_extract_string(value_json, '${jsonPathExpr(field.path)}')"
    }

    private fun jsonPathExpr(path: String): String {
        // Caller passes "$.foo.bar"; DuckDB json_extract uses the same syntax. Escape single quotes.
        return escape(path).let { if (it.startsWith("$")) it else "$.${it.removePrefix(".")}" }
    }

    private fun escape(raw: String): String = raw.replace("'", "''")

    private fun strOf(v: Value): String = when (v) {
        is Value.Str -> v.v
        is Value.Num -> v.v.toString()
        is Value.Bool -> v.v.toString()
        else -> ""
    }
}
