package pw.binom.db.duckdb

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import pw.binom.db.duckdb.internal.DDBT_COL_BIGINT
import pw.binom.db.duckdb.internal.DDBT_COL_BINARY
import pw.binom.db.duckdb.internal.DDBT_COL_BIT
import pw.binom.db.duckdb.internal.DDBT_COL_CHAR
import pw.binom.db.duckdb.internal.DDBT_COL_DATE
import pw.binom.db.duckdb.internal.DDBT_COL_DECIMAL
import pw.binom.db.duckdb.internal.DDBT_COL_DOUBLE
import pw.binom.db.duckdb.internal.DDBT_COL_FLOAT
import pw.binom.db.duckdb.internal.DDBT_COL_INTEGER
import pw.binom.db.duckdb.internal.DDBT_COL_LONGVARBINARY
import pw.binom.db.duckdb.internal.DDBT_COL_LONGVARCHAR
import pw.binom.db.duckdb.internal.DDBT_COL_NULL
import pw.binom.db.duckdb.internal.DDBT_COL_NUMERIC
import pw.binom.db.duckdb.internal.DDBT_COL_OTHER
import pw.binom.db.duckdb.internal.DDBT_COL_REAL
import pw.binom.db.duckdb.internal.DDBT_COL_SMALLINT
import pw.binom.db.duckdb.internal.DDBT_COL_TIME
import pw.binom.db.duckdb.internal.DDBT_COL_TIMESTAMP
import pw.binom.db.duckdb.internal.DDBT_COL_TINYINT
import pw.binom.db.duckdb.internal.DDBT_COL_UUID
import pw.binom.db.duckdb.internal.DDBT_COL_VARBINARY
import pw.binom.db.duckdb.internal.DDBT_COL_VARCHAR
import pw.binom.db.duckdb.internal.DDBT_OK
import pw.binom.db.duckdb.internal.ddbt_bind_blob
import pw.binom.db.duckdb.internal.ddbt_bind_bool
import pw.binom.db.duckdb.internal.ddbt_bind_double
import pw.binom.db.duckdb.internal.ddbt_bind_float
import pw.binom.db.duckdb.internal.ddbt_bind_int16
import pw.binom.db.duckdb.internal.ddbt_bind_int32
import pw.binom.db.duckdb.internal.ddbt_bind_int64
import pw.binom.db.duckdb.internal.ddbt_bind_int8
import pw.binom.db.duckdb.internal.ddbt_bind_null
import pw.binom.db.duckdb.internal.ddbt_bind_text
import pw.binom.db.duckdb.internal.ddbt_bind_timestamp_micros
import pw.binom.db.duckdb.internal.ddbt_bind_uuid
import pw.binom.db.duckdb.internal.ddbt_close
import pw.binom.db.duckdb.internal.ddbt_cursor_close
import pw.binom.db.duckdb.internal.ddbt_cursor_column_count
import pw.binom.db.duckdb.internal.ddbt_cursor_column_name
import pw.binom.db.duckdb.internal.ddbt_cursor_column_type
import pw.binom.db.duckdb.internal.ddbt_cursor_get_blob
import pw.binom.db.duckdb.internal.ddbt_cursor_get_bool
import pw.binom.db.duckdb.internal.ddbt_cursor_get_double
import pw.binom.db.duckdb.internal.ddbt_cursor_get_int64
import pw.binom.db.duckdb.internal.ddbt_cursor_get_double
import pw.binom.db.duckdb.internal.ddbt_cursor_get_text
import pw.binom.db.duckdb.internal.ddbt_cursor_get_timestamp_micros
import pw.binom.db.duckdb.internal.ddbt_cursor_get_uuid
import pw.binom.db.duckdb.internal.ddbt_cursor_is_null
import pw.binom.db.duckdb.internal.ddbt_cursor_next
import pw.binom.db.duckdb.internal.ddbt_destroy_statement
import pw.binom.db.duckdb.internal.ddbt_execute
import pw.binom.db.duckdb.internal.ddbt_execute_prepared
import pw.binom.db.duckdb.internal.ddbt_free
import pw.binom.db.duckdb.internal.ddbt_init
import pw.binom.db.duckdb.internal.ddbt_last_error
import pw.binom.db.duckdb.internal.ddbt_nparams
import pw.binom.db.duckdb.internal.ddbt_open
import pw.binom.db.duckdb.internal.ddbt_prepare
import pw.binom.db.duckdb.internal.ddbt_version

