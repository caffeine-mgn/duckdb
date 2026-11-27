package pw.binom.db.duckdb

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Реализация через JNI — общая для JVM и Android.
 *
 * Класс живёт в `jvmSharedMain`, потому что нативные методы у JVM и ART
 * одни и те же: один и тот же C-мост, одни и те же сигнатуры. Различается
 * только **способ загрузки** библиотеки, и он вынесен в
 * [loadDuckDbkLibrary] (expect/actual):
 *
 *  - JVM: распаковать `.so` из jar во временный каталог, `System.load(path)`;
 *  - Android: `System.loadLibrary("duckdbk")` — библиотека приходит из
 *    `jni/<abi>/` внутри AAR и грузится системой, извлечение из jar там
 *    не работает (linker namespace + SELinux).
 *
 * Сам DuckDB (`libduckdb.so`) в артефакт не входит: он грузится C-швом
 * через dlopen по `DUCKDB_LIBRARY` или обычному поиску линкера.
 */
internal class SharedDuckDb : DuckDbNative {

    companion object {
        /** Ленивая загрузка: одна попытка на процесс. */
        private val loaded: Result<Unit> by lazy { runCatching { loadDuckDbkLibrary() } }

        private fun ensureLoaded() {
            loaded.getOrElse { e ->
                throw DuckDbLoadException(
                    "не удалось загрузить JNI-библиотеку duckdbk: ${e.message}",
                    e,
                )
            }
        }
    }

    private external fun nativeInit(): Int

    private external fun nativeLastError(): String?

    private external fun nativeVersion(): String?

    private external fun nativeOpen(path: String?, readOnly: Boolean): Long

    private external fun nativeClose(handle: Long)

    private external fun nativeExecute(handle: Long, sql: String): Long

    private external fun nativePrepare(handle: Long, sql: String): Long

    private external fun nativeParameterCount(stmt: Long): Int

    private external fun nativeDestroyStatement(stmt: Long)

    private external fun nativeBindNull(stmt: Long, index: Int)

    private external fun nativeBindBool(stmt: Long, index: Int, value: Boolean)

    private external fun nativeBindInt8(stmt: Long, index: Int, value: Byte)

    private external fun nativeBindInt16(stmt: Long, index: Int, value: Short)

    private external fun nativeBindInt32(stmt: Long, index: Int, value: Int)

    private external fun nativeBindInt64(stmt: Long, index: Int, value: Long)

    private external fun nativeBindFloat(stmt: Long, index: Int, value: Float)

    private external fun nativeBindDouble(stmt: Long, index: Int, value: Double)

    private external fun nativeBindText(stmt: Long, index: Int, value: String)

    private external fun nativeBindBytes(stmt: Long, index: Int, value: ByteArray)

    private external fun nativeBindTimestampMicros(stmt: Long, index: Int, micros: Long)

    private external fun nativeBindUuid(stmt: Long, index: Int, bytes16: ByteArray)

    /** `long[2]`: {cursor, rowsChanged}. */
    private external fun nativeExecutePrepared(stmt: Long): LongArray?

    private external fun nativeCursorColumnCount(cursor: Long): Int

    private external fun nativeCursorColumnName(cursor: Long, index: Int): String?

    private external fun nativeCursorColumnType(cursor: Long, index: Int): Int

    private external fun nativeCursorNext(cursor: Long): Boolean

    private external fun nativeCursorIsNull(cursor: Long, index: Int): Boolean

    private external fun nativeCursorGetBoolean(cursor: Long, index: Int): Boolean

    private external fun nativeCursorGetLong(cursor: Long, index: Int): Long

    private external fun nativeCursorGetDouble(cursor: Long, index: Int): Double

    private external fun nativeCursorGetString(cursor: Long, index: Int): String?

    private external fun nativeCursorGetBytes(cursor: Long, index: Int): ByteArray?

    private external fun nativeCursorGetTimestampMicros(cursor: Long, index: Int): Long

    private external fun nativeCursorGetUuid(cursor: Long, index: Int): ByteArray?

    private external fun nativeCursorClose(cursor: Long)

    private var inited = false

    /**
     * Переводит Java-исключения из JNI в иерархию [DuckDbException].
     *
     * C-мост бросает `IllegalStateException` для ошибок SQL и
     * `IllegalArgumentException` для ошибок bind. Без перевода вызывающий
     * получал бы `IllegalStateException` вместо
     * [DuckDbConstraintException], и адаптер kdatabase не смог бы
     * отличить нарушение ограничения от синтаксической ошибки — а от
     * этого зависит, повторять запрос или нет.
     */
    private inline fun <T> wrapJni(block: () -> T): T = try {
        block()
    } catch (e: IllegalStateException) {
        throw DuckDbSqlException(e.message ?: "ошибка SQL", null)
    } catch (e: IllegalArgumentException) {
        throw DuckDbException(e.message ?: "ошибка параметра запроса", e)
    }

    private fun ensureInit(): Int {
        if (!inited) {
            ensureLoaded()
        }
        val rc = nativeInit()
        inited = true
        return rc
    }

    override fun init(): Int = ensureInit()

    override fun lastError(): String = runCatching { nativeLastError() }.getOrNull() ?: ""

    override fun version(): String? {
        ensureLoaded()
        return nativeVersion()
    }

