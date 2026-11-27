/*
 * JNI-мост к C-шву duckdb-kmp.
 *
 * Мост намеренно «тупой»: одна JNI-функция = один вызов ddbt_*. Вся логика
 * (классификация ошибок, приведение типов, жизненный цикл) — в Kotlin,
 * потому что её дешевле отлаживать там и она не зависит от платформы.
 *
 * Соглашение об ошибках:
 *  - функции, у которых в ddbt_* есть out_error, пробрасывают текст
 *    исключением Java (IllegalStateException для SQL, RuntimeException для
 *    bind/load) — Kotlin на той стороне превращает его в DuckDbException;
 *  - остальные возвращают код, и Kotlin сам читает ddbt_last_error().
 * Так границу JNI пересекает либо значение, либо исключение, но не оба.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "ddbt_shim.h"

static void throw_new(JNIEnv *env, const char *cls, const char *msg) {
    jclass c = (*env)->FindClass(env, cls);
    if (c != NULL) {
        (*env)->ThrowNew(env, c, msg != NULL ? msg : "");
        (*env)->DeleteLocalRef(env, c);
    }
}

static void throw_sql(JNIEnv *env, const char *msg) {
    throw_new(env, "java/lang/IllegalStateException", msg);
}

static void throw_state(JNIEnv *env, const char *msg) {
    throw_new(env, "java/lang/IllegalArgumentException", msg);
}

/* Освобождает malloc-буфер, если он есть, и обнуляет указатель. */
static void free_if(char **p) {
    if (p != NULL && *p != NULL) {
        ddbt_free(*p);
        *p = NULL;
    }
}

static jstring take_string(JNIEnv *env, char *s) {
    if (s == NULL) {
        return NULL;
    }
    jstring out = (*env)->NewStringUTF(env, s);
    /* NewStringUTF сам делает копию — исходный буфер свободен. */
    ddbt_free(s);
    return out;
}

JNIEXPORT jint JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeInit(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    return ddbt_init();
}

JNIEXPORT jstring JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeLastError(JNIEnv *env, jobject thiz) {
    (void)thiz;
    const char *e = ddbt_last_error();
    return e == NULL ? NULL : (*env)->NewStringUTF(env, e);
}

JNIEXPORT jstring JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeVersion(JNIEnv *env, jobject thiz) {
    (void)thiz;
    return take_string(env, ddbt_version());
}

JNIEXPORT jlong JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeOpen(JNIEnv *env, jobject thiz, jstring path, jboolean readOnly) {
    (void)thiz;
    const char *p = NULL;
    if (path != NULL) {
        p = (*env)->GetStringUTFChars(env, path, NULL);
    }
    int64_t handle = 0;
    /* Код ошибки не пробрасываем исключением: Kotlin читает ddbt_last_error()
     * и сам решает, какой класс DuckDbException бросить (загрузка или открытие). */
    ddbt_open(p, readOnly ? 1 : 0, &handle);
    if (path != NULL) {
        (*env)->ReleaseStringUTFChars(env, path, p);
    }
    return (jlong)handle;
}

JNIEXPORT void JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeClose(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    ddbt_close((int64_t)handle);
}

JNIEXPORT jlong JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeExecute(JNIEnv *env, jobject thiz, jlong handle, jstring sql) {
    (void)thiz;
    const char *s = (*env)->GetStringUTFChars(env, sql, NULL);
    int64_t rows = 0;
    char *err = NULL;
    int32_t rc = ddbt_execute((int64_t)handle, s, &rows, &err);
    (*env)->ReleaseStringUTFChars(env, sql, s);
    if (rc != 0) {
        throw_sql(env, err != NULL ? err : ddbt_last_error());
        free_if(&err);
        return 0;
    }
    free_if(&err);
    return (jlong)rows;
}

JNIEXPORT jlong JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativePrepare(JNIEnv *env, jobject thiz, jlong handle, jstring sql) {
    (void)thiz;
    const char *s = (*env)->GetStringUTFChars(env, sql, NULL);
    int64_t stmt = 0;
    char *err = NULL;
    int32_t rc = ddbt_prepare((int64_t)handle, s, &stmt, &err);
    (*env)->ReleaseStringUTFChars(env, sql, s);
    if (rc != 0) {
        throw_sql(env, err != NULL ? err : ddbt_last_error());
        free_if(&err);
        return 0;
    }
    free_if(&err);
    return (jlong)stmt;
}

JNIEXPORT jint JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeParameterCount(JNIEnv *env, jobject thiz, jlong stmt) {
    (void)env;
    (void)thiz;
    return ddbt_nparams((int64_t)stmt);
}

JNIEXPORT void JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeDestroyStatement(JNIEnv *env, jobject thiz, jlong stmt) {
    (void)env;
    (void)thiz;
    ddbt_destroy_statement((int64_t)stmt);
}

