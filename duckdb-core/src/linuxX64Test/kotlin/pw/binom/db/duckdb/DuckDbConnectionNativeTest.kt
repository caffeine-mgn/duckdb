package pw.binom.db.duckdb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Тесты ядра на Kotlin/Native.
 *
 * Требуют `libduckdb.so`, доступную через `DUCKDB_LIBRARY` или обычный поиск
 * линкера. Проверяют то же, что JVM-набор: реализация другая (cinterop
 * вместо JNI), контракт один.
 */
class DuckDbConnectionNativeTest {

    private fun requireLibrary() {
        val rc = openNative().init()
        assertEquals(0, rc, "libduckdb не загрузилась: ${openNative().lastError()}")
    }

    @Test
    fun libraryVersionIsReported() {
        requireLibrary()
        val v = DuckDbConnection.libraryVersion()
        assertNotNull(v, "версия движка не получена: ${DuckDbConnection.loadError()}")
        println("duckdb version = $v")
        assertTrue(v.startsWith("v1.") || v.startsWith("v2."), "неожиданная версия: $v")
    }

    @Test
    fun scalarQueryAndDdlDml() {
        requireLibrary()
        DuckDbConnection.openInMemory().use { db ->
            db.query("select 1+1").use { rs ->
                assertTrue(rs.next())
                assertEquals("2", rs.getString(0))
            }
            db.execute("CREATE TABLE t(id INTEGER, name VARCHAR)")
            db.execute("INSERT INTO t VALUES (1, 'a'), (2, 'b')")
            db.query("select count(*) from t").use { rs ->
                assertTrue(rs.next())
                assertEquals(2L, rs.getLong(0))
            }
        }
    }

    @Test
    fun preparedStatementWithParameters() {
        requireLibrary()
        DuckDbConnection.openInMemory().use { db ->
            db.execute("CREATE TABLE p(id INTEGER, txt VARCHAR, d DOUBLE)")
            db.prepare("INSERT INTO p VALUES (?, ?, ?)").use { st ->
                st.bind(0, DuckDbValue.Int32(7))
                st.bind(1, DuckDbValue.Text("семь"))
                st.bind(2, DuckDbValue.Float64(1.5))
                /* DML не возвращает строк: счётчик берётся из duckdb_rows_changed.
                 * Проверка важна: DuckDB отдаёт для INSERT результат с одной
                 * колонкой `Count`, и наивная реализация принимала его за SELECT. */
                assertEquals(1L, st.executeUpdate())
            }
            db.prepare("SELECT txt, d FROM p WHERE id = ?").use { st ->
                st.bind(0, DuckDbValue.Int32(7))
                st.executeQuery().use { rs ->
                    assertTrue(rs.next(), "строка не найдена")
                    assertEquals("семь", rs.getString("txt"))
                    assertEquals(1.5, rs.getDouble("d"))
                    assertEquals(listOf("txt", "d"), rs.columns)
                }
            }
        }
    }

    @Test
    fun nullAndTypeMapping() {
        requireLibrary()
        DuckDbConnection.openInMemory().use { db ->
            db.query("select NULL::INTEGER as n, 42::BIGINT as i, 'x' as s").use { rs ->
                assertTrue(rs.next())
                assertTrue(rs.isNull("n"), "NULL не распознан")
                assertNull(rs.getString("n"))
                assertEquals(42L, rs.getLong("i"))
                assertEquals("x", rs.getString("s"))
                assertEquals(DuckDbType.VARCHAR, rs.columnType("s"))
                assertEquals(DuckDbType.BIGINT, rs.columnType("i"))
            }
        }
    }

    @Test
    fun errorIsClassifiedNotSwallowed() {
        requireLibrary()
        DuckDbConnection.openInMemory().use { db ->
            val e = runCatching { db.query("select * from no_such_table") }.exceptionOrNull()
            assertNotNull(e, "несуществующая таблица должна давать ошибку")
            assertTrue(
                e.message?.contains("no_such_table") == true,
                "в ошибке нет имени таблицы: ${e.message}",
            )
        }
    }

    @Test
    fun timestampRoundTrip() {
        requireLibrary()
        DuckDbConnection.openInMemory().use { db ->
            db.execute("CREATE TABLE ts(id INTEGER, t TIMESTAMP)")
            val instant = kotlin.time.Instant.fromEpochSeconds(1_700_000_000, 123_000_000)
            db.prepare("INSERT INTO ts VALUES (?, ?)").use { st ->
                st.bind(0, DuckDbValue.Int32(1))
                st.bind(1, DuckDbValue.Time(instant))
                st.executeUpdate()
            }
            db.query("SELECT t FROM ts").use { rs ->
                assertTrue(rs.next())
                assertEquals(instant, rs.getInstant("t"), "Timestamp не совпал")
            }
        }
    }
}
