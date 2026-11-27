package pw.binom.db.duckdb

/**
 * Ошибка работы с DuckDB.
 *
 * Разделение то же, что в kdatabase, и по той же причине: от класса ошибки
 * зависит, что с ней делать. Повторять запрос имеет смысл только при
 * [ConnectionLost]; нарушение ограничения и ошибка SQL — вина вызывающего
 * или схемы, повтор их не исправит.
 */
open class DuckDbException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Ошибка разбора или исполнения SQL: синтаксис, нет таблицы, нет прав. */
class DuckDbSqlException(
    message: String,
    val sql: String? = null,
) : DuckDbException(if (sql != null) "$message\nSQL: $sql" else message)

/** Нарушено ограничение схемы: NOT NULL, UNIQUE, PRIMARY KEY, CHECK, FK. */
class DuckDbConstraintException(
    message: String,
    val sql: String? = null,
) : DuckDbException(if (sql != null) "$message\nSQL: $sql" else message)

/**
 * Соединение потеряно или закрыто извне.
 *
 * В DuckDB это реже, чем в сетевой СУБД, но при `duckdb_close` из другого
 * потока или после фатальной ошибки движка состояние соединения
 * неопределённо, и продолжать на нём нельзя.
 */
class DuckDbConnectionException(
    message: String,
    cause: Throwable? = null,
) : DuckDbException(message, cause)

/** Библиотека DuckDB не загрузилась: нет `.so`/`.dll` или в ней нет символов. */
class DuckDbLoadException(
    message: String,
    cause: Throwable? = null,
) : DuckDbException(message, cause)

/**
 * Приводит текст ошибки SQL к правильному классу.
 *
 * DuckDB не отдаёт SQLSTATE, поэтому классификация — по тексту сообщения.
 * Правила взяты из живых сообщений движка 2.0.1:
 *   `Constraint Error: NOT NULL constraint failed: ...`
 *   `Constraint Error: Duplicate key ...`
 *   `Catalog Error: Table with name X does not exist!`
 *   `Parser Error: syntax error at or near ...`
 * Нераспознанное остаётся [DuckDbSqlException] с исходным текстом: лучше
 * показать сырую ошибку движка, чем спрятать её за общим «что-то не так».
 */
internal fun classifySqlError(message: String, sql: String?): DuckDbException {
    val m = message.trim()
    return when {
        m.startsWith("Constraint Error", ignoreCase = true) ||
            m.contains("constraint failed", ignoreCase = true) ||
            m.contains("Duplicate key", ignoreCase = true) ->
            DuckDbConstraintException(m, sql)

        m.contains("database has been invalidated", ignoreCase = true) ||
            m.contains("connection closed", ignoreCase = true) ->
            DuckDbConnectionException(m)

        else -> DuckDbSqlException(m, sql)
    }
}