/* ---- bind'ы ------------------------------------------------------- */

JNIEXPORT void JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeBindNull(JNIEnv *env, jobject thiz, jlong s, jint i) {
    (void)thiz;
    if (ddbt_bind_null((int64_t)s, i) != 0) throw_state(env, ddbt_last_error());
}

JNIEXPORT void JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeBindBool(JNIEnv *env, jobject thiz, jlong s, jint i, jboolean v) {
    (void)thiz;
    if (ddbt_bind_bool((int64_t)s, i, v ? 1 : 0) != 0) throw_state(env, ddbt_last_error());
}

JNIEXPORT void JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeBindInt8(JNIEnv *env, jobject thiz, jlong s, jint i, jbyte v) {
    (void)thiz;
    if (ddbt_bind_int8((int64_t)s, i, (int8_t)v) != 0) throw_state(env, ddbt_last_error());
}

JNIEXPORT void JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeBindInt16(JNIEnv *env, jobject thiz, jlong s, jint i, jshort v) {
    (void)thiz;
    if (ddbt_bind_int16((int64_t)s, i, (int16_t)v) != 0) throw_state(env, ddbt_last_error());
}

JNIEXPORT void JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeBindInt32(JNIEnv *env, jobject thiz, jlong s, jint i, jint v) {
    (void)thiz;
    if (ddbt_bind_int32((int64_t)s, i, v) != 0) throw_state(env, ddbt_last_error());
}

JNIEXPORT void JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeBindInt64(JNIEnv *env, jobject thiz, jlong s, jint i, jlong v) {
    (void)thiz;
    if (ddbt_bind_int64((int64_t)s, i, (int64_t)v) != 0) throw_state(env, ddbt_last_error());
}

JNIEXPORT void JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeBindFloat(JNIEnv *env, jobject thiz, jlong s, jint i, jfloat v) {
    (void)thiz;
    if (ddbt_bind_float((int64_t)s, i, (float)v) != 0) throw_state(env, ddbt_last_error());
}

JNIEXPORT void JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeBindDouble(JNIEnv *env, jobject thiz, jlong s, jint i, jdouble v) {
    (void)thiz;
    if (ddbt_bind_double((int64_t)s, i, (double)v) != 0) throw_state(env, ddbt_last_error());
}

JNIEXPORT void JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeBindText(JNIEnv *env, jobject thiz, jlong s, jint i, jstring v) {
    (void)thiz;
    const char *txt = (*env)->GetStringUTFChars(env, v, NULL);
    int32_t rc = ddbt_bind_text((int64_t)s, i, txt, (int32_t)strlen(txt));
    (*env)->ReleaseStringUTFChars(env, v, txt);
    if (rc != 0) throw_state(env, ddbt_last_error());
}

JNIEXPORT void JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeBindBytes(JNIEnv *env, jobject thiz, jlong s, jint i, jbyteArray v) {
    (void)thiz;
    jsize len = (*env)->GetArrayLength(env, v);
    jbyte *buf = (*env)->GetByteArrayElements(env, v, NULL);
    int32_t rc = ddbt_bind_blob((int64_t)s, i, buf, (int32_t)len);
    (*env)->ReleaseByteArrayElements(env, v, buf, JNI_ABORT);
    if (rc != 0) throw_state(env, ddbt_last_error());
}

JNIEXPORT void JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeBindTimestampMicros(JNIEnv *env, jobject thiz, jlong s, jint i, jlong micros) {
    (void)thiz;
    if (ddbt_bind_timestamp_micros((int64_t)s, i, (int64_t)micros) != 0) throw_state(env, ddbt_last_error());
}

JNIEXPORT void JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeBindUuid(JNIEnv *env, jobject thiz, jlong s, jint i, jbyteArray v) {
    (void)thiz;
    jsize len = (*env)->GetArrayLength(env, v);
    if (len != 16) {
        throw_state(env, "UUID должен быть 16 байт");
        return;
    }
    jbyte buf[16];
    (*env)->GetByteArrayRegion(env, v, 0, 16, buf);
    if (ddbt_bind_uuid((int64_t)s, i, (const uint8_t *)buf) != 0) throw_state(env, ddbt_last_error());
}

/*
 * Исполнение подготовленного запроса. Возвращает long[2]: {cursor, rowsChanged}.
 * Массив, а не два вызова, чтобы не потерять согласованность: результат
 * один, и оба числа принадлежат ему.
 */
JNIEXPORT jlongArray JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeExecutePrepared(JNIEnv *env, jobject thiz, jlong stmt) {
    (void)thiz;
    int64_t cursor = 0;
    int64_t rows = 0;
    char *err = NULL;
    int32_t rc = ddbt_execute_prepared((int64_t)stmt, &cursor, &rows, &err);
    if (rc != 0) {
        throw_sql(env, err != NULL ? err : ddbt_last_error());
        free_if(&err);
        return NULL;
    }
    free_if(&err);
    jlongArray out = (*env)->NewLongArray(env, 2);
    if (out == NULL) {
        return NULL;
    }
    jlong vals[2];
    vals[0] = (jlong)cursor;
    vals[1] = (jlong)rows;
    (*env)->SetLongArrayRegion(env, out, 0, 2, vals);
    return out;
}

