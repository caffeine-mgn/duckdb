package pw.binom.db.duckdb

import java.io.File

/**
 * JVM: извлекаем `.so` из jar во временный каталог и грузим по пути.
 *
 * `System.load` принимает только файл на диске — варианта «загрузить из
 * байтов» в JVM нет. Поэтому библиотека распаковывается в
 * `java.io.tmpdir` под уникальным каталогом и грузится оттуда; каталог и
 * файл помечаются `deleteOnExit`.
 */
internal actual fun loadDuckDbkLibrary() {
    val ext = nativeLibraryExtension()
    val resource = "/${nativeResourceDir()}/lib$DUCKDBK_LIB_NAME.$ext"
    val stream = SharedDuckDb::class.java.getResourceAsStream(resource)
        ?: error(
            "в jar нет нативной библиотеки $resource. " +
                "Соберите JVM-артефакт с kn-clang (./gradlew :duckdb-core:jvmJar): " +
                "он кладёт .so в ресурсы.",
        )
    stream.use { input ->
        val dir = createTempDir(prefix = "duckdbk-")
        dir.deleteOnExit()
        val out = File(dir, "lib$DUCKDBK_LIB_NAME.$ext")
        out.deleteOnExit()
        out.outputStream().use { input.copyTo(it) }
        System.load(out.absolutePath)
    }
}

private fun createTempDir(prefix: String): File =
    java.nio.file.Files.createTempDirectory(prefix).toFile()
