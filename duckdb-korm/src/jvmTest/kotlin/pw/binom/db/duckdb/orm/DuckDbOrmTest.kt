package pw.binom.db.duckdb.orm

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import pw.binom.db.DatabaseEngine
import pw.binom.db.duckdb.adapter.DuckDbPoolConfig
import pw.binom.db.orm.Id
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Тесты ORM-адаптера.
 *
 * Проверяют, что `korm` действительно генерирует SQL, который DuckDB
 * принимает, — а не просто что объект создался. Поэтому здесь есть и
 * создание таблицы, и вставка, и чтение через ORM.
 *
 * ## Почему DDL пишется руками
 *
 * `createSchema` для сущности с `INTEGER PRIMARY KEY` на DuckDB падает:
 * в SQLite `INTEGER PRIMARY KEY` — алиас rowid и заполняется сам, у DuckDB
 * такого алиаса нет. Поэтому таблица создаётся явным DDL с указанным id —
 * ровно как рекомендовано в README.
 */
class DuckDbOrmTest {

    @Serializable
    data class Person(
        @Id val id: Int,
        val name: String,
        val age: Int,
    )

    @Serializable
    data class Thing(
        @Id val id: Int,
        val title: String,
    )

    @Test
    fun ormReadsWhatItGenerated() = runTest {
        DuckDbOrm.openPool(DuckDbPoolConfig()).use { pool ->
            val orm = DuckDbOrm.open(pool)

            pool.withConnection { conn ->
                conn.executeUpdate(
                    "CREATE TABLE \"Person\" (id INTEGER PRIMARY KEY, name VARCHAR NOT NULL, age INTEGER NOT NULL)",
                )
            }

            orm.insert(Person.serializer(), Person(id = 1, name = "Anton", age = 42))
            orm.insert(Person.serializer(), Person(id = 2, name = "Маша", age = 30))

            val all = orm.findAll(Person.serializer())
            assertEquals(2, all.size, "ORM прочитал не то число строк: $all")
            assertEquals("Anton", all.first { it.id == 1 }.name)
            assertEquals(30, all.first { it.id == 2 }.age)
        }
    }

    @Test
    fun findByIdAndUpdateThroughOrm() = runTest {
        DuckDbOrm.openPool().use { pool ->
            val orm = DuckDbOrm.open(pool)
            pool.withConnection { conn ->
                conn.executeUpdate(
                    "CREATE TABLE \"Thing\" (id INTEGER PRIMARY KEY, title VARCHAR NOT NULL)",
                )
            }
            orm.insert(Thing.serializer(), Thing(id = 1, title = "раз"))
            orm.insert(Thing.serializer(), Thing(id = 2, title = "два"))

            val found = orm.findById(Thing.serializer(), 2)
            assertEquals("два", found?.title, "findById через ORM не нашёл строку")

            orm.update(Thing.serializer(), Thing(id = 2, title = "два!"))
            assertEquals("два!", orm.findById(Thing.serializer(), 2)?.title)

            orm.deleteById(Thing.serializer(), 1)
            assertEquals(1, orm.findAll(Thing.serializer()).size)
            assertTrue(orm.findAll(Thing.serializer()).none { it.id == 1 })
        }
    }

    @Test
    fun engineReportedAsSqliteForKorm() {
        /* Проверка того самого компромисса: korm 0.1.0 знает только два
         * движка, и DuckDB заявлен как SQLITE. Если это изменится —
         * тест упадёт, и адаптер надо будет перевести на DUCKDB. */
        assertEquals(DatabaseEngine.SQLITE, DuckDbOrm.databaseInfo.engine)
    }
}
