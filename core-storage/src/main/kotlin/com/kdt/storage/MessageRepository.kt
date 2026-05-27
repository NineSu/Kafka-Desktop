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
