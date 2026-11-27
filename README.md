# duckdb-kmp

> **Language**: English | [Русский](./README.ru.md)

DuckDB for Kotlin Multiplatform. One client library, three modules: the
client itself, an adapter to [`kdatabase`](https://github.com/caffeine-mgn/kdatabase),
and an adapter to [`korm`](https://github.com/caffeine-mgn/korm).

Status: **alpha**. The C shim, the JVM path and the native path are verified
by real test runs; the ORM adapter carries a documented limitation (below).

## Installation

The library is published to **Maven Central**, group `pw.binom.db`.

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    // The client on its own — no kdatabase, no korm.
    implementation("pw.binom.db:duckdb-core:VERSION")

    // Plus the pw.binom.db contracts (Connection / PreparedStatement / ResultSet / pool).
    implementation("pw.binom.db:duckdb-kdatabase:VERSION")

    // Plus the ORM adapter.
    implementation("pw.binom.db:duckdb-korm:VERSION")
}
```

Target-specific artifacts are resolved from the KMP metadata artifact
automatically — **do not declare them by hand**.

### Targets

| Family | Targets | Native code |
|---|---|---|
| JVM | `jvm` | JNI, `.so`/`.dylib`/`.dll` inside the jar |
| Android (JVM/ART) | `androidTarget` (AAR) | JNI, `.so` per ABI in `jni/<abi>/` |
| Linux | `linuxX64`, `linuxArm64` | cinterop + static C shim |
| Windows | `mingwX64` | cinterop + static C shim |
| macOS | `macosX64`, `macosArm64` | cinterop + static C shim |
| iOS | `iosX64`, `iosArm64`, `iosSimulatorArm64` | cinterop + static C shim |
| tvOS | `tvosX64`, `tvosArm64`, `tvosSimulatorArm64` | cinterop + static C shim |
| watchOS | `watchosX64`, `watchosArm32`, `watchosArm64`, `watchosSimulatorArm64`, `watchosDeviceArm64` | cinterop + static C shim |
| Android NDK | `androidNativeArm32/Arm64/X86/X64` | cinterop + static C shim |

Apple klibs are built **on a macOS host** — on Linux those targets are
declared but skipped by the Kotlin plugin. The Android AAR carries all four
ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`), verified with
`unzip -l` on the built artifact.

Current version — see the registry:

```bash
curl -s "https://repo1.maven.org/maven2/pw/binom/db/duckdb-core/maven-metadata.xml" | tail -12
```

Releases are published from a **macOS runner** that builds and publishes
all 20 targets in one run (Apple targets only build on a Mac). The workflow
is in `.github/workflows/release.yml`; a tag of the form `N.N.N` starts it.

### 🔴 You must ship `libduckdb`

The klib and the jar contain **only the C shim**, not the DuckDB engine
itself. DuckDB is loaded at runtime with `dlopen`, in this order:

1. `$DUCKDB_LIBRARY` — an explicit path to `libduckdb.so` / `.dylib` / `duckdb.dll`;
2. the platform loader search path (`LD_LIBRARY_PATH`, `DYLD_LIBRARY_PATH`, `PATH`);
3. plain `libduckdb.so`, `libduckdb.so.1`, `libduckdb.dylib`, `duckdb.dll`.

