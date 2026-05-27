package com.kdt.filter

/**
 * AST for the visual filter builder. UI edits this tree directly; the SQL compiler
 * (see [SqlCompiler]) and the in-memory evaluator (see [InMemoryEvaluator]) both consume it.
 */
sealed class FilterNode

/** Combines child nodes with AND/OR/NOT. */
data class Group(
    val logic: Logic,
    val children: List<FilterNode>,
) : FilterNode()

/** A single predicate against a message field. */
data class Condition(
    val field: Field,
    val operator: Operator,
    val value: Value,
) : FilterNode()

enum class Logic { AND, OR, NOT }

/** Field selector. JSON_PATH carries a path string in [Field.jsonPath]. */
sealed class Field {
    data object Key : Field()
    data object ValueRaw : Field()
    data class Header(val name: String) : Field()
    data object Partition : Field()
    data object Offset : Field()
    data object Timestamp : Field()
    /** JSON Path applied to the message value (must be parseable JSON). */
    data class JsonPath(val path: String) : Field()
}

enum class Operator {
    EQ, NE, LT, GT, LE, GE,
    CONTAINS, LIKE, REGEX,
    BETWEEN, IN,
    IS_NULL, IS_NOT_NULL,
}

/** Tagged-union value to avoid Any?-typed plumbing. */
sealed class Value {
    data class Str(val v: String) : Value()
    data class Num(val v: Double) : Value()
    data class Bool(val v: Boolean) : Value()
    data class Range(val low: Double, val high: Double) : Value()
    data class List_(val v: List<String>) : Value()
    data object Null : Value()
}

/** Convenience builders for tests and UI. */
fun and(vararg nodes: FilterNode) = Group(Logic.AND, nodes.toList())
fun or(vararg nodes: FilterNode) = Group(Logic.OR, nodes.toList())
fun not(node: FilterNode) = Group(Logic.NOT, listOf(node))
