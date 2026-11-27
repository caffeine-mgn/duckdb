package pw.binom.db.duckdb

/**
 * Загрузка JNI-библиотеки `duckdbk` — разная для JVM и Android.
 *
 * ## Почему expect, а не общий код
 *
 * На JVM библиотеку надо **распаковать из jar в файл** и вызвать
 * `System.load(path)`: у `System.load` нет варианта «из байтов».
 *
 * На Android тот же трюк не работает: `.so`, извлечённая из jar в
 * произвольный каталог, не грузится — линкер работает в namespace
 * приложения и SELinux запрещает `dlopen` оттуда. Единственный рабочий
 * путь — `jniLibs` в AAR, откуда система сама кладёт библиотеку в
 * `lib/<abi>/` и `System.loadLibrary` её находит.
 *
 * Два разных механизма — два actual'а, один контракт: после вызова
 * нативные методы класса [SharedDuckDb] готовы.
 */
internal expect fun loadDuckDbkLibrary()

/** Имя файла библиотеки без префикса `lib` и расширения. */
internal const val DUCKDBK_LIB_NAME: String = "duckdbk"

/**
 * Каталог ресурсов с нативными библиотеками.
 *
 * Имя — `KonanTarget.name` (snake_case: `linux_x64`, `linux_arm64`,
 * `macos_arm64`, `mingw_x64`), потому что задачи копирования в
 * build.gradle.kts складывают .so именно туда. `presetName` (`linuxX64`)
 * даёт другое имя и «в jar нет нативной библиотеки» при живой .so рядом.
 */
internal fun nativeResourceDir(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val aarch64 = arch.contains("aarch64") || arch.contains("arm64")
    return when {
        os.contains("win") -> "mingw_x64"
        os.contains("mac") || os.contains("darwin") ->
            if (aarch64) "macos_arm64" else "macos_x64"
        else -> if (aarch64) "linux_arm64" else "linux_x64"
    }
}

/** Расширение разделяемой библиотеки по ОС. */
internal fun nativeLibraryExtension(): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("win") -> "dll"
        os.contains("mac") || os.contains("darwin") -> "dylib"
        else -> "so"
    }
}
