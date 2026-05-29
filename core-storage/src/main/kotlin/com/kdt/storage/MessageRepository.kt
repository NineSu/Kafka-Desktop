package com.kdt.storage

import com.fasterxml.jackson.databind.ObjectMapper
import com.kdt.filter.FilterNode
import com.kdt.filter.SqlCompiler
import com.kdt.kafka.ConsumedMessage
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant

data class MessageRow(
    val partition: Int,
    val offset: Long,
    val timestampMs: Long,
    val key: String?,
    val valueStr: String?,
    val valueJson: String?,
    val headersJson: String?,
    val keyBytes: ByteArray? = null,
    val valueBytes: ByteArray? = null,
)

/**
 * DuckDB-backed message cache. One database file per app session (in-memory by default).
 *
 * Threading: all public methods are safe to call from any thread; the underlying
 * connection is synchronized. Consumers do bulk [insertBatch] off the UI thread, while the
 * UI calls [query] on a background task.
 */
class MessageRepository(
    dbPath: String = ":memory:",
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(MessageRepository::class.java)
    private val mapper = ObjectMapper()
    private val compiler = SqlCompiler()
    private val connection: Connection

    init {
        // duckdb jdbc driver loads itself on first DriverManager call
        Class.forName("org.duckdb.DuckDBDriver")
        connection = DriverManager.getConnection("jdbc:duckdb:$dbPath")
        migrate()
    }

    private fun migrate() {
        connection.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS messages (
                    cluster_id TEXT NOT NULL,
                    topic TEXT NOT NULL,
                    partition INTEGER NOT NULL,
                    "offset" BIGINT NOT NULL,
                    ts TIMESTAMP NOT NULL,
                    key_bytes BLOB,
                    key_str TEXT,
                    value_bytes BLOB,
                    value_json JSON,
                    value_str TEXT,
                    headers JSON,
                    PRIMARY KEY (cluster_id, topic, partition, "offset")
                )
                """.trimIndent()
            )
            // DuckDB does not yet support arbitrary secondary indexes on the primary table the same way PG does,
            // but for typical filter workloads the primary key + columnar layout is sufficient.
        }
    }

    @Synchronized
    fun insertBatch(clusterId: String, topic: String, batch: List<ConsumedMessage>) {
        if (batch.isEmpty()) return
        val sql = """
            INSERT OR IGNORE INTO messages
                (cluster_id, topic, partition, "offset", ts, key_bytes, key_str, value_bytes, value_json, value_str, headers)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
        """.trimIndent()
        connection.prepareStatement(sql).use { ps ->
            for (m in batch) {
                val valueStr = m.valueAsString()
                val valueJson = valueStr?.takeIf { looksLikeJson(it) }
                val headersJson = mapper.writeValueAsString(
                    m.headers.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) }
                )
                ps.setString(1, clusterId)
                ps.setString(2, topic)
                ps.setInt(3, m.partition)
                ps.setLong(4, m.offset)
                ps.setTimestamp(5, Timestamp.from(Instant.ofEpochMilli(m.timestampMs)))
                ps.setBytes(6, m.key)
                ps.setString(7, m.keyAsString())
                ps.setBytes(8, m.value)
                if (valueJson != null) ps.setString(9, valueJson) else ps.setNull(9, java.sql.Types.OTHER)
                ps.setString(10, valueStr)
                ps.setString(11, headersJson)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    @Synchronized
    fun query(
        clusterId: String,
        topic: String,
        filter: FilterNode? = null,
        offsetPage: Long = 0,
        limit: Int = 500,
    ): List<MessageRow> {
        val compiled = compiler.compile(filter)
        val where = buildString {
            append("cluster_id = ? AND topic = ?")
            if (!compiled.isEmpty()) {
                append(" AND ")
                append(compiled.whereClause)
            }
        }
        val sql = """
            SELECT partition, "offset", ts, key_str, value_str, value_json, headers
            FROM messages
            WHERE $where
            ORDER BY ts, partition, "offset"
            LIMIT ? OFFSET ?
        """.trimIndent()
        return connection.prepareStatement(sql).use { ps ->
            var idx = 1
            ps.setString(idx++, clusterId)
            ps.setString(idx++, topic)
            for (p in compiled.params) {
                when (p) {
                    is String -> ps.setString(idx++, p)
                    is Double -> ps.setDouble(idx++, p)
                    is Long -> ps.setLong(idx++, p)
                    is Int -> ps.setInt(idx++, p)
                    is Boolean -> ps.setBoolean(idx++, p)
                    null -> ps.setNull(idx++, java.sql.Types.VARCHAR)
                    else -> ps.setObject(idx++, p)
                }
            }
            ps.setInt(idx++, limit)
            ps.setLong(idx, offsetPage)
            val rs = ps.executeQuery()
            buildList {
                while (rs.next()) {
                    add(
                        MessageRow(
                            partition = rs.getInt(1),
                            offset = rs.getLong(2),
                            timestampMs = rs.getTimestamp(3).time,
                            key = rs.getString(4),
                            valueStr = rs.getString(5),
                            valueJson = rs.getString(6),
                            headersJson = rs.getString(7),
                        )
                    )
                }
            }
        }
    }

    /**
     * Streams every row matching the filter (no pagination), invoking [rowConsumer]
     * once per row. Used for export of the full filtered result set without buffering
     * it all in memory. Same ordering as [query].
     */
    @Synchronized
    fun streamFiltered(
        clusterId: String,
        topic: String,
        filter: FilterNode? = null,
        rowConsumer: (MessageRow) -> Unit,
    ) {
        val compiled = compiler.compile(filter)
        val where = buildString {
            append("cluster_id = ? AND topic = ?")
            if (!compiled.isEmpty()) {
                append(" AND ")
                append(compiled.whereClause)
            }
        }
        val sql = """
            SELECT partition, "offset", ts, key_str, value_str, value_json, headers
            FROM messages
            WHERE $where
            ORDER BY ts, partition, "offset"
        """.trimIndent()
        connection.prepareStatement(sql).use { ps ->
            var idx = 1
            ps.setString(idx++, clusterId)
            ps.setString(idx++, topic)
            for (p in compiled.params) {
                when (p) {
                    is String -> ps.setString(idx++, p)
                    is Double -> ps.setDouble(idx++, p)
                    is Long -> ps.setLong(idx++, p)
                    is Int -> ps.setInt(idx++, p)
                    is Boolean -> ps.setBoolean(idx++, p)
                    null -> ps.setNull(idx++, java.sql.Types.VARCHAR)
                    else -> ps.setObject(idx++, p)
                }
            }
            val rs = ps.executeQuery()
            while (rs.next()) {
                rowConsumer(
                    MessageRow(
                        partition = rs.getInt(1),
                        offset = rs.getLong(2),
                        timestampMs = rs.getTimestamp(3).time,
                        key = rs.getString(4),
                        valueStr = rs.getString(5),
                        valueJson = rs.getString(6),
                        headersJson = rs.getString(7),
                    )
                )
            }
        }
    }

    @Synchronized
    fun count(clusterId: String, topic: String, filter: FilterNode? = null): Long {
        val compiled = compiler.compile(filter)
        val where = buildString {
            append("cluster_id = ? AND topic = ?")
            if (!compiled.isEmpty()) {
                append(" AND ")
                append(compiled.whereClause)
            }
        }
        val sql = "SELECT COUNT(*) FROM messages WHERE $where"
        return connection.prepareStatement(sql).use { ps ->
            var idx = 1
            ps.setString(idx++, clusterId)
            ps.setString(idx++, topic)
            for (p in compiled.params) {
                when (p) {
                    is String -> ps.setString(idx++, p)
                    is Double -> ps.setDouble(idx++, p)
                    is Long -> ps.setLong(idx++, p)
                    is Int -> ps.setInt(idx++, p)
                    is Boolean -> ps.setBoolean(idx++, p)
                    null -> ps.setNull(idx++, java.sql.Types.VARCHAR)
                    else -> ps.setObject(idx++, p)
                }
            }
            val rs = ps.executeQuery()
            if (rs.next()) rs.getLong(1) else 0L
        }
    }

    @Synchronized
    fun clear(clusterId: String, topic: String) {
        connection.prepareStatement("DELETE FROM messages WHERE cluster_id = ? AND topic = ?").use { ps ->
            ps.setString(1, clusterId)
            ps.setString(2, topic)
            ps.executeUpdate()
        }
    }

    /**
     * LRU eviction: keep only the newest [keepNewest] rows for (clusterId, topic),
     * deleting the oldest by (ts, partition, offset). Returns the number of rows deleted.
     * No-op when the current count is within the cap.
     */
    @Synchronized
    fun evictOldest(clusterId: String, topic: String, keepNewest: Int): Int {
        val total = count(clusterId, topic, null)
        val excess = total - keepNewest
        if (excess <= 0) return 0
        val sql = """
            DELETE FROM messages WHERE rowid IN (
                SELECT rowid FROM messages
                WHERE cluster_id = ? AND topic = ?
                ORDER BY ts, partition, "offset"
                LIMIT ?
            )
        """.trimIndent()
        return connection.prepareStatement(sql).use { ps ->
            ps.setString(1, clusterId)
            ps.setString(2, topic)
            ps.setLong(3, excess)
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun findOne(clusterId: String, topic: String, partition: Int, offset: Long): MessageRow? {
        val sql = """
            SELECT partition, "offset", ts, key_str, value_str, value_json, headers, key_bytes, value_bytes
            FROM messages
            WHERE cluster_id = ? AND topic = ? AND partition = ? AND "offset" = ?
            LIMIT 1
        """.trimIndent()
        return connection.prepareStatement(sql).use { ps ->
            ps.setString(1, clusterId)
            ps.setString(2, topic)
            ps.setInt(3, partition)
            ps.setLong(4, offset)
            val rs = ps.executeQuery()
            if (!rs.next()) null
            else MessageRow(
                partition = rs.getInt(1),
                offset = rs.getLong(2),
                timestampMs = rs.getTimestamp(3).time,
                key = rs.getString(4),
                valueStr = rs.getString(5),
                valueJson = rs.getString(6),
                headersJson = rs.getString(7),
                keyBytes = blobToBytes(rs.getObject(8)),
                valueBytes = blobToBytes(rs.getObject(9)),
            )
        }
    }

    /** DuckDB's JDBC driver returns BLOB as ByteBuffer (or similar) — handle several possible shapes. */
    private fun blobToBytes(any: Any?): ByteArray? = when (any) {
        null -> null
        is ByteArray -> any
        is java.nio.ByteBuffer -> ByteArray(any.remaining()).also { any.get(it) }
        is java.sql.Blob -> any.getBytes(1, any.length().toInt())
        is org.duckdb.DuckDBResultSet.DuckDBBlobResult -> {
            val stream = any.binaryStream
            stream.use { it.readAllBytes() }
        }
        else -> {
            // Fallback: stringify (loses fidelity but avoids crash)
            log.warn("Unknown BLOB return type: {}", any.javaClass.name)
            null
        }
    }

    private fun looksLikeJson(s: String): Boolean {
        val t = s.trim()
        return t.startsWith("{") || t.startsWith("[")
    }

    override fun close() {
        try {
            connection.close()
        } catch (e: Exception) {
            log.warn("Error closing DuckDB connection", e)
        }
    }
}
