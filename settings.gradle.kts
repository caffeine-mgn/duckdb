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
        mavenLocal()
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