/**
 * Реализация через cinterop: прямой вызов C-шва, без прослоек.
 *
 * Вся работа с памятью локальная: строки из шва приходят malloc-копиями,
 * читаются в Kotlin-строку и тут же освобождаются. Наружу указатели не
 * уходят.
 */
@OptIn(ExperimentalForeignApi::class)
internal class NativeDuckDbCInterop : DuckDbNative {

    private fun fail(what: String, code: Int): Nothing {
        throw DuckDbLoadException("$what: код $code, ${lastError()}")
    }

    private fun sqlFail(code: Int, outError: CPointer<ByteVar>?): Nothing {
        val msg = outError?.toKString() ?: lastError()
        outError?.let { ddbt_free(it) }
        throw DuckDbSqlException(msg)
    }

    override fun init(): Int = ddbt_init()

    override fun lastError(): String = ddbt_last_error()?.toKString() ?: ""

    override fun version(): String? = memScoped {
        val p = ddbt_version() ?: return@memScoped null
        try {
            p.toKString()
        } finally {
            ddbt_free(p)
        }
    }

    override fun open(path: String?, readOnly: Boolean): Long = memScoped {
        val handle = alloc<LongVar>()
        val flag = if (readOnly) 1 else 0
        val rc = if (path == null) {
            ddbt_open(null, flag, handle.ptr)
        } else {
            ddbt_open(path.cstr.ptr, flag, handle.ptr)
        }
        if (rc != DDBT_OK) fail("ddbt_open", rc)
        handle.value
    }

    override fun close(handle: Long) = ddbt_close(handle)

    override fun execute(handle: Long, sql: String): Long = memScoped {
        val rowsChanged = alloc<LongVar>()
        val pErr = allocPointerTo<ByteVar>()
        val rc = ddbt_execute(handle, sql.cstr.ptr, rowsChanged.ptr, pErr.ptr)
        if (rc != DDBT_OK) {
            val msg = pErr.value?.toKString() ?: lastError()
            pErr.value?.let { ddbt_free(it) }
            throw DuckDbSqlException(msg)
        }
        rowsChanged.value
    }

    override fun prepare(handle: Long, sql: String): Long = memScoped {
        val stmt = alloc<LongVar>()
        val pErr = allocPointerTo<ByteVar>()
        val rc = ddbt_prepare(handle, sql.cstr.ptr, stmt.ptr, pErr.ptr)
        if (rc != DDBT_OK) {
            val msg = pErr.value?.toKString() ?: lastError()
            pErr.value?.let { ddbt_free(it) }
            throw DuckDbSqlException(msg)
        }
        stmt.value
    }

    override fun parameterCount(stmt: Long): Int = ddbt_nparams(stmt)

    override fun destroyStatement(stmt: Long) = ddbt_destroy_statement(stmt)

    override fun bindNull(stmt: Long, index: Int) = checkBind(ddbt_bind_null(stmt, index))

    override fun bindBool(stmt: Long, index: Int, value: Boolean) =
        checkBind(ddbt_bind_bool(stmt, index, if (value) 1 else 0))

    override fun bindInt8(stmt: Long, index: Int, value: Byte) = checkBind(ddbt_bind_int8(stmt, index, value))

    override fun bindInt16(stmt: Long, index: Int, value: Short) = checkBind(ddbt_bind_int16(stmt, index, value))

