package pw.binom.db.duckdb.adapter

import kotlinx.coroutines.test.runTest
import pw.binom.db.ConstraintViolationException
import pw.binom.db.SqlErrorException
import pw.binom.db.SqlValue
import pw.binom.db.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Тесты адаптера к pw.binom.db.
 *
 * Проверяют не ядро, а именно перевод: SqlValue -> вызовы ядра,
 * исключения ядра -> SqlException-иерархия, транзакции и пул.
 */
class DuckDbKDatabaseTest {

    private fun connection() = DuckDbKDatabaseConnection(
        pw.binom.db.duckdb.DuckDbConnection.openInMemory(),
    )

    @Test
    fun basicQueryThroughContract() = runTest {
        connection().use { conn ->
            conn.executeUpdate("CREATE TABLE t(id INTEGER, name VARCHAR)")
            assertEquals(
                2L,
                conn.prepare("INSERT INTO t VALUES (?, ?)").use { st ->
                    st.bind(0, SqlValue.Int32(1))
                    st.bind(1, SqlValue.Text("a"))
                    st.executeUpdate() +
                        run {
                            st.bind(0, SqlValue.Int32(2))
                            st.bind(1, SqlValue.Text("b"))
                            st.executeUpdate()
                        }
                },
            )

            conn.prepare("SELECT name FROM t WHERE id = ?").use { st ->
                st.bind(0, SqlValue.Int32(2))
                st.executeQuery().use { rs ->
                    assertTrue(rs.next(), "строка не найдена")
                    assertEquals("b", rs.getString("name"))
                    assertFalse(rs.next(), "строк должно быть ровно одна")
                }
            }
        }
    }

    @Test
    fun allSqlValueKindsBind() = runTest {
        connection().use { conn ->
            conn.executeUpdate(
                "CREATE TABLE v(b BOOLEAN, i8 TINYINT, i16 SMALLINT, i32 INTEGER, " +
                    "i64 BIGINT, f REAL, d DOUBLE, t VARCHAR, bl BLOB, ts TIMESTAMP)",
            )
            conn.prepare("INSERT INTO v VALUES (?,?,?,?,?,?,?,?,?,?)").use { st ->
                val values = listOf(
                    SqlValue.Bool(true),
                    SqlValue.Int8(1),
                    SqlValue.Int16(2),
                    SqlValue.Int32(3),
                    SqlValue.Int64(4),
                    SqlValue.Float32(1.5f),
                    SqlValue.Float64(2.5),
                    SqlValue.Text("строка"),
                    SqlValue.Bytes(byteArrayOf(1, 2, 3)),
                    SqlValue.Time(kotlin.time.Instant.fromEpochSeconds(1_700_000_000)),
                )
                values.forEachIndexed { i, v -> st.bind(i, v) }
                assertEquals(1L, st.executeUpdate())
            }

            conn.query("SELECT b, i64, d, t, bl FROM v").use { rs ->
                assertTrue(rs.next())
                assertEquals(true, rs.getBoolean("b"))
                assertEquals(4L, rs.getLong("i64"))
                assertEquals(2.5, rs.getDouble("d"))
                assertEquals("строка", rs.getString("t"))
                assertNotNull(rs.getBytes("bl"))
                assertEquals(3, rs.getBytes("bl")!!.size)
            }
        }
    }

    @Test
    fun nullRoundTrips() = runTest {
        connection().use { conn ->
            conn.executeUpdate("CREATE TABLE n(a INTEGER, b VARCHAR)")
            conn.prepare("INSERT INTO n VALUES (?, ?)").use { st ->
                st.bind(0, SqlValue.Null)
                st.bind(1, SqlValue.Null)
                st.executeUpdate()
            }
            conn.query("SELECT a, b FROM n").use { rs ->
                assertTrue(rs.next())
                assertTrue(rs.isNull("a"))
                assertTrue(rs.isNull("b"))
            }
        }
    }

    @Test
    fun transactionCommitsAndRollsBack() = runTest {
        connection().use { conn ->
            conn.executeUpdate("CREATE TABLE tx(id INTEGER)")

            conn.transaction { tx ->
                tx.executeUpdate("INSERT INTO tx VALUES (1)")
            }
            conn.query("SELECT count(*) FROM tx").use { rs ->
                assertTrue(rs.next())
                assertEquals(1L, rs.getLong(0), "commit не сработал")
            }

            runCatching {
                conn.transaction { tx ->
                    tx.executeUpdate("INSERT INTO tx VALUES (2)")
                    error("намеренный сбой")
                }
            }
            conn.query("SELECT count(*) FROM tx").use { rs ->
                assertTrue(rs.next())
                assertEquals(1L, rs.getLong(0), "rollback не сработал")
            }
        }
    }

    @Test
    fun errorsMapToContractHierarchy() = runTest {
        connection().use { conn ->
            assertFailsWith<SqlErrorException>("нет такой таблицы — это SqlError") {
                conn.query("SELECT * FROM no_such_table")
            }

            conn.executeUpdate("CREATE TABLE u(id INTEGER PRIMARY KEY)")
            conn.executeUpdate("INSERT INTO u VALUES (1)")
            assertFailsWith<ConstraintViolationException>("дубликат ключа — это Constraint") {
                conn.executeUpdate("INSERT INTO u VALUES (1)")
            }
        }
    }

    @Test
    fun dbInfoReportsDuckDbBehaviour() = runTest {
        connection().use { conn ->
            val info = conn.dbInfo
            assertEquals("pw.binom.db.duckdb.adapter.DuckDbDatabaseInfo", info::class.qualifiedName)
            assertEquals("\"", info.tableNameQuotesStart)
            assertEquals("\"", info.tableNameQuotesEnd)
            /* DuckDB не знает FOR UPDATE — суффикс должен быть пустым,
             * иначе ORM сгенерирует SQL, который движок отвергнет. */
            assertEquals("", info.dialect.forUpdateClause())
        }
    }

    @Test
    fun streamingIsRefusedNotFaked() = runTest {
        connection().use { conn ->
            assertFailsWith<UnsupportedOperationException>(
                "DuckDB материализует результат — потоковое чтение притворяться не должно",
            ) {
                conn.queryStream("SELECT 1", 10)
            }
        }
    }

    @Test
    fun poolOpensAndServes() = runTest {
        openDuckDbPool(DuckDbPoolConfig(maxConnections = 2)).use { pool ->
            pool.withConnection { conn ->
                conn.executeUpdate("CREATE TABLE pooled(x INTEGER)")
                conn.executeUpdate("INSERT INTO pooled VALUES (42)")
            }
            pool.withConnection { conn ->
                conn.query("SELECT x FROM pooled").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(42L, rs.getLong(0))
                }
            }
        }
    }
}
