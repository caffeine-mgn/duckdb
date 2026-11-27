package pw.binom.db.duckdb.adapter

import pw.binom.db.Connection
import pw.binom.db.pool.ConnectionFactory
import pw.binom.db.pool.ConnectionPool
import pw.binom.db.pool.ConnectionPoolImpl
import pw.binom.db.pool.PoolConfig
import pw.binom.db.duckdb.DuckDbConfig
import pw.binom.db.duckdb.DuckDbConnection

/**
 * Настройки пула DuckDB.
 *
 * Отдельно от [DuckDbConfig], потому что это два разных решения: чем
 * открывать базу и сколько таких открытий держать. Смешивать их в один
 * data-класс — значит заставлять менять конфиг базы при правке размера пула.
 */
data class DuckDbPoolConfig(
    val database: DuckDbConfig = DuckDbConfig(),
    /**
     * Размер пула. По умолчанию 1: DuckDB — аналитический движок, он сам
     * распараллеливает запрос внутри (`threads`), и несколько соединений
     * к одному файлу чаще мешают, чем помогают. Больше единицы нужно,
     * когда приложение само гоняет параллельные запросы и готово к
     * конкуренции за блокировку записи.
     */
    val maxConnections: Int = 1,
    val busyTimeoutMillis: Long = 5_000,
)

/**
 * Фабрика соединений DuckDB для `ConnectionPoolImpl`.
 *
 * ## Зачем отдельный класс, а не лямбда
 *
 * Пул умеет принимать голо лямбду `suspend () -> Connection`, но фабрика
 * говорит пулу больше: как открывать (in-memory или файл), какие настройки
 * применить при каждом открытии. Это решение живёт рядом с открытием, а не
 * разъезжается по вызывающему коду.
 *
 * ## Про настройки при открытии
 *
 * DuckDB не имеет `PRAGMA`-настройки на соединение в том смысле, в каком
 * она есть у SQLite: `SET` меняет состояние базы, а не соединения. Поэтому
 * `configure` пуст — настройки задаются при создании базы через
 * `duckdb_open_ext` (появится вместе с расширенным [DuckDbConfig]).
 */
internal class DuckDbConnectionFactory(
    private val config: DuckDbPoolConfig,
) : ConnectionFactory {

    override suspend fun open(): Connection =
        DuckDbKDatabaseConnection(DuckDbConnection.open(config.database))
}

/**
 * Открывает пул соединений DuckDB.
 *
 * Возвращённый пул закрывается через `close()` — он закроет все соединения
 * и освободит нативные ресурсы.
 */
fun openDuckDbPool(config: DuckDbPoolConfig = DuckDbPoolConfig()): ConnectionPool =
    ConnectionPoolImpl(
        config = PoolConfig(
            maxConnections = config.maxConnections,
            /* DuckDB валит запрос в ошибку при конфликте блокировки записи
             * мгновенно, в отличие от SQLite с busy_timeout. Пинг живости
             * оставляем штатный: `SELECT 1` DuckDB понимает. */
        ),
        factory = DuckDbConnectionFactory(config),
    )
