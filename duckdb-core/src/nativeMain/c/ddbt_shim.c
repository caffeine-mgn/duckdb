/*
 * C-шов библиотеки duckdb-kmp.
 *
 * Ключевое решение: DuckDB грузится через dlopen, а не линкуется статически.
 * Причина проверена на живых сборках (27.11.2026):
 *
 *   libduckdb_static.a из официальных релизов собрана GCC 12+/glibc 2.31+.
 *   Kotlin/Native линкует через замороженный sysroot: glibc 2.19,
 *   libstdc++ от GCC 8.3, C++-заголовков нет вовсе. Прямая линковка падает:
 *     undefined symbol: explicit_bzero                       (glibc 2.25)
 *     undefined symbol: _dl_find_object                      (glibc 2.35)
 *     undefined symbol: std::__throw_bad_array_new_length()  (libstdc++ GCC 11)
 *     undefined symbol: std::__cxx11::basic_ostringstream()  (нет объекта в .a)
 *   Ни один вариант подстановки чужого libstdc++ не помогает: он тянет
 *   символы новее sysroot'а. Поэтому единственный надёжный путь —
 *   динамическая libduckdb.so и dlsym.
 *
 * Второе следствие: шов остаётся чистым C. Ни одного C++ символа в
 * линковке klib — значит klib собирается на любом хосте, а C++-часть
 * приходит как нативная библиотека рядом с приложением.
 */
#include "ddbt_shim.h"

#include "ddbt_platform.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ---- прототипы C API DuckDB (только то, что используем) ------------ */

typedef void *ddb_db_t;
typedef void *ddb_con_t;
typedef void *ddb_stmt_t;
typedef void *ddb_chunk_t;

/* duckdb_result по ABI — 48 байт (проверено sizeof в 2.0.1). Берём с
 * запасом: пишет в него библиотека, нам нужен только адрес. */
#define DDB_RESULT_BYTES 256

typedef void *(*fn_library_version)(void);
typedef int (*fn_open)(const char *, ddb_db_t *);
typedef int (*fn_open_ext)(const char *, ddb_db_t *, void *, char **);
typedef int (*fn_connect)(ddb_db_t, ddb_con_t *);
typedef void (*fn_disconnect)(ddb_con_t *);
typedef void (*fn_close)(ddb_db_t *);
typedef int (*fn_query)(ddb_con_t, const char *, void *);
typedef void (*fn_destroy_result)(void *);
typedef const char *(*fn_result_error)(void *);
typedef unsigned long long (*fn_idx)(void *);
typedef unsigned long long (*fn_rows_changed)(void *);
typedef char *(*fn_value_varchar)(void *, unsigned long long, unsigned long long);
typedef const char *(*fn_column_name)(void *, unsigned long long);
typedef int (*fn_column_type)(void *, unsigned long long);
typedef int (*fn_value_is_null)(void *, unsigned long long, unsigned long long);
typedef long long (*fn_value_int64)(void *, unsigned long long, unsigned long long);
typedef double (*fn_value_double)(void *, unsigned long long, unsigned long long);
typedef int (*fn_value_boolean)(void *, unsigned long long, unsigned long long);
typedef struct { void *data; unsigned long long size; } ddb_blob_t;
typedef ddb_blob_t (*fn_value_blob)(void *, unsigned long long, unsigned long long);
typedef struct { long long micros; } ddb_ts_t;
typedef ddb_ts_t (*fn_value_timestamp)(void *, unsigned long long, unsigned long long);
typedef struct { unsigned long long lower; long long upper; } ddb_hugeint_t;
typedef ddb_hugeint_t (*fn_value_hugeint)(void *, unsigned long long, unsigned long long);
typedef void (*fn_free)(void *);

typedef int (*fn_prepare)(ddb_con_t, const char *, ddb_stmt_t *);
typedef const char *(*fn_prepare_error)(ddb_stmt_t);
typedef void (*fn_destroy_prepare)(ddb_stmt_t *);
typedef unsigned long long (*fn_nparams)(ddb_stmt_t);
typedef int (*fn_bind_null)(ddb_stmt_t, unsigned long long);
typedef int (*fn_bind_bool)(ddb_stmt_t, unsigned long long, int);
typedef int (*fn_bind_int8)(ddb_stmt_t, unsigned long long, signed char);
typedef int (*fn_bind_int16)(ddb_stmt_t, unsigned long long, short);
typedef int (*fn_bind_int32)(ddb_stmt_t, unsigned long long, int);
typedef int (*fn_bind_int64)(ddb_stmt_t, unsigned long long, long long);
typedef int (*fn_bind_float)(ddb_stmt_t, unsigned long long, float);
typedef int (*fn_bind_double)(ddb_stmt_t, unsigned long long, double);
typedef int (*fn_bind_varchar)(ddb_stmt_t, unsigned long long, const char *);
typedef int (*fn_bind_blob)(ddb_stmt_t, unsigned long long, const void *, unsigned long long);
typedef int (*fn_bind_timestamp)(ddb_stmt_t, unsigned long long, ddb_ts_t);
typedef int (*fn_bind_hugeint)(ddb_stmt_t, unsigned long long, ddb_hugeint_t);
typedef int (*fn_statement_type)(ddb_stmt_t);
typedef int (*fn_execute_prepared)(ddb_stmt_t, void *);

