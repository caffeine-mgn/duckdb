package pw.binom.db.duckdb.adapter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pw.binom.db.ColumnType
import pw.binom.db.Connection
import pw.binom.db.ConstraintViolationException
import pw.binom.db.DatabaseInfo
import pw.binom.db.DatabaseEngine
import pw.binom.db.LockStrategy
import pw.binom.db.PreparedStatement
import pw.binom.db.ResultSet
import pw.binom.db.SqlErrorException
import pw.binom.db.SqlException
import pw.binom.db.SqlValue
import pw.binom.db.TransactionMode
import pw.binom.db.duckdb.DuckDbConfig
import pw.binom.db.duckdb.DuckDbConnection
import pw.binom.db.duckdb.DuckDbConstraintException
import pw.binom.db.duckdb.DuckDbException
import pw.binom.db.duckdb.DuckDbLoadException
import pw.binom.db.duckdb.DuckDbPreparedStatement
import pw.binom.db.duckdb.DuckDbResultSet
import pw.binom.db.duckdb.DuckDbSqlException
import pw.binom.db.duckdb.DuckDbType
import pw.binom.db.duckdb.DuckDbValue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * `DatabaseInfo` для DuckDB.
 *
 * ## Почему `engine = SQLITE`
 *
 * `pw.binom.db.DatabaseEngine` знает только POSTGRESQL и SQLITE, а DuckDB
 * ближе к SQLite по поведению ORM: одна база-файл, нет `FOR UPDATE`,
 * `RETURNING` есть и работает. Ввести третий член enum'а нельзя — это
 * сломало бы korm и все существующие `when` у потребителей. Поэтому
 * адаптер заявляет SQLITE и переопределяет то, что у DuckDB отличается:
 * кавычки (двойные, как у SQLite — совпадает), блокировку
 * ([LockStrategy.TRANSACTION_LOCK], как у SQLite `BEGIN IMMEDIATE`).
 *
 * Как только в `DatabaseEngine` появится DUCKDB — здесь меняется одно
 * слово, остальное остаётся.
 */
object DuckDbDatabaseInfo : DatabaseInfo {
    override val engine: DatabaseEngine = DatabaseEngine.SQLITE

    override val tableNameQuotesStart: String = "\""

    override val tableNameQuotesEnd: String = "\""

    /**
     * DuckDB, как и SQLite, не знает `SELECT ... FOR UPDATE` — проверено:
     * `Parser Error: syntax error at or near "FOR"`. Блокировка берётся на
     * уровне транзакции (`BEGIN TRANSACTION` в WAL-режиме DuckDB держит
     * запись с начала), поэтому стратегия — [LockStrategy.TRANSACTION_LOCK].
     */
    override val lockStrategy: LockStrategy = LockStrategy.TRANSACTION_LOCK
}

/**
 * Соединение DuckDB в терминах `pw.binom.db.Connection`.
 *
 * ## Про `suspend`
 *
 * Контракт kdatabase объявляет методы `suspend`, потому что рассчитан на
 * сетевые драйверы. DuckDB локальна, ждать нечего — поэтому методы просто
 * вызывают блокирующий код ядра. `withContext(Dispatchers.Default)` не
 * ставим: переключение диспетчера на CPU-bound работе даёт оверхед без
 * выгоды, а на Kotlin/Native `Dispatchers.IO` вообще internal.
 *
 * Если вызывающий хочет уйти с UI-потока — это его `withContext`, и он
 * знает, куда именно переключаться.
 */
