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

tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
        events("started", "passed", "failed", "skipped", "standardOut", "standardError")
    }
}
