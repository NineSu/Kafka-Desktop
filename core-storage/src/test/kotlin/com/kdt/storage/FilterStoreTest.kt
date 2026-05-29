package com.kdt.storage

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files

class FilterStoreTest {

    private fun newStore(): FilterStore {
        val path = Files.createTempFile("filters", ".json")
        Files.delete(path) // store must handle a missing file
        return FilterStore(path)
    }

    private fun filter(name: String, topic: String?) = SavedFilter(
        name = name,
        topic = topic,
        logic = "AND",
        conditions = listOf(SavedCondition("JSON_PATH", "$.status", "EQ", "FAILED")),
    )

    @Test
    fun `save then list round-trips including topic and conditions`() {
        val store = newStore()
        store.save(filter("failed", null))
        val loaded = store.list()
        loaded shouldHaveSize 1
        loaded[0].name shouldBe "failed"
        loaded[0].topic shouldBe null
        loaded[0].conditions[0].aux shouldBe "$.status"
        loaded[0].conditions[0].value shouldBe "FAILED"
    }

    @Test
    fun `save with same name upserts`() {
        val store = newStore()
        store.save(filter("f", null))
        store.save(filter("f", "topicA"))
        val loaded = store.list()
        loaded shouldHaveSize 1
        loaded[0].topic shouldBe "topicA"
    }

    @Test
    fun `listFor returns globals plus the matching topic only`() {
        val store = newStore()
        store.save(filter("global", null))
        store.save(filter("forA", "topicA"))
        store.save(filter("forB", "topicB"))

        store.listFor("topicA").map { it.name }.toSet() shouldBe setOf("global", "forA")
        store.listFor(null).map { it.name }.toSet() shouldBe setOf("global")
    }

    @Test
    fun `delete removes by name`() {
        val store = newStore()
        store.save(filter("f", null))
        store.delete("f")
        store.list() shouldHaveSize 0
    }
}
