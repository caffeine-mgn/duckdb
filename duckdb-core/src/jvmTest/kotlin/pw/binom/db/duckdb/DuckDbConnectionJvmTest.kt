package pw.binom.db.duckdb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Тесты ядра на JVM.
 *
 * Требуют `libduckdb.so` рядом: путь задаётся `DUCKDB_LIBRARY` или
 * `java.library.path`. Без библиотеки тесты не «проходят молча» — падают с
 * внятным сообщением, иначе зелёный прогон ничего не доказывает.
 */
class DuckDbConnectionJvmTest {

    private fun requireLibrary(): DuckDbNative {
        val native = openNative()
        val rc = native.init()
        assertEquals(0, rc, "libduckdb не загрузилась: ${native.lastError()}")
        return native
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
            assertEquals("2", db.query("select 1+1").use { rs ->
                rs.next()
                rs.getString(0)
            })

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

            db.execute("CREATE TABLE u(id INTEGER PRIMARY KEY)")
            db.execute("INSERT INTO u VALUES (1)")
            val dup = runCatching { db.execute("INSERT INTO u VALUES (1)") }.exceptionOrNull()
            assertNotNull(dup, "дубликат ключа должен падать")
            assertTrue(
                dup is DuckDbConstraintException,
                "дубликат ключа должен классифицироваться как Constraint, получено ${dup::class.simpleName}",
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

    /**
     * 🔴 Ограничение DuckDB 2.0.1, не адаптера.
     *
     * UUID в DuckDB хранится как HUGEINT, и запись через `bind_varchar`
     * канонической формой 8-4-4-4-12 работает — значение реально ложится
     * (`u::VARCHAR` возвращает его). Но **чтение через C value API
     * возвращает нули**: и `duckdb_value_uhugeint`, и
     * `duckdb_value_string`, и `duckdb_value_varchar` дают 0 и "(null)"
     * соответственно. Проверено отдельной C-программой на релизе 2.0.1,
     * для колонки типа UUID (type=27), без участия Kotlin.
     *
     * Обходной путь, который работает: приводить в SQL явно —
     * `SELECT u::VARCHAR`. Поэтому тест проверяет именно его, а
     * `getUuid` остаётся в API на будущее, когда движок починят.
     */
    @Test
    fun uuidReadsOnlyThroughExplicitCast() {
        requireLibrary()
        DuckDbConnection.openInMemory().use { db ->
            db.execute("CREATE TABLE us(id INTEGER, u UUID)")
            val canonical = "412eeb9a-942f-48d8-9591-b91b204b0877"
            db.prepare("INSERT INTO us VALUES (?, ?)").use { st ->
                st.bind(0, DuckDbValue.Int32(1))
                st.bind(1, DuckDbValue.Text(canonical))
                st.executeUpdate()
            }
            db.query("SELECT u::VARCHAR AS u FROM us").use { rs ->
                assertTrue(rs.next())
                assertEquals(canonical, rs.getString("u"), "UUID не совпал через ::VARCHAR")
                assertEquals(DuckDbType.VARCHAR, rs.columnType("u"))
            }
            // Нативный тип колонки виден правильно, даже если значение не читается
            db.query("SELECT u FROM us").use { rs ->
                assertTrue(rs.next())
                assertEquals(DuckDbType.UUID, rs.columnType("u"), "тип UUID определён неверно")
            }
        }
    }
}
