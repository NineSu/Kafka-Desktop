package com.kdt.storage

import com.kdt.kafka.ConsumedMessage
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MessageRepositoryEvictTest {

    private fun msg(partition: Int, offset: Long, ts: Long) =
        ConsumedMessage(partition, offset, ts, "k$offset".toByteArray(), "v$offset".toByteArray(), emptyMap())

    @Test
    fun `evictOldest keeps the newest N by timestamp and deletes the rest`() {
        MessageRepository(":memory:").use { repo ->
            // 10 messages, offsets 0..9 with strictly increasing timestamps
            val batch = (0L..9L).map { msg(partition = 0, offset = it, ts = 1000 + it) }
            repo.insertBatch("c", "t", batch)
            repo.count("c", "t") shouldBe 10L

            val deleted = repo.evictOldest("c", "t", keepNewest = 3)
            deleted shouldBe 7
            repo.count("c", "t") shouldBe 3L

            // The survivors must be the 3 newest (offsets 7,8,9)
            val remaining = repo.query("c", "t", null, 0, 100).map { it.offset }.toSet()
            remaining shouldBe setOf(7L, 8L, 9L)
        }
    }

    @Test
    fun `evictOldest is a no-op when within cap`() {
        MessageRepository(":memory:").use { repo ->
            repo.insertBatch("c", "t", (0L..2L).map { msg(0, it, 1000 + it) })
            repo.evictOldest("c", "t", keepNewest = 10) shouldBe 0
            repo.count("c", "t") shouldBe 3L
        }
    }

    @Test
    fun `evictOldest is scoped to one topic`() {
        MessageRepository(":memory:").use { repo ->
            repo.insertBatch("c", "t1", (0L..4L).map { msg(0, it, 1000 + it) })
            repo.insertBatch("c", "t2", (0L..4L).map { msg(0, it, 1000 + it) })
            repo.evictOldest("c", "t1", keepNewest = 2)
            repo.count("c", "t1") shouldBe 2L
            repo.count("c", "t2") shouldBe 5L // untouched
        }
    }
}