/* ---- курсор ------------------------------------------------------- */

JNIEXPORT jint JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeCursorColumnCount(JNIEnv *env, jobject thiz, jlong c) {
    (void)env;
    (void)thiz;
    return ddbt_cursor_column_count((int64_t)c);
}

JNIEXPORT jstring JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeCursorColumnName(JNIEnv *env, jobject thiz, jlong c, jint i) {
    (void)thiz;
    return take_string(env, ddbt_cursor_column_name((int64_t)c, i));
}

JNIEXPORT jint JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeCursorColumnType(JNIEnv *env, jobject thiz, jlong c, jint i) {
    (void)env;
    (void)thiz;
    return ddbt_cursor_column_type((int64_t)c, i);
}

JNIEXPORT jboolean JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeCursorNext(JNIEnv *env, jobject thiz, jlong c) {
    (void)env;
    (void)thiz;
    return ddbt_cursor_next((int64_t)c) > 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeCursorIsNull(JNIEnv *env, jobject thiz, jlong c, jint i) {
    (void)env;
    (void)thiz;
    return ddbt_cursor_is_null((int64_t)c, i) != 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeCursorGetBoolean(JNIEnv *env, jobject thiz, jlong c, jint i) {
    (void)thiz;
    int32_t v = 0;
    if (ddbt_cursor_get_bool((int64_t)c, i, &v) != 0) {
        throw_state(env, ddbt_last_error());
        return JNI_FALSE;
    }
    return v != 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeCursorGetLong(JNIEnv *env, jobject thiz, jlong c, jint i) {
    (void)thiz;
    int64_t v = 0;
    if (ddbt_cursor_get_int64((int64_t)c, i, &v) != 0) {
        throw_state(env, ddbt_last_error());
        return 0;
    }
    return (jlong)v;
}

JNIEXPORT jdouble JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeCursorGetDouble(JNIEnv *env, jobject thiz, jlong c, jint i) {
    (void)thiz;
    double v = 0;
    if (ddbt_cursor_get_double((int64_t)c, i, &v) != 0) {
        throw_state(env, ddbt_last_error());
        return 0;
    }
    return (jdouble)v;
}

JNIEXPORT jstring JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeCursorGetString(JNIEnv *env, jobject thiz, jlong c, jint i) {
    (void)thiz;
    char *txt = NULL;
    int32_t len = 0;
    if (ddbt_cursor_get_text((int64_t)c, i, &txt, &len) != 0) {
        throw_state(env, ddbt_last_error());
        return NULL;
    }
    return take_string(env, txt);
}

JNIEXPORT jbyteArray JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeCursorGetBytes(JNIEnv *env, jobject thiz, jlong c, jint i) {
    (void)thiz;
    void *data = NULL;
    int32_t len = 0;
    if (ddbt_cursor_get_blob((int64_t)c, i, &data, &len) != 0) {
        throw_state(env, ddbt_last_error());
        return NULL;
    }
    if (data == NULL) {
        return NULL;
    }
    jbyteArray out = (*env)->NewByteArray(env, len);
    if (out != NULL) {
        (*env)->SetByteArrayRegion(env, out, 0, len, (const jbyte *)data);
    }
    ddbt_free(data);
    return out;
}

JNIEXPORT jlong JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeCursorGetTimestampMicros(JNIEnv *env, jobject thiz, jlong c, jint i) {
    (void)thiz;
    int64_t v = 0;
    if (ddbt_cursor_get_timestamp_micros((int64_t)c, i, &v) != 0) {
        throw_state(env, ddbt_last_error());
        return 0;
    }
    return (jlong)v;
}

JNIEXPORT jbyteArray JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeCursorGetUuid(JNIEnv *env, jobject thiz, jlong c, jint i) {
    (void)thiz;
    uint8_t buf[16];
    if (ddbt_cursor_get_uuid((int64_t)c, i, buf) != 0) {
        throw_state(env, ddbt_last_error());
        return NULL;
    }
    jbyteArray out = (*env)->NewByteArray(env, 16);
    if (out != NULL) {
        (*env)->SetByteArrayRegion(env, out, 0, 16, (const jbyte *)buf);
    }
    return out;
}

JNIEXPORT void JNICALL
Java_pw_binom_db_duckdb_JvmDuckDb_nativeCursorClose(JNIEnv *env, jobject thiz, jlong c) {
    (void)env;
    (void)thiz;
    ddbt_cursor_close((int64_t)c);
}
