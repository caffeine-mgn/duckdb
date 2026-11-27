package pw.binom.db.duckdb

/**
 * Тип значения колонки в терминах DuckDB, сведённый к категориям, которыми
 * оперирует Kotlin-слой.
 *
 * Намеренно не совпадает один-к-одному с `duckdb_type`: у DuckDB есть
 * UNSIGNED-типы, HUGEINT, ENUM, LIST/STRUCT/MAP, которых в обычном
 * JDBC-подобном наборе нет. Здесь — что можно прочитать через C API
 * без потерь, остальное помечено [OTHER] и читается как текст.
 */
enum class DuckDbType {
    BOOLEAN,
    TINYINT,
    SMALLINT,
    INTEGER,
    BIGINT,
    UTINYINT,
    USMALLINT,
    UINTEGER,
    UBIGINT,
    FLOAT,
    DOUBLE,
    TIMESTAMP,
    DATE,
    TIME,
    INTERVAL,
    HUGEINT,
    VARCHAR,
    BLOB,
    DECIMAL,
    ENUM,
    UUID,
    BIT,

    /** LIST / STRUCT / MAP / UNION: читается только как текст. */
    OTHER,
    NULL,
}