Grab an official build from
[duckdb/duckdb releases](https://github.com/duckdb/duckdb/releases) —
`duckdb-shared-libs-*` — and put the shared library next to your application
or point `DUCKDB_LIBRARY` at it.

```bash
export DUCKDB_LIBRARY=/opt/duckdb/libduckdb.so
```

**Why dynamic and not static.** Official `libduckdb_static.a` is built with
GCC 12+/glibc 2.31+, while Kotlin/Native links through its frozen sysroot
(glibc 2.19, libstdc++ from GCC 8.3, no C++ headers at all). Static linking
fails on symbols that simply do not exist in that sysroot:

```
undefined symbol: explicit_bzero                       (glibc 2.25)
undefined symbol: _dl_find_object                      (glibc 2.35)
undefined symbol: std::__throw_bad_array_new_length()  (libstdc++ GCC 11)
undefined symbol: std::__cxx11::basic_ostringstream()  (no such object in the .a)
```

Every attempt to substitute a newer libstdc++ fails on the next batch of
symbols newer than the sysroot. So the shim is pure C, and the engine arrives
as a shared library.

## Usage

### The client

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

    // Parameters are positional, zero-based.
    db.prepare("SELECT name FROM t WHERE id = ?").use { st ->
        st.bind(0, DuckDbValue.Int32(1))
        st.executeQuery().use { rs ->
            if (rs.next()) println(rs.getString("name"))
        }
    }
}
```

A file-backed database:

```kotlin
DuckDbConnection.open(DuckDbConfig(path = "/var/lib/app/data.duckdb")).use { db -> /* ... */ }
```

`DuckDbConnection` is blocking by design: DuckDB is an embedded engine, the
query is computed in-process and there is no network wait to suspend on.

### With kdatabase

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

### With korm

```kotlin
import pw.binom.db.duckdb.orm.DuckDbOrm

val pool = DuckDbOrm.openPool()
val orm = DuckDbOrm.open(pool)
```

## Verified behaviour

Measured, not assumed — DuckDB 2.0.1, Linux x86_64:

| Check | JVM (JNI) | Native (cinterop) |
|---|---|---|
| `select 1+1`, `sum(unnest(range(n)))` | ✅ | ✅ |
| DDL + DML, `rows_changed` counter | ✅ | ✅ |
| Prepared statement, positional binds | ✅ | ✅ |
| NULL handling, type mapping | ✅ | ✅ |
| Error text passed through, constraint classified | ✅ | ✅ |
| `TIMESTAMP` round-trip (micros) | ✅ | ✅ |
| Cursor survives statement close | ✅ | ✅ |

Two engine quirks found by testing and handled in the shim:

- **Parameter indices are 1-based in DuckDB**, while the kdatabase contract is
  0-based. The shim adds 1 at the single boundary, so every Kotlin-level API
  stays zero-based.
- **`INSERT` returns a result set** with one column named `Count`. Deciding
  "is this a SELECT" by column count loses the `rows_changed` counter; the shim
  asks `duckdb_prepared_statement_type` instead.

## Known limitations

### UUID cannot be read through the C value API

DuckDB 2.0.1 stores `UUID` as `HUGEINT`. Writing works; **reading does not**:
`duckdb_value_uhugeint`, `duckdb_value_string` and `duckdb_value_varchar` all
return zeros for a UUID column (verified with a standalone C program, no Kotlin
involved). Workaround that works — cast in SQL:

```sql
SELECT u::VARCHAR AS u FROM t
```

### ORM: primary keys need an explicit default

`korm` generates `INTEGER` primary keys for the SQLITE engine. In SQLite
`INTEGER PRIMARY KEY` is a rowid alias and fills itself; **DuckDB has no such
alias**, so an insert without an id fails with `NOT NULL constraint failed`.
Create such tables with an explicit sequence:

```sql
CREATE SEQUENCE users_id_seq;
CREATE TABLE users (
    id   BIGINT PRIMARY KEY DEFAULT nextval('users_id_seq'),
    name VARCHAR NOT NULL
);
```

This is a `korm` limitation: it is fixed by adding `DatabaseEngine.DUCKDB`
and generating `DEFAULT nextval(...)` there.

### No streaming

DuckDB materialises results in memory (`duckdb_execute_prepared`). The adapter
throws `UnsupportedOperationException` for `queryStream` instead of pretending.

## Building

Requirements: JDK 17+, and the Kotlin/Native toolchain (downloaded by Gradle).

```bash
# JVM only
export DUCKDB_LIBRARY=/path/to/libduckdb.so
./gradlew :duckdb-core:jvmTest

# Native
./gradlew :duckdb-core:linuxX64Test
```

The C shim (`duckdb-core/src/nativeMain/c/ddbt_shim.c`) is compiled by
[`kn-clang`](https://github.com/caffeine-mgn/kn-clang-compiler-plugin): into a
static archive for each native target, and into a JNI shared library for the
JVM.

## License

Apache License 2.0 — see [LICENSE](./LICENSE).
