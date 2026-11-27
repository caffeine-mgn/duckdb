#ifndef DUCKDBK_SHIM_H
#define DUCKDBK_SHIM_H

/*
 * C-шов библиотеки duckdb-kmp: единственный слой, который знает про DuckDB.
 *
 * Всё, что выше (Kotlin), работает с этими функциями. Шов намеренно
 * сишный и не тянет ни одного C++ символа в линковку: официальные
 * библиотеки DuckDB собраны современным GCC/glibc, а Kotlin/Native линкует
 * через замороженный sysroot (glibc 2.19, libstdc++ от GCC 8.3). Поэтому
 * DuckDB грузится dlopen'ом, а адреса функций берутся dlsym'ом — тогда
 * один и тот же шов годится и для JNI (JVM/Android), и для cinterop
 * (Kotlin/Native), и для любого таргета, где есть POSIX dl.
 *
 * Соглашения:
 *  - все функции возвращают 0 при успехе и отрицательный код при ошибке;
 *  - строки из шва — malloc-копии, освобождаются ddbt_free;
 *  - handle — непрозрачный int64, внутри указатель на структуру шва.
 */

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Коды ошибок, одинаковые для всех функций. */
#define DDBT_OK 0
#define DDBT_ERR_LOAD (-1)      /* libduckdb не загрузилась / нет символов */
#define DDBT_ERR_NOMEM (-2)
#define DDBT_ERR_OPEN (-3)      /* duckdb_open */
#define DDBT_ERR_CONNECT (-4)   /* duckdb_connect */
#define DDBT_ERR_SQL (-11)      /* ошибка SQL: текст в out_text */
#define DDBT_ERR_PREPARE (-12)
#define DDBT_ERR_BIND (-13)
#define DDBT_ERR_RESULT (-14)
#define DDBT_ERR_ARG (-20)
#define DDBT_ERR_CLOSED (-21)
#define DDBT_ERR_UNSUPPORTED (-22)

/* Типы колонок в терминах pw.binom.db.ColumnType (см. ddbt_column_type). */
#define DDBT_COL_NULL 0
#define DDBT_COL_BIT 1
#define DDBT_COL_TINYINT 2
#define DDBT_COL_SMALLINT 3
#define DDBT_COL_INTEGER 4
#define DDBT_COL_BIGINT 5
#define DDBT_COL_FLOAT 6
#define DDBT_COL_REAL 7
#define DDBT_COL_DOUBLE 8
#define DDBT_COL_NUMERIC 9
#define DDBT_COL_DECIMAL 10
#define DDBT_COL_CHAR 11
#define DDBT_COL_VARCHAR 12
#define DDBT_COL_LONGVARCHAR 13
#define DDBT_COL_DATE 14
#define DDBT_COL_TIME 15
#define DDBT_COL_TIMESTAMP 16
#define DDBT_COL_BINARY 17
#define DDBT_COL_VARBINARY 18
#define DDBT_COL_LONGVARBINARY 19
#define DDBT_COL_UUID 20
#define DDBT_COL_OTHER 21

/* ---- жизнь -------------------------------------------------------- */

/* Загружает libduckdb (или проверяет, что уже загружена). */
int32_t ddbt_init(void);

/* Текст последней ошибки загрузки/операции (статическая строка, не free'ится). */
const char *ddbt_last_error(void);

/* Версия движка, malloc-копия. */
char *ddbt_version(void);

/* Открывает базу: path == NULL или "" — in-memory. */
int32_t ddbt_open(const char *path, int32_t read_only, int64_t *out_handle);

void ddbt_close(int64_t handle);

/* ---- запросы без параметров --------------------------------------- */

/* Исполняет SQL. Строки результата не материализуются: нужен ddbt_cursor. */
int32_t ddbt_execute(int64_t handle, const char *sql, int64_t *out_rows_changed, char **out_error);

/* ---- подготовленные запросы --------------------------------------- */

int32_t ddbt_prepare(int64_t handle, const char *sql, int64_t *out_stmt, char **out_error);
void ddbt_destroy_statement(int64_t stmt);
int32_t ddbt_nparams(int64_t stmt);

int32_t ddbt_bind_null(int64_t stmt, int32_t index);
int32_t ddbt_bind_bool(int64_t stmt, int32_t index, int32_t value);
int32_t ddbt_bind_int8(int64_t stmt, int32_t index, int8_t value);
int32_t ddbt_bind_int16(int64_t stmt, int32_t index, int16_t value);
int32_t ddbt_bind_int32(int64_t stmt, int32_t index, int32_t value);
int32_t ddbt_bind_int64(int64_t stmt, int32_t index, int64_t value);
int32_t ddbt_bind_float(int64_t stmt, int32_t index, float value);
int32_t ddbt_bind_double(int64_t stmt, int32_t index, double value);
int32_t ddbt_bind_text(int64_t stmt, int32_t index, const char *value, int32_t len);
int32_t ddbt_bind_blob(int64_t stmt, int32_t index, const void *value, int32_t len);
/* Микросекунды с эпохи Unix. */
int32_t ddbt_bind_timestamp_micros(int64_t stmt, int32_t index, int64_t micros);
/* UUID как 16 байт в порядке RFC 4122 (big-endian). */
int32_t ddbt_bind_uuid(int64_t stmt, int32_t index, const uint8_t *bytes16);

/*
 * Исполняет подготовленный запрос.
 * Если запрос возвращает строки, *out_cursor != 0 и его надо читать
 * ddbt_cursor_next; иначе *out_rows_changed содержит счётчик.
 */
int32_t ddbt_execute_prepared(int64_t stmt, int64_t *out_cursor, int64_t *out_rows_changed, char **out_error);

/* ---- курсор ------------------------------------------------------- */

int32_t ddbt_cursor_column_count(int64_t cursor);
/* Имя колонки, malloc-копия. */
char *ddbt_cursor_column_name(int64_t cursor, int32_t index);
/* Тип колонки: одна из констант DDBT_COL_*. */
int32_t ddbt_cursor_column_type(int64_t cursor, int32_t index);

/* 1 — строка есть, 0 — кончились, <0 — ошибка. */
int32_t ddbt_cursor_next(int64_t cursor);

int32_t ddbt_cursor_is_null(int64_t cursor, int32_t column);
int32_t ddbt_cursor_get_bool(int64_t cursor, int32_t column, int32_t *out);
int32_t ddbt_cursor_get_int64(int64_t cursor, int32_t column, int64_t *out);
int32_t ddbt_cursor_get_double(int64_t cursor, int32_t column, double *out);
/* Текст: malloc-копия в *out, длина в *out_len. */
int32_t ddbt_cursor_get_text(int64_t cursor, int32_t column, char **out, int32_t *out_len);
/* Бинарные данные: malloc-копия в *out, длина в *out_len. */
int32_t ddbt_cursor_get_blob(int64_t cursor, int32_t column, void **out, int32_t *out_len);
/* Микросекунды с эпохи Unix. */
int32_t ddbt_cursor_get_timestamp_micros(int64_t cursor, int32_t column, int64_t *out);
int32_t ddbt_cursor_get_uuid(int64_t cursor, int32_t column, uint8_t *out16);

void ddbt_cursor_close(int64_t cursor);

/* ---- память ------------------------------------------------------- */

void ddbt_free(void *ptr);

#ifdef __cplusplus
}
#endif

#endif
