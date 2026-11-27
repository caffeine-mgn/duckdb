package pw.binom.db.duckdb

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Значение параметра запроса.
 *
 * Набор закрыт намеренно: `Any?` допускает объект, который драйвер не умеет
 * передать, и ошибка вылезает посреди запроса. Здесь неподдерживаемый тип —
 * ошибка компиляции у вызывающего.
 *
 * Совпадает по составу с `pw.binom.db.SqlValue` — это не случайность, а
 * условие дешёвого адаптера: `duckdb-kdatabase` переводит одно в другое
 * через `when` без потерь.
 */
sealed interface DuckDbValue {
    data object Null : DuckDbValue

    data class Text(val value: String) : DuckDbValue

    data class Bool(val value: Boolean) : DuckDbValue

    data class Int8(val value: Byte) : DuckDbValue

    data class Int16(val value: Short) : DuckDbValue

    data class Int32(val value: Int) : DuckDbValue

    data class Int64(val value: Long) : DuckDbValue

    data class Float32(val value: Float) : DuckDbValue

    data class Float64(val value: Double) : DuckDbValue

    data class Bytes(val value: ByteArray) : DuckDbValue {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Bytes && value.contentEquals(other.value))

        override fun hashCode(): Int = value.contentHashCode()
    }

    data class Time(val value: Instant) : DuckDbValue

    data class UuidValue(val value: Uuid) : DuckDbValue
}
