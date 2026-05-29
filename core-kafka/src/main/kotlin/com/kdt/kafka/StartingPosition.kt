package com.kdt.kafka

/**
 * Where a consumer should begin reading a topic.
 *
 * [Beginning]/[End] are self-explanatory. [LastN] reads the most recent N records
 * **per partition** (so an 8-partition topic with N=1000 yields up to 8000). [FromTimestamp]
 * seeks each partition to the first record at/after the given epoch-millis. [FromOffset]
 * seeks every partition to the same absolute offset.
 */
sealed interface StartingPosition {
    data object Beginning : StartingPosition
    data object End : StartingPosition
    data class LastN(val n: Long) : StartingPosition
    data class FromTimestamp(val epochMs: Long) : StartingPosition
    data class FromOffset(val offset: Long) : StartingPosition
}
