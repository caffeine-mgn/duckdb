package pw.binom.db.duckdb

/**
 * Низкоуровневый доступ к C API DuckDB.
 *
 * Реализаций ровно две, и обе — тонкие: JNI (JVM/Android) и cinterop
 * (Kotlin/Native). Всё, что выше, живёт в commonMain и о платформе не знает.
 *
 * ## Почему интерфейс, а не `expect class`
 *
 * `expect class` заставил бы дублировать сигнатуры в каждом таргете и не
 * дал бы подменить реализацию в тесте. Интерфейс + фабрика [openNative]
 * дешевле: одна точка expect/actual на всю платформенную часть.
 *
 * ## Почему индексы и хендлы вместо объектов
 *
 * Хендлы — `Long`, а не обёртки: они копируются между потоками без
 * синхронизации и не держат GC-корни на нативную память. Освобождение —
 * явное, через `close`, как в самом DuckDB.
 *
 * ## Ошибки
 *
 * Методы бросают исключения из `DuckDbException.kt`. Классификация по
 * тексту сообщения делается в [classifySqlError] — общий код для обеих
 * реализаций.
 */
internal interface DuckDbNative {

    /** Загружает библиотеку. 0 — успех, отрицательный код — ошибка. */
    fun init(): Int

    /** Текст последней ошибки платформенного слоя. */
    fun lastError(): String

    /** Версия движка; `null`, если библиотека не загрузилась. */
    fun version(): String?

    /** Открывает базу. Бросает [DuckDbLoadException]/[DuckDbConnectionException]. */
    fun open(path: String?, readOnly: Boolean): Long

    fun close(handle: Long)

    /** Исполняет SQL без курсора. Возвращает число изменённых строк. */
    fun execute(handle: Long, sql: String): Long

    fun prepare(handle: Long, sql: String): Long

    fun parameterCount(stmt: Long): Int

    fun destroyStatement(stmt: Long)

    fun bindNull(stmt: Long, index: Int)

    fun bindBool(stmt: Long, index: Int, value: Boolean)

    fun bindInt8(stmt: Long, index: Int, value: Byte)

    fun bindInt16(stmt: Long, index: Int, value: Short)

    fun bindInt32(stmt: Long, index: Int, value: Int)

    fun bindInt64(stmt: Long, index: Int, value: Long)

    fun bindFloat(stmt: Long, index: Int, value: Float)

    fun bindDouble(stmt: Long, index: Int, value: Double)

    fun bindText(stmt: Long, index: Int, value: String)

    fun bindBytes(stmt: Long, index: Int, value: ByteArray)

    /** Микросекунды с эпохи Unix. */
    fun bindTimestampMicros(stmt: Long, index: Int, micros: Long)

    /** 16 байт в порядке RFC 4122 (big-endian). */
    fun bindUuid(stmt: Long, index: Int, bytes16: ByteArray)

    fun executePrepared(stmt: Long): ExecuteResult

    fun cursorColumnCount(cursor: Long): Int

    fun cursorColumnName(cursor: Long, index: Int): String

    fun cursorColumnType(cursor: Long, index: Int): DuckDbType

    /** `true` — строка есть, `false` — кончились. */
    fun cursorNext(cursor: Long): Boolean

    fun cursorIsNull(cursor: Long, index: Int): Boolean

    fun cursorGetBoolean(cursor: Long, index: Int): Boolean

    fun cursorGetLong(cursor: Long, index: Int): Long

    fun cursorGetDouble(cursor: Long, index: Int): Double

    fun cursorGetString(cursor: Long, index: Int): String

    fun cursorGetBytes(cursor: Long, index: Int): ByteArray

    /** Микросекунды с эпохи Unix. */
    fun cursorGetTimestampMicros(cursor: Long, index: Int): Long

    /** 16 байт в порядке RFC 4122. */
    fun cursorGetUuid(cursor: Long, index: Int): ByteArray

    fun cursorClose(cursor: Long)
}

/**
 * Результат исполнения подготовленного запроса.
 *
 * [cursor] == 0 — запрос не вернул строк (DDL/DML), число изменённых строк
 * в [rowsChanged]. Иначе `cursor` действителен до `cursorClose` и
 * `stmtClose` — закрывается один раз, вторым закроется уже освобождённое.
 */
internal class ExecuteResult(
    val cursor: Long,
    val rowsChanged: Long,
)

/** Фабрика платформенной реализации. Одна на процесс. */
internal expect fun openNative(): DuckDbNative
