package com.kdt.filter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SqlCompilerTest {

    private val compiler = SqlCompiler()

    @Test
    fun `null tree produces empty where`() {
        val r = compiler.compile(null)
        r.whereClause shouldBe ""
        r.params shouldBe emptyList()
    }

    @Test
    fun `EQ on key produces parameterized equality`() {
        val r = compiler.compile(Condition(Field.Key, Operator.EQ, Value.Str("order_1")))
        r.whereClause shouldBe "key_str = ?"
        r.params shouldBe listOf("order_1")
    }

    @Test
    fun `LIKE on key`() {
        val r = compiler.compile(Condition(Field.Key, Operator.LIKE, Value.Str("order_%")))
        r.whereClause shouldBe "key_str LIKE ?"
        r.params shouldBe listOf("order_%")
    }

    @Test
    fun `JSON path EQ uses json_extract_string`() {
        val r = compiler.compile(Condition(Field.JsonPath("$.status"), Operator.EQ, Value.Str("FAILED")))
        r.whereClause shouldBe "json_extract_string(value_json, '$.status') = ?"
        r.params shouldBe listOf("FAILED")
    }

    @Test
    fun `numeric GT casts to DOUBLE`() {
        val r = compiler.compile(Condition(Field.Offset, Operator.GT, Value.Num(100.0)))
        r.whereClause shouldBe "\"offset\"::DOUBLE > ?::DOUBLE"
        r.params shouldBe listOf(100.0)
    }

    @Test
    fun `AND grouping with two predicates`() {
        val r = compiler.compile(
            and(
                Condition(Field.Key, Operator.LIKE, Value.Str("order_%")),
                Condition(Field.JsonPath("$.status"), Operator.EQ, Value.Str("FAILED")),
            )
        )
        r.whereClause shouldBe "(key_str LIKE ? AND json_extract_string(value_json, '$.status') = ?)"
        r.params shouldBe listOf("order_%", "FAILED")
    }

    @Test
    fun `nested AND inside OR preserves parens`() {
        val r = compiler.compile(
            or(
                and(
                    Condition(Field.Key, Operator.LIKE, Value.Str("order_%")),
                    Condition(Field.JsonPath("$.amount"), Operator.GT, Value.Num(100.0)),
                ),
                Condition(Field.JsonPath("$.status"), Operator.EQ, Value.Str("FAILED")),
            )
        )
        r.whereClause shouldBe
            "((key_str LIKE ? AND json_extract_string(value_json, '$.amount')::DOUBLE > ?::DOUBLE) OR json_extract_string(value_json, '$.status') = ?)"
        r.params shouldBe listOf("order_%", 100.0, "FAILED")
    }

    @Test
    fun `NOT wraps single child`() {
        val r = compiler.compile(not(Condition(Field.Key, Operator.EQ, Value.Str("admin"))))
        r.whereClause shouldBe "NOT (key_str = ?)"
        r.params shouldBe listOf("admin")
    }

    @Test
    fun `BETWEEN on offset`() {
        val r = compiler.compile(Condition(Field.Offset, Operator.BETWEEN, Value.Range(10.0, 20.0)))
        r.whereClause shouldBe "\"offset\" BETWEEN ? AND ?"
        r.params shouldBe listOf(10.0, 20.0)
    }

    @Test
    fun `IN on multiple values`() {
        val r = compiler.compile(
            Condition(Field.Key, Operator.IN, Value.List_(listOf("a", "b", "c")))
        )
        r.whereClause shouldBe "key_str IN (?,?,?)"
        r.params shouldBe listOf("a", "b", "c")
    }

    @Test
    fun `empty IN returns always-false`() {
        val r = compiler.compile(Condition(Field.Key, Operator.IN, Value.List_(emptyList())))
        r.whereClause shouldBe "1=0"
    }

    @Test
    fun `IS_NULL on header`() {
        val r = compiler.compile(Condition(Field.Header("trace-id"), Operator.IS_NULL, Value.Null))
        r.whereClause shouldBe "json_extract_string(headers, '$.trace-id') IS NULL"
        r.params shouldBe emptyList()
    }

    @Test
    fun `CONTAINS wraps value in percent`() {
        val r = compiler.compile(Condition(Field.ValueRaw, Operator.CONTAINS, Value.Str("error")))
        r.whereClause shouldBe "value_str LIKE ?"
        r.params shouldBe listOf("%error%")
    }
}