    override fun bindInt32(stmt: Long, index: Int, value: Int) = checkBind(ddbt_bind_int32(stmt, index, value))

    override fun bindInt64(stmt: Long, index: Int, value: Long) = checkBind(ddbt_bind_int64(stmt, index, value))

    override fun bindFloat(stmt: Long, index: Int, value: Float) = checkBind(ddbt_bind_float(stmt, index, value))

    override fun bindDouble(stmt: Long, index: Int, value: Double) = checkBind(ddbt_bind_double(stmt, index, value))

    override fun bindText(stmt: Long, index: Int, value: String) = memScoped {
        checkBind(ddbt_bind_text(stmt, index, value.cstr.ptr, value.encodeToByteArray().size))
    }

    override fun bindBytes(stmt: Long, index: Int, value: ByteArray) = memScoped {
        val arr = allocArray<UByteVar>(if (value.isEmpty()) 1 else value.size)
        for (i in value.indices) arr[i] = value[i].toUByte()
        checkBind(ddbt_bind_blob(stmt, index, arr, value.size))
    }

    override fun bindTimestampMicros(stmt: Long, index: Int, micros: Long) =
        checkBind(ddbt_bind_timestamp_micros(stmt, index, micros))

    override fun bindUuid(stmt: Long, index: Int, bytes16: ByteArray) = memScoped {
        require(bytes16.size == 16) { "UUID должен быть 16 байт, получено ${bytes16.size}" }
        val arr = allocArray<UByteVar>(16)
        for (i in 0 until 16) arr[i] = bytes16[i].toUByte()
        checkBind(ddbt_bind_uuid(stmt, index, arr))
    }

    private fun checkBind(rc: Int) {
        if (rc != DDBT_OK) throw DuckDbException("bind: код $rc, ${lastError()}")
    }

    override fun executePrepared(stmt: Long): ExecuteResult = memScoped {
        val cursor = alloc<LongVar>()
        val rows = alloc<LongVar>()
        val pErr = allocPointerTo<ByteVar>()
        val rc = ddbt_execute_prepared(stmt, cursor.ptr, rows.ptr, pErr.ptr)
        if (rc != DDBT_OK) {
            val msg = pErr.value?.toKString() ?: lastError()
            pErr.value?.let { ddbt_free(it) }
            throw DuckDbSqlException(msg)
        }
        ExecuteResult(cursor.value, rows.value)
    }

    override fun cursorColumnCount(cursor: Long): Int = ddbt_cursor_column_count(cursor)

    override fun cursorColumnName(cursor: Long, index: Int): String = memScoped {
        val p = ddbt_cursor_column_name(cursor, index) ?: return@memScoped ""
        try {
            p.toKString()
        } finally {
            ddbt_free(p)
        }
    }

    override fun cursorColumnType(cursor: Long, index: Int): DuckDbType =
        when (ddbt_cursor_column_type(cursor, index)) {
            DDBT_COL_NULL -> DuckDbType.NULL
            DDBT_COL_BIT -> DuckDbType.BIT
            DDBT_COL_TINYINT -> DuckDbType.TINYINT
            DDBT_COL_SMALLINT -> DuckDbType.SMALLINT
            DDBT_COL_INTEGER -> DuckDbType.INTEGER
            DDBT_COL_BIGINT -> DuckDbType.BIGINT
            DDBT_COL_FLOAT -> DuckDbType.FLOAT
            DDBT_COL_REAL -> DuckDbType.DOUBLE
            DDBT_COL_DOUBLE -> DuckDbType.DOUBLE
            DDBT_COL_NUMERIC -> DuckDbType.HUGEINT
            DDBT_COL_DECIMAL -> DuckDbType.DECIMAL
            DDBT_COL_CHAR, DDBT_COL_VARCHAR, DDBT_COL_LONGVARCHAR -> DuckDbType.VARCHAR
            DDBT_COL_DATE -> DuckDbType.DATE
            DDBT_COL_TIME -> DuckDbType.TIME
            DDBT_COL_TIMESTAMP -> DuckDbType.TIMESTAMP
            DDBT_COL_BINARY, DDBT_COL_VARBINARY, DDBT_COL_LONGVARBINARY -> DuckDbType.BLOB
            DDBT_COL_UUID -> DuckDbType.UUID
            DDBT_COL_OTHER -> DuckDbType.OTHER
            else -> DuckDbType.OTHER
        }

