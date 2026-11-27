plugins {
    alias(libs.plugins.kotlin.multiplatform)
    /*
     * Плагин сериализации нужен хотя бы в тестах: korm работает через
     * KSerializer, и `Person.serializer()` без плагина не генерируется.
     */
    alias(libs.plugins.kotlin.serialization)
}

group = "pw.binom.db"
version = rootProject.property("version") as String

/*
 * Адаптер к korm.
 *
 * Нативный код не нужен: модуль даёт ORM `DatabaseInfo`/`SqlDialect` под
 * DuckDB, чтобы она генерировала правильный SQL. Всё остальное — работа
 * duckdb-kdatabase.
 */
kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    linuxX64()
    linuxArm64()
    macosX64()
    macosArm64()
    mingwX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":duckdb-kdatabase"))
                api(libs.korm)
                api(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}
