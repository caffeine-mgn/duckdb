plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kn.clang) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.android.library) apply false
}

group = "pw.binom.db"

/*
 * Версия: -Pversion=<тег> из CI, иначе SNAPSHOT. Gradle по умолчанию ставит
 * строку "unspecified", а не null, поэтому проверяем явно.
 */
val propVersion = findProperty("version") as String?
val envVersion = System.getenv("GITHUB_REF_NAME")?.removePrefix("v")
version = when {
    !propVersion.isNullOrBlank() && propVersion != "unspecified" -> propVersion
    !envVersion.isNullOrBlank() && envVersion != "unspecified" -> envVersion
    else -> "0.1.0-SNAPSHOT"
}

subprojects {
    group = rootProject.group
    version = rootProject.version
}

/*
 * Публикация в Maven Central — одна конфигурация на все модули.
 *
 * ## Почему здесь, а не в `gradle/publishing.gradle.kts`
 *
 * Apply-скрипт компилируется отдельно и **не видит типы плагинов** из
 * buildscript модуля: `com.vanniktech.maven.publish.MavenPublishBaseExtension`
 * из него не резолвится (`Unresolved reference 'vanniktech'`). Проверено.
 * В корневом скрипте классы плагинов есть — они попали в classpath через
 * `alias(...) apply false` выше, поэтому `configure<T>` работает.
 *
 * ## Почему `withPlugin`, а не прямой `configure`
 *
 * Конфигурация срабатывает только у модулей, которые реально применили
 * vanniktech. Модуль без него (или новый служебный) не упадёт.
 *
 * ## Координаты
 *
 * `pw.binom.db:<имя каталога модуля>` — `duckdb-core`, `duckdb-kdatabase`,
 * `duckdb-korm`. Имя артефакта = имя каталога, чтобы не держать третье
 * место, где его можно рассогласовать.
 */

/** Что различается у модулей — только описание в POM. */
val moduleDescriptions = mapOf(
    "duckdb-core" to "DuckDB for Kotlin Multiplatform: client with cinterop (native) and JNI (JVM/Android)",
    "duckdb-kdatabase" to "DuckDB adapter for pw.binom.db: Connection, PreparedStatement, ResultSet, pool",
    "duckdb-korm" to "DuckDB adapter for korm: DatabaseInfo, SqlDialect, Orm factory",
)

subprojects {
    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        apply(plugin = "org.jetbrains.dokka")

        /*
         * ВАЖНО: `name` и `version` читаются ЛЕНИВО, внутри configure.
         *
         * При `withPlugin` блок выполняется в момент применения плагина —
         * это раньше, чем проект получает окончательное имя. Проверено:
         * при жадном чтении все три модуля публиковались под
         * artifactId="publish", и Gradle предупреждал, что публикации
         * перетирают друг друга:
         *   Multiple publications with coordinates
         *   'pw.binom.db:publish-jvm:…' will overwrite each other
         */
        val moduleVersion = project.version.toString()
        val moduleDescription = moduleDescriptions[name] ?: "DuckDB for Kotlin Multiplatform"

        /*
         * Координаты задаются в afterEvaluate: на момент withPlugin свойство
         * `name` ещё не окончательное (проверено — artifactId получался
         * "publish", и все три модуля перетирали друг друга).
         */
        afterEvaluate {
            val finalName = name
            val finalVersion = version.toString()
            val finalDescription = moduleDescriptions[finalName] ?: "DuckDB for Kotlin Multiplatform"

            configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
                /*
                 * automaticRelease: портал Sonatype сам выпускает staging
                 * после успешной загрузки. Без флага артефакты висят в
                 * staging и ждут ручного «close + release» — а его ждут
                 * неделями.
                 *
                 * В 0.37.0 из API убран `SonatypeHost`: плагин работает
                 * только с Central Portal, и у `publishToMavenCentral`
                 * осталась одна сигнатура — `(automaticRelease: Boolean)`.
                 * Проверено javap по jar плагина.
                 */
                publishToMavenCentral(automaticRelease = true)

                /*
                 * Подпись. `skipSigning=true` нужен локально: без GPG-ключа
                 * `publishToMavenLocal` падает на подписи, а артефакт в
                 * mavenLocal нужен соседним репозиториям.
                 * В CI подпись обязательна и не отключается.
                 */
                if (providers.gradleProperty("skipSigning").orNull != "true") {
                    signAllPublications()
                }

                coordinates(
                    groupId = "pw.binom.db",
                    artifactId = finalName,
                    version = finalVersion,
                )

                pom {
                    name.set(finalName)
                    description.set(finalDescription)
                    url.set("https://github.com/caffeine-mgn/duckdb")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("subochev")
                            name.set("Anton Subochev")
                            email.set("caffeine.mgn@gmail.com")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/caffeine-mgn/duckdb.git")
                        developerConnection.set("scm:git:ssh://git@github.com/caffeine-mgn/duckdb.git")
                        url.set("https://github.com/caffeine-mgn/duckdb")
                    }
                }
            }
        }

        /*
         * Подпись через системный gpg — режим CI, тот же, что у ksqlite и
         * kn-clang: ключ импортирован в keyring, Gradle зовёт бинарь
         * `gpg`, имя ключа и пароль приходят свойствами.
         */
        if (providers.gradleProperty("signingUseGpg").orNull == "true") {
            pluginManager.withPlugin("signing") {
                extensions.configure<org.gradle.plugins.signing.SigningExtension>("signing") {
                    useGpgCmd()
                }
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
        events("started", "passed", "failed", "skipped", "standardOut", "standardError")
    }
}

/*
 * Apple-публикации только на macOS-хосте.
 *
 * Kotlin/Native объявляет Apple-таргеты везде, но собирает их **только на
 * маке**. На Linux `publishToMavenLocal` пытается сгенерировать metadata
 * для `macosArm64`/`iosArm64`/… и падает:
 *
 *   FileNotFoundException: build/libs/<mod>-macosArm64Main-….klib
 *
 * Плагин при этом не считает таргет отключённым — задача публикации есть,
 * а артефакта нет. Поэтому задачи публикации Apple-таргетов отключаются
 * по хосту, а не по таргету.
 *
 * На маке условие всегда истинно — там публикуется всё, включая Apple.
 * Это и есть целевая схема: сборка и публикация целиком на macOS-раннере.
 */
val hostIsMac = org.jetbrains.kotlin.konan.target.HostManager.host.family ==
    org.jetbrains.kotlin.konan.target.Family.OSX

val applePublicationNames = listOf(
    "MacosX64", "MacosArm64",
    "IosX64", "IosArm64", "IosSimulatorArm64",
    "TvosX64", "TvosArm64", "TvosSimulatorArm64",
    "WatchosX64", "WatchosArm32", "WatchosArm64",
    "WatchosSimulatorArm64", "WatchosDeviceArm64",
)

subprojects {
    if (!hostIsMac) {
        tasks.matching { task ->
            val n = task.name
            n.startsWith("publish") &&
                applePublicationNames.any { n.contains(it) } &&
                (n.endsWith("PublicationToMavenLocal") || n.endsWith("PublicationToMavenRepository"))
        }.configureEach {
            enabled = false
        }
        tasks.matching { task ->
            task.name.startsWith("generateMetadataFileFor") &&
                applePublicationNames.any { task.name.contains(it) }
        }.configureEach {
            enabled = false
        }
    }
}