struct ddb_api {
    void *lib;
    fn_library_version library_version;
    fn_open open;
    fn_connect connect;
    fn_disconnect disconnect;
    fn_close close;
    fn_query query;
    fn_destroy_result destroy_result;
    fn_result_error result_error;
    fn_idx column_count;
    fn_idx row_count;
    fn_rows_changed rows_changed;
    fn_value_varchar value_varchar;
    fn_column_name column_name;
    fn_column_type column_type;
    fn_value_is_null value_is_null;
    fn_value_int64 value_int64;
    fn_value_double value_double;
    fn_value_boolean value_boolean;
    fn_value_blob value_blob;
    fn_value_timestamp value_timestamp;
    fn_value_hugeint value_hugeint;
    fn_free free_ptr;
    fn_prepare prepare;
    fn_prepare_error prepare_error;
    fn_destroy_prepare destroy_prepare;
    fn_nparams nparams;
    fn_bind_null bind_null;
    fn_bind_bool bind_bool;
    fn_bind_int8 bind_int8;
    fn_bind_int16 bind_int16;
    fn_bind_int32 bind_int32;
    fn_bind_int64 bind_int64;
    fn_bind_float bind_float;
    fn_bind_double bind_double;
    fn_bind_varchar bind_varchar;
    fn_bind_blob bind_blob;
    fn_bind_timestamp bind_timestamp;
    fn_bind_hugeint bind_hugeint;
    fn_statement_type statement_type;
    fn_execute_prepared execute_prepared;
};

static struct ddb_api g_api;
static int g_ready = 0;
static char g_error[512];

struct ddbt_connection {
    ddb_db_t db;
    ddb_con_t con;
};

struct ddbt_statement {
    struct ddbt_connection *owner;
    ddb_stmt_t stmt;
    unsigned char result[DDB_RESULT_BYTES];
    int has_result;
};

struct ddbt_cursor {
    struct ddbt_statement *owner;
    /*
     * Курсор владеет КОПИЕЙ duckdb_result, а не указателем на буфер
     * statement'а. Причина: `DuckDbConnection.query` делает
     * `prepare(sql).use { it.executeQuery() }` — statement уничтожается
     * сразу после получения курсора, и указатель на его буфер становится
     * висячим (проверено: SIGABRT и мусор в первой колонке). duckdb_result —
     * обычная C-структура со указателями на уже материализованные данные,
     * поэтому memcpy структуры переносит владение без копирования строк.
     */
    unsigned char result[DDB_RESULT_BYTES];
    unsigned long long cols;
    unsigned long long rows;
    unsigned long long row; /* -1 до первого next */
    int materialized;       /* 1 — все строки уже в памяти */
    int result_valid;       /* 1 — result содержит живой duckdb_result */
};

static void set_error(const char *what, const char *detail) {
    if (detail != NULL) {
        snprintf(g_error, sizeof(g_error), "%s: %s", what, detail);
    } else {
        snprintf(g_error, sizeof(g_error), "%s", what);
    }
}

const char *ddbt_last_error(void) {
    return g_error;
}

static char *dup_str(const char *s) {
    if (s == NULL) {
        return NULL;
    }
    size_t n = strlen(s);
    char *copy = (char *)malloc(n + 1);
    if (copy != NULL) {
        memcpy(copy, s, n + 1);
    }
    return copy;
}

/* ---- загрузка ----------------------------------------------------- */

static void *try_dlopen(const char *name) {
    if (name == NULL || name[0] == '\0') {
        return NULL;
    }
    return (void *)ddbt_lib_open(name);
}

