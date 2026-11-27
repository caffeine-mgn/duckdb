package pw.binom.db.duckdb.orm

import pw.binom.db.DatabaseEngine
import pw.binom.db.duckdb.adapter.DuckDbDatabaseInfo
import pw.binom.db.duckdb.adapter.DuckDbPoolConfig
import pw.binom.db.duckdb.adapter.openDuckDbPool
import pw.binom.db.orm.Orm
import pw.binom.db.pool.ConnectionPool

/**
 * Фабрика ORM поверх DuckDB.
 *
 * ## Что здесь есть, а что нет
 *
 * `korm` (0.1.0) принимает в конструкторе `Orm(pool, engine)`, где engine —
 * член enum `DatabaseEngine` (POSTGRESQL/SQLITE), и сам решает по нему, как
 * генерировать SQL. Отдельной точки расширения «свой диалект» в этой версии
 * нет, поэтому адаптер отдаёт `SQLITE`: по поведению DuckDB действительно
 * ближе к SQLite — двойные кавычки для идентификаторов, `RETURNING` есть,
 * `FOR UPDATE` нет, транзакционная блокировка записи.
 *
 * Когда в `korm` появится публичный `SqlBuilder.Dialect` в конструкторе
 * (или третий член enum'а), здесь меняется одна строка — остальное
 * остаётся.
 *
 * ## 🔴 Известное ограничение: автоинкремент
 *
 * `korm` для SQLITE-движка генерирует первичный ключ как `INTEGER`. В
 * SQLite колонка `INTEGER PRIMARY KEY` — алиас rowid, заполняется сама.
 * **В DuckDB такого алиаса нет**: `INTEGER PRIMARY KEY` требует явного
 * значения, и вставка без него падает с `NOT NULL constraint failed`.
 *
 * Поэтому таблицы с автогенерируемым ключом надо создавать не через
 * `orm.createTables`, а своим DDL с последовательностью:
 *
 * ```sql
 * CREATE SEQUENCE users_id_seq;
 * CREATE TABLE users (
 *     id BIGINT PRIMARY KEY DEFAULT nextval('users_id_seq'),
 *     name VARCHAR NOT NULL
 * );
 * ```
 *
 * Это ограничение самого `korm`, а не адаптера: исправляется в `korm`
 * добавлением `DatabaseEngine.DUCKDB` и генерации `DEFAULT nextval(...)`.
 * Пока оно есть — его надо знать, иначе первый же `insert` без id
 * выглядит как «адаптер сломан».
 */
object DuckDbOrm {

    /** Пул соединений DuckDB, готовый для передачи в [Orm]. */
    fun openPool(config: DuckDbPoolConfig = DuckDbPoolConfig()): ConnectionPool =
        openDuckDbPool(config)

    /**
     * ORM поверх пула.
     *
     * Движок — [DatabaseEngine.SQLITE]: см. объяснение в KDoc класса и
     * `DuckDbDatabaseInfo`.
     */
    fun open(pool: ConnectionPool): Orm = Orm(pool, DatabaseEngine.SQLITE)

    /** `DatabaseInfo`, который адаптер отдаёт в `Connection.dbInfo`. */
    val databaseInfo get() = DuckDbDatabaseInfo
}