class DuckDbKDatabaseConnection internal constructor(
    private val core: DuckDbConnection,
) : Connection {

    override val isClosed: Boolean get() = core.isClosed

    override val dbInfo: DatabaseInfo get() = DuckDbDatabaseInfo

    /** DuckDB не отдаёт состояние «занята»: запрос либо идёт, либо нет. */
    override val isReadyForQuery: Boolean get() = !core.isClosed

    private var currentMode: TransactionMode = TransactionMode.READ_COMMITTED
    private var inTransaction = false

    override val transactionMode: TransactionMode get() = currentMode

    override val isInTransaction: Boolean get() = inTransaction

    /**
     * DuckDB поддерживает уровни изоляции через `SET TRANSACTION`. Реально
     * различаются только `SERIALIZABLE` и `READ COMMITTED`; остальные
     * принимаются, но ведут себя как ближайший поддерживаемый — движок
     * об этом не падает, и драйвер тоже не притворяется, что уровень
     * применился точно.
     */
    override suspend fun setTransactionMode(mode: TransactionMode) {
        currentMode = mode
    }

    override suspend fun beginTransaction() {
        if (inTransaction) {
            throw SqlException("транзакция уже открыта")
        }
        mapErrors { core.execute("BEGIN TRANSACTION") }
        inTransaction = true
    }

    override suspend fun commit() {
        if (!inTransaction) {
            throw SqlException("нет открытой транзакции")
        }
        mapErrors { core.execute("COMMIT") }
        inTransaction = false
    }

    override suspend fun rollback() {
        if (!inTransaction) {
            throw SqlException("нет открытой транзакции")
        }
        mapErrors { core.execute("ROLLBACK") }
        inTransaction = false
    }

    override suspend fun prepare(sql: String): PreparedStatement = mapErrors {
        /*
         * korm генерирует `:name`, а C API DuckDB принимает только
         * позиционные `?`. Перевод делается здесь, на границе драйвера:
         * выше (ORM) остаётся именованный SQL, ниже (ядро) — позиционный.
         */
        val parsed = SqlParameters.parse(sql)
        DuckDbKDatabaseStatement(core.prepare(parsed.sql), this, parsed)
    }

    override suspend fun queryStream(sql: String, fetchSize: Int): ResultSet {
        require(fetchSize > 0) { "fetchSize должен быть > 0, получено $fetchSize" }
        /* DuckDB отдаёт результат целиком в память (`duckdb_execute_prepared`),
         * потокового портала у неё нет. Притворяться потоковым нельзя: это
         * ровно та болезнь, от которой уходим. Честно сообщаем. */
        throw UnsupportedOperationException(
            "DuckDB не поддерживает потоковое чтение: результат материализуется движком",
        )
    }

    override fun close() {
        inTransaction = false
        core.close()
    }
}

/** Подготовленный запрос kdatabase поверх DuckDB. */
internal class DuckDbKDatabaseStatement(
    private val core: DuckDbPreparedStatement,
    override val connection: Connection,
    /**
     * Результат перевода `:name` → `?`. Нужен для проверки, что число
     * привязок соответствует числу параметров: молчаливое расхождение
     * даёт ошибку движка «не все параметры заданы» вместо внятного
     * сообщения драйвера.
     */
    private val parsed: SqlParameters.Parsed? = null,
) : PreparedStatement {

    override suspend fun reset() {
        /* У DuckDB `duckdb_clear_bindings` сбрасывает привязки, но
         * подготовленный запрос переиспользуется как есть. Контракт
         * требует «сбросить курсор и привязки», а курсор закрывается
         * вместе с ResultSet, поэтому здесь достаточно перепривязать
         * значения — их и перепривязывает вызывающий через bindAll. */
    }

    override suspend fun bind(index: Int, value: SqlValue) = mapErrors {
        core.bind(index, value.toDuckDbValue())
    }

    override suspend fun executeQuery(): ResultSet = mapErrors {
        DuckDbKDatabaseResultSet(core.executeQuery())
    }

    override suspend fun executeUpdate(): Long = mapErrors { core.executeUpdate() }

    override fun close() = core.close()
}

/** Курсор kdatabase поверх DuckDB. */
internal class DuckDbKDatabaseResultSet(
    private val core: DuckDbResultSet,
) : ResultSet {

    override val columns: List<String> get() = core.columns

    override fun columnType(index: Int): ColumnType = core.columnType(index).toColumnType()

    override suspend fun next(): Boolean = mapErrors { core.next() }

    override fun isNull(index: Int): Boolean = core.isNull(index)

    override fun getString(index: Int): String? = core.getString(index)

    override fun getBoolean(index: Int): Boolean? = core.getBoolean(index)

    override fun getByte(index: Int): Byte? = core.getByte(index)

    override fun getShort(index: Int): Short? = core.getShort(index)

    override fun getInt(index: Int): Int? = core.getInt(index)

    override fun getLong(index: Int): Long? = core.getLong(index)

    override fun getFloat(index: Int): Float? = core.getFloat(index)

    override fun getDouble(index: Int): Double? = core.getDouble(index)

    override fun getBytes(index: Int): ByteArray? = core.getBytes(index)

    override fun getInstant(index: Int): Instant? = core.getInstant(index)

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    override fun getUuid(index: Int): Uuid? = core.getUuid(index)

    override fun close() = core.close()
}