int32_t ddbt_init(void) {
    if (g_ready) {
        return DDBT_OK;
    }
    /* Порядок: явная переменная окружения, потом обычный поиск линкера,
     * потом типичные имена. На Android и в AAR работает первый вариант
     * (приложение кладёт .so в jniLibs), на десктопе — второй. */
    const char *env = getenv("DUCKDB_LIBRARY");
    const char *names[] = {
        env,
        "libduckdb.so",
        "libduckdb.so.1",
        "libduckdb.dylib",
        "duckdb.dll",
        "libduckdb",
        NULL,
    };
    void *lib = NULL;
    const char *last = NULL;
    for (int i = 0; names[i] != NULL && lib == NULL; i++) {
        if (names[i] == NULL || names[i][0] == '\0') {
            continue;
        }
        last = names[i];
        lib = try_dlopen(names[i]);
    }
    if (lib == NULL) {
        set_error("libduckdb не загружена", last != NULL ? last : "нет имён для попытки");
        return DDBT_ERR_LOAD;
    }

    g_api.lib = lib;
#define RESOLVE(field, symbol, required)                                            \
    do {                                                                            \
        *(void **)(&g_api.field) = (void *)ddbt_lib_sym((ddbt_lib_t)lib, symbol);                              \
        if (required && g_api.field == NULL) {                                      \
            set_error("в libduckdb нет символа", symbol);                           \
            return DDBT_ERR_LOAD;                                                   \
        }                                                                           \
    } while (0)

    RESOLVE(library_version, "duckdb_library_version", 1);
    RESOLVE(open, "duckdb_open", 1);
    RESOLVE(connect, "duckdb_connect", 1);
    RESOLVE(disconnect, "duckdb_disconnect", 1);
    RESOLVE(close, "duckdb_close", 1);
    RESOLVE(query, "duckdb_query", 1);
    RESOLVE(destroy_result, "duckdb_destroy_result", 1);
    RESOLVE(result_error, "duckdb_result_error", 0);
    RESOLVE(column_count, "duckdb_column_count", 1);
    RESOLVE(row_count, "duckdb_row_count", 1);
    RESOLVE(rows_changed, "duckdb_rows_changed", 0);
    RESOLVE(value_varchar, "duckdb_value_varchar", 1);
    RESOLVE(column_name, "duckdb_column_name", 0);
    RESOLVE(column_type, "duckdb_column_type", 0);
    RESOLVE(value_is_null, "duckdb_value_is_null", 0);
    RESOLVE(value_int64, "duckdb_value_int64", 0);
    RESOLVE(value_double, "duckdb_value_double", 0);
    RESOLVE(value_boolean, "duckdb_value_boolean", 0);
    RESOLVE(value_blob, "duckdb_value_blob", 0);
    RESOLVE(value_timestamp, "duckdb_value_timestamp", 0);
    RESOLVE(value_hugeint, "duckdb_value_hugeint", 0);
    RESOLVE(free_ptr, "duckdb_free", 1);
    RESOLVE(prepare, "duckdb_prepare", 1);
    RESOLVE(prepare_error, "duckdb_prepare_error", 0);
    RESOLVE(destroy_prepare, "duckdb_destroy_prepare", 1);
    RESOLVE(nparams, "duckdb_nparams", 0);
    RESOLVE(bind_null, "duckdb_bind_null", 1);
    RESOLVE(bind_bool, "duckdb_bind_boolean", 1);
    RESOLVE(bind_int8, "duckdb_bind_int8", 1);
    RESOLVE(bind_int16, "duckdb_bind_int16", 1);
    RESOLVE(bind_int32, "duckdb_bind_int32", 1);
    RESOLVE(bind_int64, "duckdb_bind_int64", 1);
    RESOLVE(bind_float, "duckdb_bind_float", 1);
    RESOLVE(bind_double, "duckdb_bind_double", 1);
    RESOLVE(bind_varchar, "duckdb_bind_varchar", 1);
    RESOLVE(bind_blob, "duckdb_bind_blob", 1);
    RESOLVE(bind_timestamp, "duckdb_bind_timestamp", 1);
    RESOLVE(bind_hugeint, "duckdb_bind_hugeint", 1);
    RESOLVE(statement_type, "duckdb_prepared_statement_type", 0);
    RESOLVE(execute_prepared, "duckdb_execute_prepared", 1);
#undef RESOLVE

    g_ready = 1;
    g_error[0] = '\0';
    return DDBT_OK;
}

char *ddbt_version(void) {
    if (ddbt_init() != DDBT_OK) {
        return dup_str(g_error);
    }
    const char *v = g_api.library_version();
    return dup_str(v);
}

/* ---- жизнь соединения --------------------------------------------- */

