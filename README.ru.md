# duckdb-kmp

> **Язык**: Русский | [English](./README.md)

DuckDB для Kotlin Multiplatform. Одна библиотека, три модуля: сам клиент,
адаптер к [`kdatabase`](https://github.com/caffeine-mgn/kdatabase) и адаптер
к [`korm`](https://github.com/caffeine-mgn/korm).

Статус: **alpha**. C-шов, JVM-путь и native-путь проверены реальными
прогонами тестов; у ORM-адаптера есть документированное ограничение (ниже).

## Подключение

Опубликовано в **Maven Central**, группа `pw.binom.db`.

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    // Только клиент — без kdatabase и korm.
    implementation("pw.binom.db:duckdb-core:VERSION")

    // Плюс контракты pw.binom.db (Connection / PreparedStatement / ResultSet / пул).
    implementation("pw.binom.db:duckdb-kdatabase:VERSION")

    // Плюс адаптер ORM.
    implementation("pw.binom.db:duckdb-korm:VERSION")
}
```

Таргетные артефакты резолвятся из KMP metadata-артефакта сами —
**вручную их не прописывать**.

### Таргеты

| Семейство | Таргеты | Нативный код |
|---|---|---|
| JVM | `jvm` | JNI, `.so`/`.dylib`/`.dll` внутри jar |
| Android (JVM/ART) | `androidTarget` (AAR) | JNI, `.so` на каждый ABI в `jni/<abi>/` |
| Linux | `linuxX64`, `linuxArm64` | cinterop + статический C-шов |
| Windows | `mingwX64` | cinterop + статический C-шов |
| macOS | `macosX64`, `macosArm64` | cinterop + статический C-шов |
| iOS | `iosX64`, `iosArm64`, `iosSimulatorArm64` | cinterop + статический C-шов |
| tvOS | `tvosX64`, `tvosArm64`, `tvosSimulatorArm64` | cinterop + статический C-шов |
| watchOS | `watchosX64`, `watchosArm32`, `watchosArm64`, `watchosSimulatorArm64`, `watchosDeviceArm64` | cinterop + статический C-шов |
| Android NDK | `androidNativeArm32/Arm64/X86/X64` | cinterop + статический C-шов |

Apple-klib собираются **на macOS-хосте**: на Linux таргеты объявлены, но
плагин их пропускает. Android-AAR несёт все четыре ABI (`arm64-v8a`,
`armeabi-v7a`, `x86`, `x86_64`) — проверено `unzip -l` по собранному
артефакту.

Текущая версия — в реестре:

```bash
curl -s "https://repo1.maven.org/maven2/pw/binom/db/duckdb-core/maven-metadata.xml" | tail -12
```

Релизы публикуются с **macOS-раннера**, который собирает и публикует все
20 таргетов за один прогон (Apple-таргеты собираются только на маке).
Workflow — в `.github/workflows/release.yml`, запуск по тегу `N.N.N`.

### 🔴 Нужно положить `libduckdb` рядом

В klib и в jar лежит **только C-шов**, а не сам движок. DuckDB грузится в
рантайме через `dlopen`, в таком порядке:

1. `$DUCKDB_LIBRARY` — явный путь к `libduckdb.so` / `.dylib` / `duckdb.dll`;
2. путь поиска загрузчика платформы (`LD_LIBRARY_PATH`, `DYLD_LIBRARY_PATH`, `PATH`);
3. просто `libduckdb.so`, `libduckdb.so.1`, `libduckdb.dylib`, `duckdb.dll`.

Официальная сборка — в
[релизах duckdb/duckdb](https://github.com/duckdb/duckdb/releases),
ассеты `duckdb-shared-libs-*`. Положите `.so` рядом с приложением или
укажите `DUCKDB_LIBRARY`:

```bash
export DUCKDB_LIBRARY=/opt/duckdb/libduckdb.so
```

**Почему динамически, а не статически.** Официальный `libduckdb_static.a`
собран GCC 12+/glibc 2.31+, а Kotlin/Native линкует через замороженный
sysroot (glibc 2.19, libstdc++ от GCC 8.3, C++-заголовков нет вовсе).
Статическая линковка падает на символах, которых в этом sysroot просто нет:

```
undefined symbol: explicit_bzero                       (glibc 2.25)
undefined symbol: _dl_find_object                      (glibc 2.35)
undefined symbol: std::__throw_bad_array_new_length()  (libstdc++ GCC 11)
undefined symbol: std::__cxx11::basic_ostringstream()  (нет объекта в .a)
```

Подстановка чужого, более нового libstdc++ валится на следующей партии
символов новее sysroot'а. Поэтому шов — чистый C, а движок приходит
разделяемой библиотекой.

## Использование

### Клиент

```kotlin
import pw.binom.db.duckdb.*

DuckDbConnection.openInMemory().use { db ->
    db.execute("CREATE TABLE t(id INTEGER, name VARCHAR)")
    db.execute("INSERT INTO t VALUES (1, 'a'), (2, 'b')")

    db.query("SELECT name FROM t ORDER BY id").use { rs ->
        while (rs.next()) {
            println(rs.getString("name"))
        }
    }

    // Параметры позиционные, с нуля.
    db.prepare("SELECT name FROM t WHERE id = ?").use { st ->
        st.bind(0, DuckDbValue.Int32(1))
        st.executeQuery().use { rs ->
            if (rs.next()) println(rs.getString("name"))
        }
    }
}
```

Файловая база:

```kotlin
DuckDbConnection.open(DuckDbConfig(path = "/var/lib/app/data.duckdb")).use { db -> /* ... */ }
```

`DuckDbConnection` блокирующий намеренно: DuckDB — встроенный движок, запрос
считается в том же процессе, ждать сети нечего.

### С kdatabase

```kotlin
import pw.binom.db.duckdb.adapter.openDuckDbPool
import pw.binom.db.transaction

openDuckDbPool().use { pool ->
    pool.withConnection { conn ->
        conn.transaction { tx ->
            tx.prepare("INSERT INTO t VALUES (?, ?)").use { st ->
                st.bind(0, SqlValue.Int32(1))
                st.bind(1, SqlValue.Text("a"))
                st.executeUpdate()
            }
        }
    }
}
```

### С korm

```kotlin
import pw.binom.db.duckdb.orm.DuckDbOrm

val pool = DuckDbOrm.openPool()
val orm = DuckDbOrm.open(pool)
```

## Что проверено

Измерено, а не предположено — DuckDB 2.0.1, Linux x86_64:

| Проверка | JVM (JNI) | Native (cinterop) |
|---|---|---|
| `select 1+1`, `sum(unnest(range(n)))` | ✅ | ✅ |
| DDL + DML, счётчик `rows_changed` | ✅ | ✅ |
| Подготовленный запрос, позиционные bind'ы | ✅ | ✅ |
| NULL и отображение типов | ✅ | ✅ |
| Текст ошибки пробрасывается, constraint классифицируется | ✅ | ✅ |
| `TIMESTAMP` туда-обратно (микросекунды) | ✅ | ✅ |
| Курсор жив после закрытия statement | ✅ | ✅ |

Две особенности движка, найденные тестами и закрытые в шве:

- **DuckDB нумерует параметры с 1**, а контракт kdatabase — с 0. Шов
  прибавляет 1 в единственной точке границы, поэтому весь Kotlin-API
  остаётся 0-based.
- **`INSERT` возвращает результат** с одной колонкой `Count`. Решение «это
  SELECT или DML» по числу колонок теряет счётчик `rows_changed`; шов
  спрашивает `duckdb_prepared_statement_type`.

## Известные ограничения

### UUID не читается через C value API

DuckDB 2.0.1 хранит `UUID` как `HUGEINT`. Запись работает; **чтение — нет**:
`duckdb_value_uhugeint`, `duckdb_value_string` и `duckdb_value_varchar`
возвращают нули для колонки UUID (проверено отдельной C-программой, без
участия Kotlin). Работающий обход — приведение в SQL:

```sql
SELECT u::VARCHAR AS u FROM t
```

### ORM: первичным ключам нужен явный default

`korm` генерирует первичный ключ `INTEGER` для движка SQLITE. В SQLite
`INTEGER PRIMARY KEY` — алиас rowid и заполняется сам; **в DuckDB такого
алиаса нет**, поэтому вставка без id падает с `NOT NULL constraint failed`.
Такие таблицы надо создавать явной последовательностью:

```sql
CREATE SEQUENCE users_id_seq;
CREATE TABLE users (
    id   BIGINT PRIMARY KEY DEFAULT nextval('users_id_seq'),
    name VARCHAR NOT NULL
);
```

Это ограничение `korm`: лечится добавлением `DatabaseEngine.DUCKDB` и
генерацией `DEFAULT nextval(...)` там.

### Потокового чтения нет

DuckDB материализует результат в памяти (`duckdb_execute_prepared`). Адаптер
бросает `UnsupportedOperationException` на `queryStream`, а не притворяется.

## Сборка

Требуется JDK 17+ и тулчейн Kotlin/Native (скачивается Gradle'ом).

```bash
# Только JVM
export DUCKDB_LIBRARY=/path/to/libduckdb.so
./gradlew :duckdb-core:jvmTest

# Native
./gradlew :duckdb-core:linuxX64Test
```

C-шов (`duckdb-core/src/nativeMain/c/ddbt_shim.c`) собирается
[`kn-clang`](https://github.com/caffeine-mgn/kn-clang-compiler-plugin): в
статический архив для каждого native-таргета и в JNI-разделяемую библиотеку
для JVM.

## Лицензия

Apache License 2.0 — см. [LICENSE](./LICENSE).