    override fun cursorNext(cursor: Long): Boolean = ddbt_cursor_next(cursor) > 0

    override fun cursorIsNull(cursor: Long, index: Int): Boolean = ddbt_cursor_is_null(cursor, index) != 0

    override fun cursorGetBoolean(cursor: Long, index: Int): Boolean = memScoped {
        val out = alloc<IntVar>()
        if (ddbt_cursor_get_bool(cursor, index, out.ptr) != DDBT_OK) {
            throw DuckDbException("getBoolean: ${lastError()}")
        }
        out.value != 0
    }

    override fun cursorGetLong(cursor: Long, index: Int): Long = memScoped {
        val out = alloc<LongVar>()
        if (ddbt_cursor_get_int64(cursor, index, out.ptr) != DDBT_OK) {
            throw DuckDbException("getLong: ${lastError()}")
        }
        out.value
    }

    override fun cursorGetDouble(cursor: Long, index: Int): Double = memScoped {
        val out = alloc<kotlinx.cinterop.DoubleVar>()
        if (ddbt_cursor_get_double(cursor, index, out.ptr) != DDBT_OK) {
            throw DuckDbException("getDouble: ${lastError()}")
        }
        out.value
    }

    override fun cursorGetString(cursor: Long, index: Int): String = memScoped {
        val pOut = allocPointerTo<ByteVar>()
        val pLen = alloc<IntVar>()
        if (ddbt_cursor_get_text(cursor, index, pOut.ptr, pLen.ptr) != DDBT_OK) {
            throw DuckDbException("getString: ${lastError()}")
        }
        val p = pOut.value ?: return@memScoped ""
        try {
            p.toKString()
        } finally {
            ddbt_free(p)
        }
    }

    override fun cursorGetBytes(cursor: Long, index: Int): ByteArray = memScoped {
        val pOut = allocPointerTo<kotlinx.cinterop.COpaquePointerVar>()
        val pLen = alloc<IntVar>()
        if (ddbt_cursor_get_blob(cursor, index, pOut.ptr.reinterpret(), pLen.ptr) != DDBT_OK) {
            throw DuckDbException("getBytes: ${lastError()}")
        }
        val p = pOut.value ?: return@memScoped ByteArray(0)
        try {
            val n = pLen.value
            val dst = ByteArray(n)
            val src = p.reinterpret<ByteVar>()
            for (i in 0 until n) dst[i] = src[i]
            dst
        } finally {
            ddbt_free(p)
        }
    }

    override fun cursorGetTimestampMicros(cursor: Long, index: Int): Long = memScoped {
        val out = alloc<LongVar>()
        if (ddbt_cursor_get_timestamp_micros(cursor, index, out.ptr) != DDBT_OK) {
            throw DuckDbException("getInstant: ${lastError()}")
        }
        out.value
    }

    override fun cursorGetUuid(cursor: Long, index: Int): ByteArray = memScoped {
        val arr = allocArray<UByteVar>(16)
        if (ddbt_cursor_get_uuid(cursor, index, arr) != DDBT_OK) {
            throw DuckDbException("getUuid: ${lastError()}")
        }
        ByteArray(16) { arr[it].toByte() }
    }

    override fun cursorClose(cursor: Long) = ddbt_cursor_close(cursor)
}

internal actual fun openNative(): DuckDbNative = NativeDuckDbCInterop()
