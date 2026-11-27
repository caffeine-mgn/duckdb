# duckdb-kmp — что сделано и что проверено

Дата: 27.11.2026. Репозиторий: `caffeine-mgn/duckdb`.

## Итог прогона

```
jvmTest      duckdb-core        7 тестов, 0 падений
jvmTest      duckdb-kdatabase   8 тестов, 0 падений
jvmTest      duckdb-korm        3 теста,  0 падений
linuxX64Test duckdb-core        6 тестов, 0 падений
                                24 всего, 0 skipped
```

Воспроизвести:

```bash
export DUCKDB_LIBRARY=/path/to/libduckdb.so
./gradlew allTests
```

## Ключевое архитектурное решение: dlopen вместо статической линковки

Официальные `libduckdb_static.a` собраны GCC 12+/glibc 2.31+. Kotlin/Native
линкует через замороженный sysroot: glibc 2.19, libstdc++ от GCC 8.3,
C++-заголовков нет. Проверены и отброшены:

- статика 2.0.1 (glibc и musl) — `explicit_bzero`, `_dl_find_object`,
  `std::__throw_bad_array_new_length`, `std::__cxx11::basic_ostringstream`;
- статика 1.5.6 — `ExtensionHelper::LoadAllExtensions`, `_Unwind_Resume`;
- подстановка libstdc++ из gcc 14 — следующая партия символов новее sysroot;
- `--start-group`, `-Bstatic`-обходы — то же.

Решение: C-шов не линкует DuckDB, а грузит `dlopen`/`LoadLibrary` и берёт
адреса функций через `dlsym`/`GetProcAddress`. Шов — чистый C, ни одного
C++ символа в klib.

## Найденные тестами особенности движка

| Что | Как проявилось | Где закрыто |
|---|---|---|
| Параметры DuckDB нумеруются с 1 | bind(0) → ошибка, bind(1) → работает | шов: `index + 1` в одной точке |
| `INSERT` возвращает result set с колонкой `Count` | `rows_changed` терялся, DML считался SELECT | шов: `duckdb_prepared_statement_type` |
| Курсор держал указатель на буфер statement | SIGABRT, мусор в колонке | шов: курсор владеет копией `duckdb_result` |
| UUID не читается через C value API | `value_uhugeint`/`value_string` дают нули | README: обход `u::VARCHAR` |
| `korm` генерирует `:name` | `Parser Error: at or near ":"` | адаптер: `SqlParameters` |

## Грабли инструментов (для скиллов)

1. **`addStatic`/`include`/`compileFile` понимают только RegularFile/
   Provider/String** (`Project.fileAnyWay`). Голый `java.io.File` и
   `Directory` **молча игнорируются** — симптом `undefined symbol` на весь
   набор функций или `header not found`.
2. **`include()` для cinterop** должен получить `.asFile`, а `compileFile`
   берёт `File` — путать нельзя.
3. **Каталог ресурсов JNI — `KonanTarget.name`** (snake_case:
   `linux_x64`), не `presetName` (`linuxX64`).
4. **`jvmTest` не видит `jvmProcessResources`** без явной зависимости.
5. **`linkerOpts` живёт на бинарнике**, не на компиляции; `-ldl` через
   `binaries.configureEach { linkerOpts(...) }`.
6. **Windows: нет `dlfcn.h`** — нужен `LoadLibrary`; вынесено в
   `ddbt_platform.h`.
7. **`korm` требует `@Id`** на сущности, иначе `IllegalArgumentException`.
8. **`-ldl` на MinGW** не нужен и ломает линковку.

## Не сделано

- push в `caffeine-mgn/duckdb` (креды есть, доступ проверен);
- публикация в Maven Central (workflow написан, тег не ставился);
- Apple-таргеты: только `compileKotlin*`, запуск на маке.
