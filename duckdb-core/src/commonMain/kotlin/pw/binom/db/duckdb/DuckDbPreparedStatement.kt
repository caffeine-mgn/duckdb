package pw.binom.db.duckdb

/**
 * Подготовленный запрос.
 *
 * ## Параметры — позиционные, с нуля
 *
 * DuckDB в C API принимает и `?`, и `$1`, и именованные `$name`. Контракт
 * здесь один: позиционный индекс с нуля, порядок совпадает с порядком
 * подстановки. Именованные параметры — дело ORM-диалекта, он переводит их
 * в позиционные до того, как SQL дойдёт сюда.
 *
 * ## Почему bind'ы отдельными методами, а не `bind(index, Any)`
 *
 * Тип известен вызывающему, и C API имеет отдельную функцию под каждый.
 * Приведение в общий `Any` заставило бы делать `when` на каждом bind'е и
 * терять точность: `duckdb_bind_int32` и `duckdb_bind_int64` — разные
 * функции, и разница видна в плане запроса.
 */
class DuckDbPreparedStatement internal constructor(
    private val native: DuckDbNative,
    private val stmt: Long,
    /** SQL, нужен только для текста ошибок. */
    val sql: String,
) : AutoCloseable {

    private var closed = false

    private fun checkOpen() {
        check(!closed) { "запрос закрыт" }
    }

    /** Сколько параметров принимает запрос (по мнению DuckDB). */
    fun parameterCount(): Int = native.parameterCount(stmt)

    fun bind(index: Int, value: DuckDbValue) {
        checkOpen()
        when (value) {
            DuckDbValue.Null -> native.bindNull(stmt, index)
            is DuckDbValue.Text -> native.bindText(stmt, index, value.value)
            is DuckDbValue.Bool -> native.bindBool(stmt, index, value.value)
            is DuckDbValue.Int8 -> native.bindInt8(stmt, index, value.value)
            is DuckDbValue.Int16 -> native.bindInt16(stmt, index, value.value)
            is DuckDbValue.Int32 -> native.bindInt32(stmt, index, value.value)
            is DuckDbValue.Int64 -> native.bindInt64(stmt, index, value.value)
            is DuckDbValue.Float32 -> native.bindFloat(stmt, index, value.value)
            is DuckDbValue.Float64 -> native.bindDouble(stmt, index, value.value)
            is DuckDbValue.Bytes -> native.bindBytes(stmt, index, value.value)
            is DuckDbValue.Time -> native.bindTimestampMicros(
                stmt,
                index,
                value.value.toEpochMilliseconds() * 1_000,
            )
            is DuckDbValue.UuidValue -> native.bindUuid(stmt, index, value.value.toByteArray())
        }
    }

    fun bindAll(values: List<DuckDbValue>) {
        values.forEachIndexed { i, v -> bind(i, v) }
    }

    /** Исполняет запрос, возвращающий строки. */
    fun executeQuery(): DuckDbResultSet {
        checkOpen()
        val r = native.executePrepared(stmt)
        check(r.cursor != 0L) { "запрос не вернул курсор: $sql" }
        return DuckDbResultSet(native, r.cursor)
    }

    /** Исполняет запрос, не возвращающий строк. Возвращает число изменённых строк. */
    fun executeUpdate(): Long {
        checkOpen()
        val r = native.executePrepared(stmt)
        if (r.cursor != 0L) {
            /* Запрос всё-таки вернул строки (например, INSERT ... RETURNING).
             * Курсор закрываем: контракт executeUpdate — счётчик, а не данные. */
            native.cursorClose(r.cursor)
        }
        return r.rowsChanged
    }

    fun query(vararg values: DuckDbValue): DuckDbResultSet {
        bindAll(values.toList())
        return executeQuery()
    }

    fun update(vararg values: DuckDbValue): Long {
        bindAll(values.toList())
        return executeUpdate()
    }

    override fun close() {
        if (closed) return
        closed = true
        native.destroyStatement(stmt)
    }
}