int32_t ddbt_open(const char *path, int32_t read_only, int64_t *out_handle) {
    if (out_handle == NULL) {
        return DDBT_ERR_ARG;
    }
    if (ddbt_init() != DDBT_OK) {
        return DDBT_ERR_LOAD;
    }
    struct ddbt_connection *c = (struct ddbt_connection *)calloc(1, sizeof(struct ddbt_connection));
    if (c == NULL) {
        return DDBT_ERR_NOMEM;
    }
    const char *p = (path != NULL && path[0] != '\0') ? path : NULL;
    /* read_only для in-memory бессмыслен и DuckDB его отвергает; флаг
     * используем только для файлового пути. Настройки доступа делаются
     * через duckdb_open_ext, но в PoC-контракте достаточно NULL. */
    (void)read_only;
    if (g_api.open(p, &c->db) != 0) {
        set_error("duckdb_open", p != NULL ? p : ":memory:");
        free(c);
        return DDBT_ERR_OPEN;
    }
    if (g_api.connect(c->db, &c->con) != 0) {
        g_api.close(&c->db);
        set_error("duckdb_connect", NULL);
        free(c);
        return DDBT_ERR_CONNECT;
    }
    *out_handle = (int64_t)(intptr_t)c;
    return DDBT_OK;
}

void ddbt_close(int64_t handle) {
    struct ddbt_connection *c = (struct ddbt_connection *)(intptr_t)handle;
    if (c == NULL) {
        return;
    }
    if (c->con != NULL) {
        g_api.disconnect(&c->con);
    }
    if (c->db != NULL) {
        g_api.close(&c->db);
    }
    free(c);
}

/* ---- исполнение --------------------------------------------------- */

static char *result_error_dup(void *result) {
    if (g_api.result_error == NULL) {
        return NULL;
    }
    return dup_str(g_api.result_error(result));
}

int32_t ddbt_execute(int64_t handle, const char *sql, int64_t *out_rows_changed, char **out_error) {
    struct ddbt_connection *c = (struct ddbt_connection *)(intptr_t)handle;
    if (c == NULL) {
        return DDBT_ERR_ARG;
    }
    if (out_error != NULL) {
        *out_error = NULL;
    }
    if (out_rows_changed != NULL) {
        *out_rows_changed = 0;
    }
    unsigned char result[DDB_RESULT_BYTES];
    memset(result, 0, sizeof(result));
    if (g_api.query(c->con, sql, result) != 0) {
        if (out_error != NULL) {
            *out_error = result_error_dup(result);
        }
        g_api.destroy_result(result);
        return DDBT_ERR_SQL;
    }
    if (out_rows_changed != NULL && g_api.rows_changed != NULL) {
        *out_rows_changed = (int64_t)g_api.rows_changed(result);
    }
    g_api.destroy_result(result);
    return DDBT_OK;
}

int32_t ddbt_prepare(int64_t handle, const char *sql, int64_t *out_stmt, char **out_error) {
    struct ddbt_connection *c = (struct ddbt_connection *)(intptr_t)handle;
    if (c == NULL || sql == NULL || out_stmt == NULL) {
        return DDBT_ERR_ARG;
    }
    if (out_error != NULL) {
        *out_error = NULL;
    }
    struct ddbt_statement *s = (struct ddbt_statement *)calloc(1, sizeof(struct ddbt_statement));
    if (s == NULL) {
        return DDBT_ERR_NOMEM;
    }
    s->owner = c;
    memset(s->result, 0, sizeof(s->result));
    if (g_api.prepare(c->con, sql, &s->stmt) != 0) {
        if (out_error != NULL && g_api.prepare_error != NULL) {
            *out_error = dup_str(g_api.prepare_error(s->stmt));
        }
        if (s->stmt != NULL) {
            g_api.destroy_prepare(&s->stmt);
        }
        free(s);
        return DDBT_ERR_PREPARE;
    }
    *out_stmt = (int64_t)(intptr_t)s;
    return DDBT_OK;
}

void ddbt_destroy_statement(int64_t stmt) {
    struct ddbt_statement *s = (struct ddbt_statement *)(intptr_t)stmt;
    if (s == NULL) {
        return;
    }
    if (s->stmt != NULL) {
        g_api.destroy_prepare(&s->stmt);
    }
    free(s);
}

int32_t ddbt_nparams(int64_t stmt) {
    struct ddbt_statement *s = (struct ddbt_statement *)(intptr_t)stmt;
    if (s == NULL || s->stmt == NULL || g_api.nparams == NULL) {
        return 0;
    }
    return (int32_t)g_api.nparams(s->stmt);
}

int32_t ddbt_bind_null(int64_t stmt, int32_t index) {
    struct ddbt_statement *s = (struct ddbt_statement *)(intptr_t)stmt;
    if (s == NULL || s->stmt == NULL) {
        return DDBT_ERR_ARG;
    }
    /* ВАЖНО: DuckDB нумерует параметры с 1 (проверено: bind с 0 возвращает
     * ошибку, с 1 — работает), а контракт kdatabase — с 0. Перевод делаем
     * здесь, в единственном месте; выше индексы всегда 0-based. */
    return g_api.bind_null(s->stmt, (unsigned long long)(index + 1)) == 0 ? DDBT_OK : DDBT_ERR_BIND;
}

