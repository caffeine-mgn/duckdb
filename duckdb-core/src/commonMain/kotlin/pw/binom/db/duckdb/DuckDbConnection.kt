package pw.binom.db.duckdb

/**
 * Соединение с DuckDB.
 *
 * ## Блокирующий по природе
 *
 * DuckDB — встроенный движок: `duckdb_query` считает на месте, в том же
 * процессе. Сетевого ожидания нет, поэтому методы синхронные. Поток
 * освобождается не «в ожидании», а по завершении запроса — оборачивать это
 * в `suspend` значило бы предложить корутину там, где её нечего ждать.
 *
 * Адаптер к `pw.binom.db` объявляет свои методы `suspend` (так требует
 * контракт) и вызывает эти — прямо, без `runBlocking`: блокировать нечего,
 * кроме собственных вычислений движка.
 *
 * ## Одно соединение — один поток
 *
 * DuckDB допускает несколько соединений на одну базу из разных потоков, но
 * одно соединение не потокобезопасно. Класс это не защищает мьютексом:
 * дешевле и честнее потребовать, чтобы вызывающий не делил его между
 * потоками (для этого есть пул в `duckdb-kdatabase`).
 */
class DuckDbConnection internal constructor(
    private val native: DuckDbNative,
    private val handle: Long,
) : AutoCloseable {

    private var closed = false

    private fun checkOpen() {
        check(!closed) { "соединение закрыто" }
    }

    val isClosed: Boolean get() = closed

    /** Версия движка, к которой подключено соединение. */
    fun engineVersion(): String? = native.version()

    /**
     * Исполняет SQL без параметров.
     *
     * Возвращает число изменённых строк. Для запросов, возвращающих строки,
     * используйте [query] — здесь результат отбрасывается.
     */
    fun execute(sql: String): Long {
        checkOpen()
        return try {
            native.execute(handle, sql)
        } catch (e: DuckDbSqlException) {
            throw classifySqlError(e.message ?: "", sql)
        }
    }

    /** Исполняет SELECT и возвращает курсор. */
    fun query(sql: String): DuckDbResultSet = prepare(sql).use { it.executeQuery() }

    fun prepare(sql: String): DuckDbPreparedStatement {
        checkOpen()
        val stmt = try {
            native.prepare(handle, sql)
        } catch (e: DuckDbSqlException) {
            throw classifySqlError(e.message ?: "", sql)
        }
        return DuckDbPreparedStatement(native, stmt, sql)
    }

    override fun close() {
        if (closed) return
        closed = true
        native.close(handle)
    }

    companion object {
        /** Версия движка из загруженной библиотеки; `null`, если не загрузилась. */
        fun libraryVersion(): String? = openNative().version()

        /** Текст последней ошибки загрузки — для диагностики. */
        fun loadError(): String = openNative().lastError()

        /**
         * Открывает соединение по настройкам.
         *
         * Бросает [DuckDbLoadException], если библиотека не загрузилась, и
         * [DuckDbConnectionException], если движок не смог открыть базу
         * (нет файла, нет прав, файл — не база DuckDB).
         */
        fun open(config: DuckDbConfig = DuckDbConfig()): DuckDbConnection {
            val native = openNative()
            val handle = try {
                native.open(config.path, config.readOnly)
            } catch (e: DuckDbException) {
                throw e
            } catch (e: Throwable) {
                throw DuckDbConnectionException("не удалось открыть базу: ${config.path ?: ":memory:"}", e)
            }
            return DuckDbConnection(native, handle)
        }

        /** База в памяти — самый частый режим для аналитики. */
        fun openInMemory(): DuckDbConnection = open(DuckDbConfig())
    }
}