    override fun open(path: String?, readOnly: Boolean): Long {
        ensureLoaded()
        val handle = nativeOpen(path, readOnly)
        if (nativeInit() != 0) {
            throw DuckDbLoadException("DuckDB не инициализировалась: ${lastError()}")
        }
        if (handle == 0L) {
            throw DuckDbConnectionException(
                "не удалось открыть базу ${path ?: ":memory:"}: ${lastError()}",
            )
        }
        return handle
    }

    override fun close(handle: Long) = nativeClose(handle)

    override fun execute(handle: Long, sql: String): Long = wrapJni {
        nativeExecute(handle, sql)
    }

    override fun prepare(handle: Long, sql: String): Long = wrapJni {
        nativePrepare(handle, sql)
    }

    override fun parameterCount(stmt: Long): Int = nativeParameterCount(stmt)

    override fun destroyStatement(stmt: Long) = nativeDestroyStatement(stmt)

    override fun bindNull(stmt: Long, index: Int) = nativeBindNull(stmt, index)

    override fun bindBool(stmt: Long, index: Int, value: Boolean) = nativeBindBool(stmt, index, value)

    override fun bindInt8(stmt: Long, index: Int, value: Byte) = nativeBindInt8(stmt, index, value)

    override fun bindInt16(stmt: Long, index: Int, value: Short) = nativeBindInt16(stmt, index, value)

    override fun bindInt32(stmt: Long, index: Int, value: Int) = nativeBindInt32(stmt, index, value)

    override fun bindInt64(stmt: Long, index: Int, value: Long) = nativeBindInt64(stmt, index, value)

    override fun bindFloat(stmt: Long, index: Int, value: Float) = nativeBindFloat(stmt, index, value)

    override fun bindDouble(stmt: Long, index: Int, value: Double) = nativeBindDouble(stmt, index, value)

    override fun bindText(stmt: Long, index: Int, value: String) = nativeBindText(stmt, index, value)

    override fun bindBytes(stmt: Long, index: Int, value: ByteArray) = nativeBindBytes(stmt, index, value)

    override fun bindTimestampMicros(stmt: Long, index: Int, micros: Long) =
        nativeBindTimestampMicros(stmt, index, micros)

    override fun bindUuid(stmt: Long, index: Int, bytes16: ByteArray) {
        require(bytes16.size == 16) { "UUID должен быть 16 байт, получено ${bytes16.size}" }
        nativeBindUuid(stmt, index, bytes16)
    }

    override fun executePrepared(stmt: Long): ExecuteResult = wrapJni {
        val r = nativeExecutePrepared(stmt)
            ?: throw DuckDbException("executePrepared: пустой результат")
        ExecuteResult(r[0], r[1])
    }

    override fun cursorColumnCount(cursor: Long): Int = nativeCursorColumnCount(cursor)

    override fun cursorColumnName(cursor: Long, index: Int): String =
        nativeCursorColumnName(cursor, index) ?: ""

    override fun cursorColumnType(cursor: Long, index: Int): DuckDbType =
        when (nativeCursorColumnType(cursor, index)) {
            0 -> DuckDbType.NULL
            1 -> DuckDbType.BIT
            2 -> DuckDbType.TINYINT
            3 -> DuckDbType.SMALLINT
            4 -> DuckDbType.INTEGER
            5 -> DuckDbType.BIGINT
            6 -> DuckDbType.FLOAT
            7 -> DuckDbType.DOUBLE
            8 -> DuckDbType.DOUBLE
            9 -> DuckDbType.HUGEINT
            10 -> DuckDbType.DECIMAL
            11 -> DuckDbType.VARCHAR
            12 -> DuckDbType.VARCHAR
            13 -> DuckDbType.VARCHAR
            14 -> DuckDbType.DATE
            15 -> DuckDbType.TIME
            16 -> DuckDbType.TIMESTAMP
            17 -> DuckDbType.BLOB
            18 -> DuckDbType.BLOB
            19 -> DuckDbType.BLOB
            20 -> DuckDbType.UUID
            else -> DuckDbType.OTHER
        }

    override fun cursorNext(cursor: Long): Boolean = nativeCursorNext(cursor)

    override fun cursorIsNull(cursor: Long, index: Int): Boolean = nativeCursorIsNull(cursor, index)

    override fun cursorGetBoolean(cursor: Long, index: Int): Boolean = nativeCursorGetBoolean(cursor, index)

    override fun cursorGetLong(cursor: Long, index: Int): Long = nativeCursorGetLong(cursor, index)

    override fun cursorGetDouble(cursor: Long, index: Int): Double = nativeCursorGetDouble(cursor, index)

    override fun cursorGetString(cursor: Long, index: Int): String = nativeCursorGetString(cursor, index) ?: ""

    override fun cursorGetBytes(cursor: Long, index: Int): ByteArray =
        nativeCursorGetBytes(cursor, index) ?: ByteArray(0)

    override fun cursorGetTimestampMicros(cursor: Long, index: Int): Long =
        nativeCursorGetTimestampMicros(cursor, index)

    override fun cursorGetUuid(cursor: Long, index: Int): ByteArray =
        nativeCursorGetUuid(cursor, index) ?: ByteArray(16)

    override fun cursorClose(cursor: Long) = nativeCursorClose(cursor)
}

internal actual fun openNative(): DuckDbNative = SharedDuckDb()
