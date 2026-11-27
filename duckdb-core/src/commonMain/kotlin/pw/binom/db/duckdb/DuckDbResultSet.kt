package pw.binom.db.duckdb

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Курсор по результату запроса.
 *
 * ## Почему блокирующий, а не `suspend`
 *
 * DuckDB — встроенный движок в том же процессе. Ожидания сети нет: строка
 * либо уже в памяти, либо её считает местный CPU. `suspend` здесь был бы
 * данью симметрии с сетевыми драйверами и заставлял бы вызывающего
 * запускать корутину ради работы, которая не отдаёт поток.
 *
 * Адаптер `duckdb-kdatabase` всё равно объявляет `next()` как `suspend` —
 * этого требует контракт `pw.binom.db.ResultSet`. Он просто вызывает
 * блокирующий метод: корутина не притворяется, что ждёт.
 *
 * ## Чтение по индексу и по имени
 *
 * Индекс — 0-based, как в C API DuckDB и как в `pw.binom.db.ResultSet`.
 * По имени — через [columnIndex], регистронезависимо: DuckDB сохраняет
 * регистр идентификатора в кавычках, но в запросах пользователи пишут
 * вольно, и падать на `SELECT name` при колонке `Name` — плохой сервис.
 */
class DuckDbResultSet internal constructor(
    private val native: DuckDbNative,
    private val cursor: Long,
) : AutoCloseable {

    private var closed = false
    private var currentRow = false

    /** Имена колонок в порядке выдачи. */
    val columns: List<String> by lazy {
        val n = native.cursorColumnCount(cursor)
        List(n) { native.cursorColumnName(cursor, it) }
    }

    private fun checkOpen() {
        check(!closed) { "курсор закрыт" }
    }

    private fun checkRow(): Int {
        checkOpen()
        check(currentRow) { "курсор не указывает на строку: не был вызван next() или строки кончились" }
        return 0
    }

    fun columnCount(): Int = native.cursorColumnCount(cursor)

    fun columnType(index: Int): DuckDbType {
        checkOpen()
        return native.cursorColumnType(cursor, index)
    }

    fun columnType(column: String): DuckDbType = columnType(columnIndex(column))

    /** Номер колонки по имени, регистронезависимо. Бросает, если такой нет. */
    fun columnIndex(column: String): Int {
        val i = columns.indexOfFirst { it.equals(column, ignoreCase = true) }
        if (i < 0) {
            throw DuckDbSqlException("колонка «$column» отсутствует, есть: ${columns.joinToString()}")
        }
        return i
    }

    /** Переходит к следующей строке. `false` — строк больше нет. */
    fun next(): Boolean {
        checkOpen()
        currentRow = native.cursorNext(cursor)
        return currentRow
    }

    fun isNull(index: Int): Boolean {
        checkRow()
        return native.cursorIsNull(cursor, index)
    }

    fun isNull(column: String): Boolean = isNull(columnIndex(column))

    fun getString(index: Int): String? {
        checkRow()
        return if (native.cursorIsNull(cursor, index)) null else native.cursorGetString(cursor, index)
    }

    fun getBoolean(index: Int): Boolean? {
        checkRow()
        return if (native.cursorIsNull(cursor, index)) null else native.cursorGetBoolean(cursor, index)
    }

    fun getByte(index: Int): Byte? = getLong(index)?.toByte()

    fun getShort(index: Int): Short? = getLong(index)?.toShort()

    fun getInt(index: Int): Int? = getLong(index)?.toInt()

    fun getLong(index: Int): Long? {
        checkRow()
        return if (native.cursorIsNull(cursor, index)) null else native.cursorGetLong(cursor, index)
    }

    fun getFloat(index: Int): Float? = getDouble(index)?.toFloat()

    fun getDouble(index: Int): Double? {
        checkRow()
        return if (native.cursorIsNull(cursor, index)) null else native.cursorGetDouble(cursor, index)
    }

    fun getBytes(index: Int): ByteArray? {
        checkRow()
        return if (native.cursorIsNull(cursor, index)) null else native.cursorGetBytes(cursor, index)
    }

    fun getInstant(index: Int): Instant? {
        checkRow()
        if (native.cursorIsNull(cursor, index)) return null
        val micros = native.cursorGetTimestampMicros(cursor, index)
        return Instant.fromEpochSeconds(
            epochSeconds = micros / 1_000_000,
            nanosecondAdjustment = ((micros % 1_000_000) * 1_000).toInt(),
        )
    }

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    fun getUuid(index: Int): Uuid? {
        checkRow()
        return if (native.cursorIsNull(cursor, index)) null else Uuid.fromByteArray(native.cursorGetUuid(cursor, index))
    }

    fun getString(column: String): String? = getString(columnIndex(column))
    fun getBoolean(column: String): Boolean? = getBoolean(columnIndex(column))
    fun getByte(column: String): Byte? = getByte(columnIndex(column))
    fun getShort(column: String): Short? = getShort(columnIndex(column))
    fun getInt(column: String): Int? = getInt(columnIndex(column))
    fun getLong(column: String): Long? = getLong(columnIndex(column))
    fun getFloat(column: String): Float? = getFloat(columnIndex(column))
    fun getDouble(column: String): Double? = getDouble(columnIndex(column))
    fun getBytes(column: String): ByteArray? = getBytes(columnIndex(column))
    fun getInstant(column: String): Instant? = getInstant(columnIndex(column))
    fun getUuid(column: String): Uuid? = getUuid(columnIndex(column))

    override fun close() {
        if (closed) return
        closed = true
        currentRow = false
        native.cursorClose(cursor)
    }
}
