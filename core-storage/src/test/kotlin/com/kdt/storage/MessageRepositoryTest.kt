package com.kdt.storage

import com.kdt.filter.Condition
import com.kdt.filter.Field
import com.kdt.filter.Operator
import com.kdt.filter.Value
import com.kdt.filter.and
import com.kdt.kafka.ConsumedMessage
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MessageRepositoryTest {

    private lateinit var repo: MessageRepository

    @BeforeEach
    fun setup() {
        repo = MessageRepository()
    }

    @AfterEach
    fun teardown() {
        repo.close()
    }

    private fun msg(
        partition: Int = 0,
        offset: Long,
        ts: Long = 1_700_000_000_000L,
        key: String? = null,
        value: String? = null,
    ) = ConsumedMessage(
        partition = partition,
        offset = offset,
        timestampMs = ts,
        key = key?.toByteArray(),
        value = value?.toByteArray(),
        headers = emptyMap(),
    )

    @Test
    fun `insertBatch then query returns rows in offset order`() {
        repo.insertBatch(
            "c1", "orders", listOf(
                msg(offset = 1, key = "k1", value = "v1"),
                msg(offset = 2, key = "k2", value = "v2"),
            )
        )
        val rows = repo.query("c1", "orders")
        rows.shouldHaveSize(2)
        rows[0].key shouldBe "k1"
        rows[1].key shouldBe "k2"
    }

    @Test
    fun `count returns total without filter`() {
        repo.insertBatch("c1", "orders", (1..5).map { msg(offset = it.toLong(), key = "k$it") })
        repo.count("c1", "orders") shouldBe 5L
    }

    @Test
    fun `filter LIKE on key prunes results`() {
        repo.insertBatch(
            "c1", "orders", listOf(
                msg(offset = 1, key = "order_1"),
                msg(offset = 2, key = "order_2"),
                msg(offset = 3, key = "event_1"),
            )
        )
        val rows = repo.query(
            "c1", "orders",
            filter = Condition(Field.Key, Operator.LIKE, Value.Str("order_%"))
        )
        rows.map { it.key } shouldContainExactly listOf("order_1", "order_2")
    }

    @Test
    fun `filter on JSON path extracts nested fields`() {
        repo.insertBatch(
            "c1", "orders", listOf(
                msg(offset = 1, value = """{"id":1,"status":"OK"}"""),
                msg(offset = 2, value = """{"id":2,"status":"FAILED"}"""),
                msg(offset = 3, value = """{"id":3,"status":"FAILED"}"""),
            )
        )
        val rows = repo.query(
            "c1", "orders",
            filter = Condition(Field.JsonPath("$.status"), Operator.EQ, Value.Str("FAILED"))
        )
        rows.map { it.offset } shouldContainExactly listOf(2L, 3L)
    }

    @Test
    fun `nested AND filter on key + JSON path`() {
        repo.insertBatch(
            "c1", "orders", listOf(
                msg(offset = 1, key = "order_1", value = """{"status":"OK"}"""),
                msg(offset = 2, key = "order_2", value = """{"status":"FAILED"}"""),
                msg(offset = 3, key = "event_1", value = """{"status":"FAILED"}"""),
            )
        )
        val rows = repo.query(
            "c1", "orders",
            filter = and(
                Condition(Field.Key, Operator.LIKE, Value.Str("order_%")),
                Condition(Field.JsonPath("$.status"), Operator.EQ, Value.Str("FAILED")),
            )
        )
        rows.shouldHaveSize(1)
        rows[0].key shouldBe "order_2"
    }

    @Test
    fun `pagination via limit and offset`() {
        repo.insertBatch("c1", "orders", (1..10).map { msg(offset = it.toLong(), key = "k$it") })
        val page1 = repo.query("c1", "orders", limit = 3, offsetPage = 0)
        val page2 = repo.query("c1", "orders", limit = 3, offsetPage = 3)
        page1.map { it.key } shouldContainExactly listOf("k1", "k2", "k3")
        page2.map { it.key } shouldContainExactly listOf("k4", "k5", "k6")
    }

    @Test
    fun `clear removes all rows for a topic`() {
        repo.insertBatch("c1", "orders", (1..3).map { msg(offset = it.toLong(), key = "k$it") })
        repo.insertBatch("c1", "events", listOf(msg(offset = 1, key = "ek1")))
        repo.clear("c1", "orders")
        repo.count("c1", "orders") shouldBe 0L
        repo.count("c1", "events") shouldBe 1L
    }

    @Test
    fun `duplicate insertBatch is ignored on PK collision`() {
        val batch = listOf(msg(offset = 1, key = "k1"))
        repo.insertBatch("c1", "orders", batch)
        repo.insertBatch("c1", "orders", batch)
        repo.count("c1", "orders") shouldBe 1L
    }
}