int32_t ddbt_bind_bool(int64_t stmt, int32_t index, int32_t value) {
    struct ddbt_statement *s = (struct ddbt_statement *)(intptr_t)stmt;
    if (s == NULL || s->stmt == NULL) {
        return DDBT_ERR_ARG;
    }
    return g_api.bind_bool(s->stmt, (unsigned long long)(index + 1), value != 0) == 0 ? DDBT_OK : DDBT_ERR_BIND;
}

int32_t ddbt_bind_int8(int64_t stmt, int32_t index, int8_t value) {
    struct ddbt_statement *s = (struct ddbt_statement *)(intptr_t)stmt;
    if (s == NULL || s->stmt == NULL) {
        return DDBT_ERR_ARG;
    }
    return g_api.bind_int8(s->stmt, (unsigned long long)(index + 1), value) == 0 ? DDBT_OK : DDBT_ERR_BIND;
}

int32_t ddbt_bind_int16(int64_t stmt, int32_t index, int16_t value) {
    struct ddbt_statement *s = (struct ddbt_statement *)(intptr_t)stmt;
    if (s == NULL || s->stmt == NULL) {
        return DDBT_ERR_ARG;
    }
    return g_api.bind_int16(s->stmt, (unsigned long long)(index + 1), value) == 0 ? DDBT_OK : DDBT_ERR_BIND;
}

int32_t ddbt_bind_int32(int64_t stmt, int32_t index, int32_t value) {
    struct ddbt_statement *s = (struct ddbt_statement *)(intptr_t)stmt;
    if (s == NULL || s->stmt == NULL) {
        return DDBT_ERR_ARG;
    }
    return g_api.bind_int32(s->stmt, (unsigned long long)(index + 1), value) == 0 ? DDBT_OK : DDBT_ERR_BIND;
}

int32_t ddbt_bind_int64(int64_t stmt, int32_t index, int64_t value) {
    struct ddbt_statement *s = (struct ddbt_statement *)(intptr_t)stmt;
    if (s == NULL || s->stmt == NULL) {
        return DDBT_ERR_ARG;
    }
    return g_api.bind_int64(s->stmt, (unsigned long long)(index + 1), value) == 0 ? DDBT_OK : DDBT_ERR_BIND;
}

int32_t ddbt_bind_float(int64_t stmt, int32_t index, float value) {
    struct ddbt_statement *s = (struct ddbt_statement *)(intptr_t)stmt;
    if (s == NULL || s->stmt == NULL) {
        return DDBT_ERR_ARG;
    }
    return g_api.bind_float(s->stmt, (unsigned long long)(index + 1), value) == 0 ? DDBT_OK : DDBT_ERR_BIND;
}

int32_t ddbt_bind_double(int64_t stmt, int32_t index, double value) {
    struct ddbt_statement *s = (struct ddbt_statement *)(intptr_t)stmt;
    if (s == NULL || s->stmt == NULL) {
        return DDBT_ERR_ARG;
    }
    return g_api.bind_double(s->stmt, (unsigned long long)(index + 1), value) == 0 ? DDBT_OK : DDBT_ERR_BIND;
}

int32_t ddbt_bind_text(int64_t stmt, int32_t index, const char *value, int32_t len) {
    struct ddbt_statement *s = (struct ddbt_statement *)(intptr_t)stmt;
    if (s == NULL || s->stmt == NULL || value == NULL) {
        return DDBT_ERR_ARG;
    }
    /* duckdb_bind_varchar ждёт NUL-терминированную строку и делает копию.
     * Значение с встроенным NUL так передать нельзя — для этого есть blob. */
    (void)len;
    return g_api.bind_varchar(s->stmt, (unsigned long long)(index + 1), value) == 0 ? DDBT_OK : DDBT_ERR_BIND;
}

int32_t ddbt_bind_blob(int64_t stmt, int32_t index, const void *value, int32_t len) {
    struct ddbt_statement *s = (struct ddbt_statement *)(intptr_t)stmt;
    if (s == NULL || s->stmt == NULL) {
        return DDBT_ERR_ARG;
    }
    return g_api.bind_blob(s->stmt, (unsigned long long)(index + 1), value,
                           (unsigned long long)len) == 0
               ? DDBT_OK
               : DDBT_ERR_BIND;
}

