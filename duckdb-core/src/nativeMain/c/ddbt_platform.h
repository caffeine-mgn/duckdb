#ifndef DDBT_PLATFORM_H
#define DDBT_PLATFORM_H

/*
 * Платформенная загрузка разделяемой библиотеки — три режима.
 *
 * Windows: <dlfcn.h> не существует, там LoadLibrary/GetProcAddress.
 *
 * POSIX (Linux/Android/macOS): dlopen/dlsym из <dlfcn.h>.
 *
 * iOS/tvOS/watchOS: <dlfcn.h> в SDK этих платформ ОТСУТСТВУЕТ —
 * динамическая загрузка сторонних библиотек там запрещена правилами
 * платформы. Сборка падала с
 *
 *   ddbt_platform.h:41:10: fatal error: 'dlfcn.h' file not found
 *
 * на `tvosArm64Cinterop` в CI на macOS-раннере: macOS-срез собирается,
 * а tvOS — нет.
 *
 * Поэтому наличие заголовка проверяется через `__has_include`, и при его
 * отсутствии подставляется заглушка с ВНЯТНОЙ ошибкой в рантайме.
 * Молча собирать klib, который потом упадёт на `dlopen` непонятно
 * почему, — хуже, чем вернуть «на этой платформе загрузка невозможна».
 *
 * Абстракция — две функции и один код ошибки, чтобы ddbt_shim.c оставался
 * одним файлом без ветвлений на каждом вызове.
 */

#include <stddef.h>
#include <stdint.h>

/* Есть ли настоящий dlopen на этой платформе. */
#if !defined(_WIN32) && defined(__has_include)
#  if __has_include(<dlfcn.h>)
#    define DDBT_HAVE_DLOPEN 1
#  endif
#endif
/* Компиляторы без __has_include (старый GCC) — считаем, что dlopen есть:
 * на поддерживаемых нами Linux/Android/macOS он действительно есть. */
#if !defined(_WIN32) && !defined(__has_include) && !defined(DDBT_NO_DLOPEN)
#  define DDBT_HAVE_DLOPEN 1
#endif

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

#elif defined(DDBT_HAVE_DLOPEN)

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

#else
/* iOS / tvOS / watchOS: динамической загрузки нет по правилам платформы.
 * Заглушка нужна, чтобы klib собирался; попытка открыть библиотеку
 * вернёт NULL и внятное сообщение. */

typedef void *ddbt_lib_t;
typedef void *ddbt_sym_t;

static inline ddbt_lib_t ddbt_lib_open(const char *name) {
    (void) name;
    return NULL;
}

static inline ddbt_sym_t ddbt_lib_sym(ddbt_lib_t lib, const char *name) {
    (void) lib;
    (void) name;
    return NULL;
}

static inline const char *ddbt_lib_error(void) {
    return "динамическая загрузка библиотек недоступна на этой платформе "
           "(iOS/tvOS/watchOS запрещают dlopen сторонних библиотек)";
}

#endif

#endif