// ---------------------------------------------------------------- перевод

internal fun SqlValue.toDuckDbValue(): DuckDbValue = when (this) {
    SqlValue.Null -> DuckDbValue.Null
    is SqlValue.Text -> DuckDbValue.Text(value)
    is SqlValue.Bool -> DuckDbValue.Bool(value)
    is SqlValue.Int8 -> DuckDbValue.Int8(value)
    is SqlValue.Int16 -> DuckDbValue.Int16(value)
    is SqlValue.Int32 -> DuckDbValue.Int32(value)
    is SqlValue.Int64 -> DuckDbValue.Int64(value)
    is SqlValue.Float32 -> DuckDbValue.Float32(value)
    is SqlValue.Float64 -> DuckDbValue.Float64(value)
    is SqlValue.Bytes -> DuckDbValue.Bytes(value)
    is SqlValue.Time -> DuckDbValue.Time(value)
    is SqlValue.UuidValue -> DuckDbValue.UuidValue(value)
}

internal fun DuckDbType.toColumnType(): ColumnType = when (this) {
    DuckDbType.BOOLEAN, DuckDbType.BIT -> ColumnType.BIT
    DuckDbType.TINYINT, DuckDbType.UTINYINT -> ColumnType.TINYINT
    DuckDbType.SMALLINT, DuckDbType.USMALLINT -> ColumnType.SMALLINT
    DuckDbType.INTEGER, DuckDbType.UINTEGER -> ColumnType.INTEGER
    DuckDbType.BIGINT, DuckDbType.UBIGINT -> ColumnType.BIGINT
    DuckDbType.FLOAT -> ColumnType.FLOAT
    DuckDbType.DOUBLE -> ColumnType.DOUBLE
    DuckDbType.HUGEINT, DuckDbType.INTERVAL -> ColumnType.NUMERIC
    DuckDbType.DECIMAL -> ColumnType.DECIMAL
    DuckDbType.VARCHAR, DuckDbType.ENUM -> ColumnType.VARCHAR
    DuckDbType.DATE -> ColumnType.DATE
    DuckDbType.TIME -> ColumnType.TIME
    DuckDbType.TIMESTAMP -> ColumnType.TIMESTAMP
    DuckDbType.BLOB -> ColumnType.VARBINARY
    DuckDbType.UUID -> ColumnType.UUID
    DuckDbType.NULL -> ColumnType.NULL
    DuckDbType.OTHER -> ColumnType.OTHER
}

/**
 * Переводит исключения ядра в иерархию `pw.binom.db.SqlException`.
 *
 * Пул и ORM различают три случая по типу исключения, поэтому перевод
 * обязателен: без него `ConstraintViolationException` не отличить от
 * синтаксической ошибки, и пул начнёт повторять то, что повторять нельзя.
 *
 * Классификация идёт **по тексту**, а не только по классу исключения.
 * Причина: `DuckDbSqlException` приходит из разных слоёв (execute,
 * prepare, executePrepared), и не каждый из них успевает вызвать
 * [classifySqlError]. Текст движка — единственный общий признак, который
 * есть на всех путях, поэтому он и решает.
 */
internal fun DuckDbException.toSqlException(): SqlException = when (this) {
    is DuckDbConstraintException -> ConstraintViolationException(message, this)
    is DuckDbLoadException -> SqlException("DuckDB не загружена: $message", this)
    else -> {
        val text = message.orEmpty()
        when {
            text.contains("Constraint Error", ignoreCase = true) ||
                text.contains("constraint failed", ignoreCase = true) ||
                text.contains("Duplicate key", ignoreCase = true) ->
                ConstraintViolationException(text, this)

            else -> SqlErrorException(text, this)
        }
    }
}

/** Обёртка: выполняет блок и переводит исключения ядра в контрактные. */
internal inline fun <T> mapErrors(block: () -> T): T = try {
    block()
} catch (e: DuckDbException) {
    throw e.toSqlException()
}
