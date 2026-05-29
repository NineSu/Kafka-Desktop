package com.kdt.kafka

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ConsumerGroupAdminTest {

    @Test
    fun `isResetSafe only for empty or dead groups`() {
        fun info(state: String) = ConsumerGroupInfo("g", state, members = 0, lags = emptyList())
        info("EMPTY").isResetSafe shouldBe true
        info("DEAD").isResetSafe shouldBe true
        info("STABLE").isResetSafe shouldBe false
        info("PREPARING_REBALANCE").isResetSafe shouldBe false
        info("COMPLETING_REBALANCE").isResetSafe shouldBe false
    }

    @Test
    fun `computeLag is the gap, floored at zero`() {
        PartitionLag.computeLag(committed = 90, logEnd = 100) shouldBe 10
        PartitionLag.computeLag(committed = 100, logEnd = 100) shouldBe 0
        // committed past log end (e.g. after truncation) must not report negative lag
        PartitionLag.computeLag(committed = 120, logEnd = 100) shouldBe 0
    }

    @Test
    fun `computeLag counts the whole log when there is no committed offset`() {
        PartitionLag.computeLag(committed = -1, logEnd = 100) shouldBe 100
    }
}