int32_t ddbt_bind_timestamp_micros(int64_t stmt, int32_t index, int64_t micros) {
    struct ddbt_statement *s = (struct ddbt_statement *)(intptr_t)stmt;
    if (s == NULL || s->stmt == NULL) {
        return DDBT_ERR_ARG;
    }
    ddb_ts_t ts;
    ts.micros = micros;
    return g_api.bind_timestamp(s->stmt, (unsigned long long)(index + 1), ts) == 0 ? DDBT_OK : DDBT_ERR_BIND;
}

int32_t ddbt_bind_uuid(int64_t stmt, int32_t index, const uint8_t *bytes16) {
    struct ddbt_statement *s = (struct ddbt_statement *)(intptr_t)stmt;
    if (s == NULL || s->stmt == NULL || bytes16 == NULL) {
        return DDBT_ERR_ARG;
    }
    /*
     * UUID биндится СТРОКОЙ, а не через duckdb_bind_hugeint.
     *
     * Причина проверена живьём: DuckDB хранит UUID как HUGEINT, но
     * `bind_hugeint` + неявное приведение при INSERT даёт
     *   Conversion Error: Unimplemented type for cast (HUGEINT -> UUID)
     * а `bind_varchar` с канонической формой 8-4-4-4-12 приводится к UUID
     * корректно и на запись, и на чтение.
     */
    static const char hex[] = "0123456789abcdef";
    char text[37];
    int pos = 0;
    for (int i = 0; i < 16; i++) {
        if (i == 4 || i == 6 || i == 8 || i == 10) {
            text[pos++] = '-';
        }
        text[pos++] = hex[(bytes16[i] >> 4) & 0x0F];
        text[pos++] = hex[bytes16[i] & 0x0F];
    }
    text[pos] = '\0';
    return g_api.bind_varchar(s->stmt, (unsigned long long)(index + 1), text) == 0
               ? DDBT_OK
               : DDBT_ERR_BIND;
}

int32_t ddbt_execute_prepared(int64_t stmt, int64_t *out_cursor, int64_t *out_rows_changed, char **out_error) {
    struct ddbt_statement *s = (struct ddbt_statement *)(intptr_t)stmt;
    if (s == NULL || s->stmt == NULL) {
        return DDBT_ERR_ARG;
    }
    if (out_cursor != NULL) {
        *out_cursor = 0;
    }
    if (out_rows_changed != NULL) {
        *out_rows_changed = 0;
    }
    if (out_error != NULL) {
        *out_error = NULL;
    }
    memset(s->result, 0, sizeof(s->result));
    if (g_api.execute_prepared(s->stmt, s->result) != 0) {
        if (out_error != NULL) {
            *out_error = result_error_dup(s->result);
        }
        g_api.destroy_result(s->result);
        s->has_result = 0;
        return DDBT_ERR_SQL;
    }
    s->has_result = 1;
    /*
     * Решаем «это SELECT или DML» по типу подготовленного запроса, а не по
     * числу колонок результата.
     *
     * Проверено живьём: `INSERT` в DuckDB 2.0.1 возвращает результат с ОДНОЙ
     * колонкой `Count` и одной строкой. Проверка `cols == 0` считала это
     * SELECT'ом, отдавала курсор, а `rows_changed` оставался нулём — счётчик
     * вставленных строк терялся. `duckdb_prepared_statement_type`: 1 —
     * SELECT, 2 — INSERT, 3 — UPDATE, 4 — DELETE и т.д.
     */
    int stmt_type = g_api.statement_type != NULL ? g_api.statement_type(s->stmt) : 1;
    int is_select = (stmt_type == 1);
    unsigned long long cols = g_api.column_count(s->result);
    if (!is_select || cols == 0) {
        if (out_rows_changed != NULL && g_api.rows_changed != NULL) {
            *out_rows_changed = (int64_t)g_api.rows_changed(s->result);
        }
        g_api.destroy_result(s->result);
        s->has_result = 0;
        return DDBT_OK;
    }
    if (out_cursor == NULL) {
        g_api.destroy_result(s->result);
        s->has_result = 0;
        return DDBT_OK;
    }
    struct ddbt_cursor *cur = (struct ddbt_cursor *)calloc(1, sizeof(struct ddbt_cursor));
    if (cur == NULL) {
        g_api.destroy_result(s->result);
        s->has_result = 0;
        return DDBT_ERR_NOMEM;
    }
    cur->owner = s;
    memcpy(cur->result, s->result, DDB_RESULT_BYTES);
    /* Владение результатом перешло курсору: statement свой экземпляр
     * больше не освобождает. */
    s->has_result = 0;
    cur->cols = cols;
    cur->rows = g_api.row_count(cur->result);
    cur->row = (unsigned long long)-1;
    /* Материализованный результат: DuckDB уже отдал все строки в память.
     * Потоковый вариант (duckdb_fetch_chunk) добавится поверх того же
     * курсора — интерфейс не изменится. */
    cur->materialized = 1;
    cur->result_valid = 1;
    *out_cursor = (int64_t)(intptr_t)cur;
    return DDBT_OK;
}

