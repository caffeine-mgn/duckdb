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

## Таргеты: полный список и что где проверено

| Семейство | Таргеты | Статус на Linux-хосте |
|---|---|---|
| JVM | `jvm` | ✅ 7 тестов зелёные |
| Android (JVM/ART) | `androidTarget` (AAR) | ✅ компилируется, AAR несёт 4 ABI |
| Linux | `linuxX64`, `linuxArm64` | ✅ x64: 6 тестов; arm64: компилируется |
| Windows | `mingwX64` | ✅ компилируется (кросс-линковка с Linux) |
| Android NDK | `androidNativeArm32/Arm64/X86/X64` | ✅ все четыре компилируются |
| macOS | `macosX64`, `macosArm64` | ⏭ SKIPPED — нужен macOS-хост |
| iOS | `iosX64`, `iosArm64`, `iosSimulatorArm64` | ⏭ SKIPPED — нужен macOS-хост |
| tvOS | `tvosX64`, `tvosArm64`, `tvosSimulatorArm64` | ⏭ SKIPPED — нужен macOS-хост |
| watchOS | `watchosX64/Arm32/Arm64/SimulatorArm64/DeviceArm64` | ⏭ SKIPPED — нужен macOS-хост |

Проверка AAR:

```bash
./gradlew :duckdb-core:assembleRelease
unzip -l duckdb-core/build/outputs/aar/duckdb-core-release.aar | grep 'jni/.*libduckdbk.so'
# ожидается 4 строки: arm64-v8a, armeabi-v7a, x86, x86_64
```

## Иерархия source sets

```
commonMain          контракты, типы, ошибки — вся логика
├── jvmSharedMain   интерфейс DuckDbNative + SharedDuckDb (JNI), общий для JVM и Android
│   ├── jvmMain     actual loadDuckDbkLibrary: распаковка .so из jar + System.load
│   └── androidMain actual loadDuckDbkLibrary: System.loadLibrary из jni/<abi>/
└── nativeMain      actual через cinterop
```

**Почему загрузка разная.** На JVM `System.load` умеет только файл на
диске — библиотека распаковывается из jar во временный каталог. На Android
этот трюк не работает: линкер в namespace приложения, SELinux не даёт
`dlopen` из произвольного каталога. Единственный путь — `jniLibs` в AAR.

## 🔴 Переименование Kotlin-класса ломает JNI молча

Найдено при добавлении Android: класс `JvmDuckDb` переименован в
`SharedDuckDb`, а C-мост продолжал экспортировать
`Java_pw_binom_db_duckdb_JvmDuckDb_*`. Компиляция зелёная, сборка
зелёная — падение только на первом вызове:

```
java.lang.UnsatisfiedLinkError
```

**Правило:** при переименовании класса с нативными методами —
`sed -i 's/Java_<старый путь>/Java_<новый путь>/g'` по всем `.c` файлам
моста, и сразу прогон тестов. JNI-имена не проверяются компилятором.

## CICD: всё на macOS-раннере

Схема как в ksqlite, но с одним отличием: весь прогон — на маке.

**Почему на маке.** Apple-таргеты (iOS, tvOS, watchOS, macOS) собираются
только на macOS-хосте. На Linux-раннере они SKIPPED, и «зелёный» прогон
там ничего не доказывает: половина заявленных таргетов не собирается, их
артефакты не публикуются.

Мак — полноценный KMP-хост: `linuxX64`, `linuxArm64`, `mingwX64`
компилируются на нём так же, как на ubuntu (klib хост-независим).
Android-таргеты тоже работают — kn-clang тянет bionic сам.

**Питфоллы мака:**

| Проблема | Решение |
|---|---|
| нет node → `actions/checkout` падает | checkout шеллом (`git init` + `fetch` + `checkout`) |
| нет Docker | контейнерные шаги недоступны |
| `linuxX64Test`/`mingwX64Test` не исполняются | только `compileKotlin*` |
| `macosX64Test` — Rosetta под вопросом | только `compileTestKotlinMacosX64` |

**Ворота публикации (4):**

1. тесты `jvmTest` + `macosArm64Test` с живой `libduckdb.dylib`;
2. сводка по XML: ноль тестов = ошибка (ворота не фиктивны);
3. компиляция всех не-macOS таргетов (Linux ×2, MinGW, Android NDK ×4,
   iOS ×3, tvOS ×2, watchOS ×2);
4. `.so` в JVM-jar (macOS-вариант) + AAR с четырьмя ABI.

## Публикация: конфигурация и её грабли

Плагин — `vanniktech` 0.37.0. Конфигурация POM/подписи одна на все
модули, в корневом `build.gradle.kts` через `subprojects { withPlugin }`.

**Питфоллы, каждый стоил прогона:**

| Симптом | Причина | Решение |
|---|---|---|
| `Unresolved reference 'vanniktech'` в `gradle/publishing.gradle.kts` | apply-скрипт компилируется отдельно и не видит типы плагинов модуля | конфигурация в корневом скрипте, где `alias(...) apply false` положил классы в classpath |
| `Unresolved reference 'SonatypeHost'` | в 0.37.0 удалён: только Central Portal | `publishToMavenCentral(automaticRelease = true)` — одна сигнатура |
| `description.set(description)` → `Unresolved reference 'set'` | локальная `val description` перекрыла `pom.description` | переименовать локальную в `moduleDescription` |
| все модули публикуются как `artifactId="publish"` | `name` прочитан в `withPlugin`, до присвоения имени проекту | координаты задавать в `afterEvaluate` |
| `FileNotFoundException: …-macosArm64Main-….klib` на Linux | Apple-klib не собираются, а публикация их требует | Apple-задачи публикации `enabled = false` вне macOS |
| `publishToMavenLocal` падает на подписи без ключа | GPG нет локально | `-PskipSigning=true` |

**Проверка публикации локально:**

```bash
./gradlew publishToMavenLocal -PskipSigning=true
ls ~/.m2/repository/pw/binom/db/ | grep duckdb
# ожидается 21 артефакт: duckdb-core{,-jvm,-android,-linuxx64,…}, kdatabase, korm
```
