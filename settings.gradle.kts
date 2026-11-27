pluginManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        /*
         * mavenLocal НЕ участвует в резолве.
         *
         * Проверка «собирается ли проект с опубликованными kdatabase:0.1.0
         * и korm:0.1.0» имеет смысл только если локальная сборка не может
         * подсунуть свои артефакты. С mavenLocal в списке Gradle молча
         * возьмёт /root/.m2, и зелёный прогон ничего не докажет.
         *
         * Для локальной разработки самих kdatabase/korm подключать точечно:
         *   --init-script <(echo 'settingsEvaluated { ... mavenLocal() ... }')
         * или отдельным профилем. Постоянно держать его здесь — значит
         * навсегда потерять возможность проверить публикацию.
         */
        google()
        mavenCentral()

    }
}

rootProject.name = "duckdb-kmp"

/*
 * Три модуля, разделение — по потребителю, а не по слоям кода:
 *
 *  duckdb-core      — сам клиент. Контракты, типы SqlValue/ColumnType,
 *                     управление соединением/запросами/курсором.
 *                     Зависимостей от kdatabase нет: библиотека обязана
 *                     собираться и работать без ORM-стеков.
 *  duckdb-kdatabase — адаптер к pw.binom.db: Connection, PreparedStatement,
 *                     ResultSet, пул. Тонкий: переводит SqlValue в вызовы ядра.
 *  duckdb-korm      — адаптер к korm: DatabaseInfo/SqlDialect, чтобы ORM
 *                     генерировала SQL под DuckDB.
 *
 * Такое разделение даёт потребителю выбор: взять голый клиент, взять его
 * с контрактами kdatabase или взять с ORM — и не тянуть лишнего.
 */
include(":duckdb-core")
include(":duckdb-kdatabase")
include(":duckdb-korm")