/* ---- курсор ------------------------------------------------------- */

int32_t ddbt_cursor_column_count(int64_t cursor) {
    struct ddbt_cursor *c = (struct ddbt_cursor *)(intptr_t)cursor;
    return c == NULL ? 0 : (int32_t)c->cols;
}

char *ddbt_cursor_column_name(int64_t cursor, int32_t index) {
    struct ddbt_cursor *c = (struct ddbt_cursor *)(intptr_t)cursor;
    if (c == NULL || g_api.column_name == NULL || index < 0 || (unsigned long long)index >= c->cols) {
        return NULL;
    }
    return dup_str(g_api.column_name(c->result, (unsigned long long)index));
}

int32_t ddbt_cursor_column_type(int64_t cursor, int32_t index) {
    struct ddbt_cursor *c = (struct ddbt_cursor *)(intptr_t)cursor;
    if (c == NULL || g_api.column_type == NULL || index < 0 || (unsigned long long)index >= c->cols) {
        return DDBT_COL_NULL;
    }
    /* duckdb_type -> ColumnType из kdatabase. UNSIGNED-типы расширяются до
     * следующего знакового: UINTEGER -> BIGINT, UBIGINT -> NUMERIC (в
     * Long не влезает, а NUMERIC kdatabase трактует как double/строку). */
    switch (g_api.column_type(c->result, (unsigned long long)index)) {
        case 1:  /* BOOLEAN */ return DDBT_COL_BIT;
        case 2:  /* TINYINT */ return DDBT_COL_TINYINT;
        case 3:  /* SMALLINT */ return DDBT_COL_SMALLINT;
        case 4:  /* INTEGER */ return DDBT_COL_INTEGER;
        case 5:  /* BIGINT */ return DDBT_COL_BIGINT;
        case 6:  /* UTINYINT */ return DDBT_COL_SMALLINT;
        case 7:  /* USMALLINT */ return DDBT_COL_INTEGER;
        case 8:  /* UINTEGER */ return DDBT_COL_BIGINT;
        case 9:  /* UBIGINT */ return DDBT_COL_NUMERIC;
        case 10: /* FLOAT */ return DDBT_COL_FLOAT;
        case 11: /* DOUBLE */ return DDBT_COL_DOUBLE;
        case 12: /* TIMESTAMP */ return DDBT_COL_TIMESTAMP;
        case 13: /* DATE */ return DDBT_COL_DATE;
        case 14: /* TIME */ return DDBT_COL_TIME;
        case 15: /* INTERVAL */ return DDBT_COL_OTHER;
        case 16: /* HUGEINT */ return DDBT_COL_NUMERIC;
        case 17: /* VARCHAR */ return DDBT_COL_VARCHAR;
        case 18: /* BLOB */ return DDBT_COL_VARBINARY;
        case 19: /* DECIMAL */ return DDBT_COL_DECIMAL;
        case 20: /* TIMESTAMP_S */
        case 21: /* TIMESTAMP_MS */
        case 22: /* TIMESTAMP_NS */ return DDBT_COL_TIMESTAMP;
        case 23: /* ENUM */ return DDBT_COL_VARCHAR;
        case 24: /* LIST */
        case 25: /* STRUCT */
        case 26: /* MAP */
        case 28: /* UNION */ return DDBT_COL_OTHER;
        case 27: /* UUID */ return DDBT_COL_UUID;
        case 29: /* BIT */ return DDBT_COL_BIT;
        default: return DDBT_COL_OTHER;
    }
}

int32_t ddbt_cursor_next(int64_t cursor) {
    struct ddbt_cursor *c = (struct ddbt_cursor *)(intptr_t)cursor;
    if (c == NULL) {
        return DDBT_ERR_ARG;
    }
    unsigned long long next = c->row + 1;
    if (next >= c->rows) {
        c->row = c->rows; /* за пределами: повторный вызов тоже вернёт 0 */
        return 0;
    }
    c->row = next;
    return 1;
}

int32_t ddbt_cursor_is_null(int64_t cursor, int32_t column) {
    struct ddbt_cursor *c = (struct ddbt_cursor *)(intptr_t)cursor;
    if (c == NULL || g_api.value_is_null == NULL || c->row >= c->rows) {
        return 1;
    }
    return g_api.value_is_null(c->result, (unsigned long long)column, c->row) ? 1 : 0;
}

