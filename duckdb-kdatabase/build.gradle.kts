plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

group = "pw.binom.db"
version = rootProject.property("version") as String

/*
 * Адаптер к контрактам pw.binom.db.
 *
 * Никакого нативного кода: модуль только переводит SqlValue -> DuckDbValue
 * и блокирующие вызовы ядра -> suspend-контракты kdatabase. Поэтому он
 * собирается на любой хост и не тянет kn-clang.
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
                api(project(":duckdb-core"))
                api(libs.kdatabase)
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
