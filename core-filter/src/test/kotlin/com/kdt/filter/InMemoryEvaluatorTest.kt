package com.kdt.filter

import com.kdt.kafka.ConsumedMessage
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class InMemoryEvaluatorTest {

    private val evaluator = InMemoryEvaluator()

    private fun msg(
        key: String? = null,
        value: String? = null,
        partition: Int = 0,
        offset: Long = 0,
        timestamp: Long = 0,
        headers: Map<String, ByteArray?> = emptyMap(),
    ) = ConsumedMessage(
        partition = partition,
        offset = offset,
        timestampMs = timestamp,
        key = key?.toByteArray(),
        value = value?.toByteArray(),
        headers = headers,
    )

    @Test
    fun `null filter matches everything`() {
        evaluator.matches(msg(key = "any"), null) shouldBe true
    }

    @Test
    fun `EQ on key`() {
        val f = Condition(Field.Key, Operator.EQ, Value.Str("order_1"))
        evaluator.matches(msg(key = "order_1"), f) shouldBe true
        evaluator.matches(msg(key = "order_2"), f) shouldBe false
    }

    @Test
    fun `LIKE wildcard on key`() {
        val f = Condition(Field.Key, Operator.LIKE, Value.Str("order_%"))
        evaluator.matches(msg(key = "order_42"), f) shouldBe true
        evaluator.matches(msg(key = "event_42"), f) shouldBe false
    }

    @Test
    fun `JSON path on value`() {
        val f = Condition(Field.JsonPath("$.status"), Operator.EQ, Value.Str("FAILED"))
        evaluator.matches(msg(value = """{"id":1,"status":"FAILED"}"""), f) shouldBe true
        evaluator.matches(msg(value = """{"id":2,"status":"OK"}"""), f) shouldBe false
    }

    @Test
    fun `JSON path numeric GT`() {
        val f = Condition(Field.JsonPath("$.amount"), Operator.GT, Value.Num(100.0))
        evaluator.matches(msg(value = """{"amount":250}"""), f) shouldBe true
        evaluator.matches(msg(value = """{"amount":50}"""), f) shouldBe false
    }

    @Test
    fun `AND combines two predicates`() {
        val f = and(
            Condition(Field.Key, Operator.LIKE, Value.Str("order_%")),
            Condition(Field.JsonPath("$.status"), Operator.EQ, Value.Str("FAILED")),
        )
        evaluator.matches(msg(key = "order_4", value = """{"status":"FAILED"}"""), f) shouldBe true
        evaluator.matches(msg(key = "order_4", value = """{"status":"OK"}"""), f) shouldBe false
        evaluator.matches(msg(key = "event_4", value = """{"status":"FAILED"}"""), f) shouldBe false
    }

    @Test
    fun `OR matches either branch`() {
        val f = or(
            Condition(Field.JsonPath("$.status"), Operator.EQ, Value.Str("FAILED")),
            Condition(Field.JsonPath("$.amount"), Operator.GT, Value.Num(500.0)),
        )
        evaluator.matches(msg(value = """{"status":"FAILED","amount":50}"""), f) shouldBe true
        evaluator.matches(msg(value = """{"status":"OK","amount":600}"""), f) shouldBe true
        evaluator.matches(msg(value = """{"status":"OK","amount":50}"""), f) shouldBe false
    }

    @Test
    fun `nested AND inside OR`() {
        val f = or(
            and(
                Condition(Field.Key, Operator.LIKE, Value.Str("order_%")),
                Condition(Field.JsonPath("$.amount"), Operator.GT, Value.Num(100.0)),
            ),
            Condition(Field.JsonPath("$.status"), Operator.EQ, Value.Str("FAILED")),
        )
        evaluator.matches(msg(key = "order_1", value = """{"amount":150,"status":"OK"}"""), f) shouldBe true
        evaluator.matches(msg(key = "evt_1", value = """{"amount":150,"status":"FAILED"}"""), f) shouldBe true
        evaluator.matches(msg(key = "evt_1", value = """{"amount":150,"status":"OK"}"""), f) shouldBe false
    }

    @Test
    fun `NOT inverts predicate`() {
        val f = not(Condition(Field.Key, Operator.EQ, Value.Str("admin")))
        evaluator.matches(msg(key = "admin"), f) shouldBe false
        evaluator.matches(msg(key = "user"), f) shouldBe true
    }

    @Test
    fun `BETWEEN on offset`() {
        val f = Condition(Field.Offset, Operator.BETWEEN, Value.Range(10.0, 20.0))
        evaluator.matches(msg(offset = 15), f) shouldBe true
        evaluator.matches(msg(offset = 25), f) shouldBe false
    }

    @Test
    fun `IS_NULL on header`() {
        val f = Condition(Field.Header("trace-id"), Operator.IS_NULL, Value.Null)
        evaluator.matches(msg(), f) shouldBe true
        evaluator.matches(msg(headers = mapOf("trace-id" to "abc".toByteArray())), f) shouldBe false
    }

    @Test
    fun `malformed JSON returns false for json path predicate`() {
        val f = Condition(Field.JsonPath("$.status"), Operator.EQ, Value.Str("OK"))
        evaluator.matches(msg(value = "not-json"), f) shouldBe false
    }
}