int32_t ddbt_cursor_get_bool(int64_t cursor, int32_t column, int32_t *out) {
    struct ddbt_cursor *c = (struct ddbt_cursor *)(intptr_t)cursor;
    if (c == NULL || out == NULL || c->row >= c->rows) {
        return DDBT_ERR_ARG;
    }
    *out = g_api.value_boolean(c->result, (unsigned long long)column, c->row) ? 1 : 0;
    return DDBT_OK;
}

int32_t ddbt_cursor_get_int64(int64_t cursor, int32_t column, int64_t *out) {
    struct ddbt_cursor *c = (struct ddbt_cursor *)(intptr_t)cursor;
    if (c == NULL || out == NULL || c->row >= c->rows) {
        return DDBT_ERR_ARG;
    }
    *out = (int64_t)g_api.value_int64(c->result, (unsigned long long)column, c->row);
    return DDBT_OK;
}

int32_t ddbt_cursor_get_double(int64_t cursor, int32_t column, double *out) {
    struct ddbt_cursor *c = (struct ddbt_cursor *)(intptr_t)cursor;
    if (c == NULL || out == NULL || c->row >= c->rows) {
        return DDBT_ERR_ARG;
    }
    *out = g_api.value_double(c->result, (unsigned long long)column, c->row);
    return DDBT_OK;
}

int32_t ddbt_cursor_get_text(int64_t cursor, int32_t column, char **out, int32_t *out_len) {
    struct ddbt_cursor *c = (struct ddbt_cursor *)(intptr_t)cursor;
    if (c == NULL || out == NULL || out_len == NULL || c->row >= c->rows) {
        return DDBT_ERR_ARG;
    }
    *out = NULL;
    *out_len = 0;
    /* value_varchar отдаёт malloc-копию, владение переходит к нам. */
    char *v = g_api.value_varchar(c->result, (unsigned long long)column, c->row);
    if (v == NULL) {
        return DDBT_OK;
    }
    *out = v;
    *out_len = (int32_t)strlen(v);
    return DDBT_OK;
}

int32_t ddbt_cursor_get_blob(int64_t cursor, int32_t column, void **out, int32_t *out_len) {
    struct ddbt_cursor *c = (struct ddbt_cursor *)(intptr_t)cursor;
    if (c == NULL || out == NULL || out_len == NULL || c->row >= c->rows || g_api.value_blob == NULL) {
        return DDBT_ERR_ARG;
    }
    *out = NULL;
    *out_len = 0;
    ddb_blob_t b = g_api.value_blob(c->result, (unsigned long long)column, c->row);
    if (b.data == NULL) {
        return DDBT_OK;
    }
    void *copy = malloc(b.size > 0 ? b.size : 1);
    if (copy == NULL) {
        g_api.free_ptr(b.data);
        return DDBT_ERR_NOMEM;
    }
    memcpy(copy, b.data, b.size);
    g_api.free_ptr(b.data);
    *out = copy;
    *out_len = (int32_t)b.size;
    return DDBT_OK;
}

int32_t ddbt_cursor_get_timestamp_micros(int64_t cursor, int32_t column, int64_t *out) {
    struct ddbt_cursor *c = (struct ddbt_cursor *)(intptr_t)cursor;
    if (c == NULL || out == NULL || c->row >= c->rows || g_api.value_timestamp == NULL) {
        return DDBT_ERR_ARG;
    }
    ddb_ts_t ts = g_api.value_timestamp(c->result, (unsigned long long)column, c->row);
    *out = ts.micros;
    return DDBT_OK;
}

int32_t ddbt_cursor_get_uuid(int64_t cursor, int32_t column, uint8_t *out16) {
    struct ddbt_cursor *c = (struct ddbt_cursor *)(intptr_t)cursor;
    if (c == NULL || out16 == NULL || c->row >= c->rows || g_api.value_hugeint == NULL) {
        return DDBT_ERR_ARG;
    }
    ddb_hugeint_t h = g_api.value_hugeint(c->result, (unsigned long long)column, c->row);
    for (int i = 0; i < 8; i++) {
        out16[i] = (uint8_t)((h.upper >> (56 - i * 8)) & 0xFF);
        out16[8 + i] = (uint8_t)((h.lower >> (56 - i * 8)) & 0xFF);
    }
    return DDBT_OK;
}

void ddbt_cursor_close(int64_t cursor) {
    struct ddbt_cursor *c = (struct ddbt_cursor *)(intptr_t)cursor;
    if (c == NULL) {
        return;
    }
    /* Результат принадлежит курсору — освобождаем его здесь, один раз.
     * Statement к этому моменту может быть уже уничтожен. */
    if (c->result_valid) {
        g_api.destroy_result(c->result);
        c->result_valid = 0;
    }
    free(c);
}

void ddbt_free(void *ptr) {
    free(ptr);
}
