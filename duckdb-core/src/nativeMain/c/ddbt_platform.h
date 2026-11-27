#ifndef DDBT_PLATFORM_H
#define DDBT_PLATFORM_H

/*
 * Платформенная загрузка разделяемой библиотеки.
 *
 * POSIX (Linux/macOS/Android): dlopen/dlsym — объявлены в <dlfcn.h>,
 * для glibc нужен _GNU_SOURCE (его ставит сборка).
 *
 * Windows: <dlfcn.h> не существует, там LoadLibrary/GetProcAddress.
 * Абстракция на две функции + один код ошибки — минимальная, чтобы
 * ddbt_shim.c оставался одним файлом и не разветвлялся на каждом вызове.
 */

#include <stddef.h>
#include <stdint.h>

#ifdef _WIN32

#include <windows.h>

typedef HMODULE ddbt_lib_t;
typedef FARPROC ddbt_sym_t;

static inline ddbt_lib_t ddbt_lib_open(const char *name) {
    /* LoadLibraryA без пути ищет в каталоге приложения и в PATH — то же
     * поведение, что у dlopen с именем без слэша. */
    return LoadLibraryA(name);
}

static inline ddbt_sym_t ddbt_lib_sym(ddbt_lib_t lib, const char *name) {
    return GetProcAddress(lib, name);
}

static inline const char *ddbt_lib_error(void) {
    return "LoadLibrary/GetProcAddress: ошибка (код в GetLastError)";
}

#else

#include <dlfcn.h>

typedef void *ddbt_lib_t;
typedef void *ddbt_sym_t;

static inline ddbt_lib_t ddbt_lib_open(const char *name) {
    return dlopen(name, RTLD_NOW | RTLD_GLOBAL);
}

static inline ddbt_sym_t ddbt_lib_sym(ddbt_lib_t lib, const char *name) {
    return dlsym(lib, name);
}

static inline const char *ddbt_lib_error(void) {
    const char *e = dlerror();
    return e != NULL ? e : "dlopen: неизвестная ошибка";
}

#endif

#endif
