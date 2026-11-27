package pw.binom.db.duckdb

/**
 * Android: `System.loadLibrary` — без извлечения из jar.
 *
 * На Android `.so`, распакованная из jar в произвольный каталог, **не
 * грузится**: линкер работает в namespace приложения, и SELinux не даёт
 * `dlopen` оттуда. Работает только штатный путь — библиотека лежит в
 * `jni/<abi>/` внутри AAR, система при установке кладёт её в
 * `lib/<abi>/`, и `System.loadLibrary("duckdbk")` её находит.
 *
 * Именно поэтому `androidTarget` публикует AAR с `jniLibs`, а не
 * переиспользует JVM-механизм с распаковкой.
 */
internal actual fun loadDuckDbkLibrary() {
    System.loadLibrary(DUCKDBK_LIB_NAME)
}
