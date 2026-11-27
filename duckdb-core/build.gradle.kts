import pw.binom.kotlin.clang.addStatic
import pw.binom.kotlin.clang.clangBuildJni
import pw.binom.kotlin.clang.clangBuildStatic
import pw.binom.kotlin.clang.compileTaskName
import pw.binom.kotlin.clang.eachNative
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget

plugins {
    /* Публикация в Maven Central. Конфигурация POM/подписи — в корневом
     * build.gradle.kts (subprojects + withPlugin), чтобы не дублировать её. */
    alias(libs.plugins.vanniktech.maven.publish)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kn.clang)
    /* AAR нужен потребителям на Android JVM/ART: без androidTarget
     * артефакт `-jvm` имеет атрибут standard-jvm, и AGP его не берёт. */
    alias(libs.plugins.android.library)
}

group = "pw.binom.db"
version = rootProject.property("version") as String

val KOTLIN_VERSION = libs.versions.kotlin.get()

/*
 * C-шов лежит в src/nativeMain/c и собирается в статический архив на каждый
 * native-таргет. Шов — чистый C, поэтому его сборка не зависит от того,
 * есть ли на хосте C++-заголовки, и одинаково проходит на Linux и macOS.
 */
val SHIM_DIR = layout.projectDirectory.dir("src/nativeMain/c")
val JNI_DIR = layout.projectDirectory.dir("src/jvmMain/c")

/*
 * Android JNI: `jni/<abi>/libduckdbk.so` внутри AAR — единственное место,
 * откуда Android-линкер грузит нативную библиотеку. Копирование в это
 * дерево делают задачи ниже, `preBuild` на них завязан.
 */
val ANDROID_JNI_LIBS_DIR = layout.buildDirectory.dir("androidJniLibs")
val androidJniCopyTasks = mutableListOf<TaskProvider<*>>()

kotlin {
    /*
     * Иерархия: jvm + android делят JNI-реализацию (jvmSharedMain),
     * native-таргеты — cinterop-реализацию. Два независимых плеча,
     * общего кода между ними нет, кроме контрактов в commonMain.
     */
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withJvm()
                withAndroidTarget()
            }
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    /*
     * Android JVM/ART: AAR с кодом jvmSharedMain и bionic libduckdbk.so
     * в jniLibs. Независим от androidNative* ниже — те дают klib для
     * Kotlin/Native-потребителей.
     *
     * Оба варианта публикуются: Android-приложение несёт
     * BuildTypeAttr=debug, и без debug-варианта Gradle молча берёт
     * klib-вариант без jniLibs, который на Android не грузится.
     */
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        publishLibraryVariants("release", "debug")
    }

    /* Desktop + сервер + Windows. */
    linuxX64()
    linuxArm64()
    macosX64()
    macosArm64()
    mingwX64()

    /* Apple: телефон, планшет, часы, ТВ. Собираются только на macOS-хосте,
     * на Linux объявлены и автоматически отключены плагином. */
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    tvosX64()
    tvosArm64()
    tvosSimulatorArm64()
    watchosX64()
    watchosArm32()
    watchosArm64()
    watchosSimulatorArm64()
    /*
     * watchosDeviceArm64 объявляется, и он СБИРАЕТСЯ.
     *
     * Ранее он падал с
     *   ddbt_shim.c:25:10: fatal error: 'stdio.h' file not found
     * и я ошибочно решил, что sysroot для него не публикуется.
     * Проверка лога показала обратное: K/N ПИШЕТ
     *   Sysroot watchos_device_arm64 is being installed
     *   Exists: {...target-sysroot-xcode_26.4_17E192-watchos=false}
     * то есть sysroot существует и ставится — просто позже, чем clang
     * начинал компилировать C-шов.
     *
     * Настоящая причина — гонка: kn-clang вызывает clang напрямую, минуя
     * K/N, и установку sysroot не триггерит. Лечится прогревом
     * (шаг Pre-warm watchOS sysroots в CI): он ставит sysroot ДО того,
     * как соберётся хоть один watchOS-klib.
     */
    watchosDeviceArm64()

    /* Android NDK (bionic) как klib — для потребителей на Kotlin/Native. */
    androidNativeArm32()
    androidNativeArm64()
    androidNativeX86()
    androidNativeX64()

    eachNative {
        val shim = clangBuildStatic(target = konanTarget, name = "ddbt_shim") {
            konanVersion.set(KOTLIN_VERSION)
            compileArgs("-std=gnu99")
            /*
             * _GNU_SOURCE нужен для dlopen/dlsym в glibc-профиле sysroot'а
             * Kotlin/Native: без него <dlfcn.h> объявляет их только при
             * _POSIX_C_SOURCE >= 200112L. На bionic/Apple макрос безвреден.
             */
            compileArgs("-D_GNU_SOURCE")
            optimizationLevel(2)
            /* ВАЖНО: include()/compileFile() понимают только File/Provider/String
             * (Project.fileAnyWay), а Directory молча игнорируется —
             * отсюда "ddbt_shim.h not found" на JNI-сборке. */
            include(SHIM_DIR.asFile)
            compileFile(SHIM_DIR.file("ddbt_shim.c").asFile)
        }
        shim.dependsOn(tasks.named("downloadKotlinNativeDistribution"))
        tasks.findByName(compileTaskName)?.dependsOn(shim)

        binaries {
            compilations["main"].apply {
                /*
                 * ВСЁ, что линкуется в бинарник, — один сишный архив шва.
                 * Сам DuckDB остаётся динамической библиотекой и грузится
                 * dlopen'ом в рантайме: статические архивы из официальных
                 * релизов собраны новым GCC/glibc и с sysroot'ом
                 * Kotlin/Native (glibc 2.19, libstdc++ 8.3) не сходятся —
                 * проверено, список неразрешённых символов в README.
                 */
                addStatic(shim.staticFile)
                cinterops {
                    create("ddbt") {
                        defFile = project.file("src/nativeInterop/ddbt.def")
                        packageName = "pw.binom.db.duckdb.internal"
                        includeDirs(SHIM_DIR.asFile.absolutePath)
                    }
                }
            }
            binaries.configureEach {
                /*
                 * dlopen/dlsym. На glibc это libdl, на Apple/Android/musl
                 * они внутри libSystem/libc — линкер лишний -ldl на Apple
                 * отвергает, поэтому только Linux и Android.
                 */
                when (konanTarget.family) {
                    Family.LINUX, Family.ANDROID -> linkerOpts("-ldl")
                    else -> Unit
                }
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
        /*
         * Android-тесты — только instrumentation: на JVM нельзя загрузить
         * bionic .so. Unit-тесты Android-варианта ограничены общими типами
         * и ничего не доказывают, поэтому здесь их нет.
         */
    }
}

/*
 * JNI: `clangBuildJni` из kn-clang сам подставляет jni.h/jni_md.h нужного
 * таргета и корректно пропускает кросс-таргеты, для которых на хосте нет
 * заголовков (Linux-JDK не содержит include/win32).
 *
 * Android-таргеты идут отдельным вызовом: clangBuildJni ждёт JDK-заголовки,
 * а им нужны NDK-шные, которые kn-clang достаёт сам.
 */
val jvmJniTargets = buildList {
    add(KonanTarget.LINUX_X64)
    add(KonanTarget.LINUX_ARM64)
    add(KonanTarget.MINGW_X64)
    if (HostManager.host.family == Family.OSX) add(HostManager.host)
}

/*
 * Кросс-JNI заголовки.
 *
 * Хостовый JDK несёт `jni_md.h` только для своей платформы: в Linux-JDK нет
 * `include/win32`, в macOS-JDK — ни `linux`, ни `win32`. Без override
 * `clangBuildJni` молча ставит `SKIPPED` кросс-таргету — и `.dll`/`.dylib`
 * не попадает в jar, а потребитель на той ОС падает на `UnsatisfiedLinkError`.
 *
 * Проверено на этом проекте: до override в jar лежали только
 * `linux_x64/libduckdbk.so` и `linux_arm64/libduckdbk.so`, mingw
 * отсутствовал. CI провижит `build/jni-include/{linux,win32}/jni_md.h`
 * (см. .github/workflows/release.yml); локально каталог может не
 * существовать — тогда override просто не подхватится (`File.isFile`
 * проверяется в JdkIncludeResolver).
 */
val JNI_HEADERS_OVERRIDE = layout.buildDirectory.dir("jni-include").get().asFile

/*
 * Кросс-JNI заголовки — ОБЯЗАТЕЛЬНО на этапе конфигурации.
 *
 * 🔴 Плагин kn-clang вычисляет `jniUsable` в момент конфигурации проекта, а
 * не при выполнении задачи:
 *
 *     val jdk = JdkIncludeResolver.resolve(target, effectiveOverride)
 *     val jniUsable = jdk.isUsable()          // ← configuration time
 *     onlyIf("...") { isHost || jniUsable }   // ← execution time
 *
 * Поэтому Gradle-задача `provisionCrossJniHeaders` НЕ РАБОТАЕТ: она
 * выполнится уже после того, как флаг посчитан, и mingw всё равно получит
 * SKIPPED. Проверено — качал заголовки задачей, `buildJniDuckdbkMingwX64`
 * остался SKIPPED, а `.dll` не попал в jar.
 *
 * Отсюда и схема serial-kmp: curl в отдельном CI-шаге ДО вызова gradlew.
 * Здесь то же самое, но без отдельного шага: файлы докачиваются при
 * конфигурации, если их нет.
 *
 * Хостовый JDK несёт `jni_md.h` только для своей платформы: в Linux-JDK нет
 * `include/win32`, в macOS-JDK — ни `linux`, ни `win32`. Без override кросс-
 * таргет молча теряется, и потребитель на той ОС падает на
 * UnsatisfiedLinkError в рантайме.
 *
 * darwin сюда НЕ входит: macOS-хост собирает macOS-JNI своим JDK, а
 * Linux-хосту darwin-заголовок не нужен (macOS JVM-библиотека не собирается
 * с Linux — Apple-таргеты требуют macOS-хоста).
 */
val JNI_HEADER_URLS = mapOf(
    "linux" to "https://raw.githubusercontent.com/openjdk/jdk21u/master/src/java.base/unix/native/include/jni_md.h",
    "win32" to "https://raw.githubusercontent.com/openjdk/jdk21u/master/src/java.base/windows/native/include/jni_md.h",
)

JNI_HEADER_URLS.forEach { (platform, url) ->
    val dir = File(JNI_HEADERS_OVERRIDE, platform)
    val file = File(dir, "jni_md.h")
    if (!file.isFile) {
        dir.mkdirs()
        val ok = runCatching {
            val proc = ProcessBuilder("curl", "-fsSL", "--max-time", "30", url, "-o", file.absolutePath)
                .redirectErrorStream(true).start()
            proc.waitFor() == 0 && file.isFile
        }.getOrDefault(false)
        if (ok) {
            logger.lifecycle("kn-jni: скачан jni_md.h[$platform]")
        } else {
            file.delete()
            logger.warn(
                "kn-jni: нет jni_md.h[$platform] и не удалось скачать — " +
                    "кросс-таргет семейства '$platform' будет SKIPPED " +
                    "(в jar не попадёт соответствующая библиотека). " +
                    "В CI качается отдельным шагом до gradlew."
            )
        }
    }
}

val jvmJniBuilds = clangBuildJni(
    name = "duckdbk",
    targets = jvmJniTargets,
    jniHeadersOverride = JNI_HEADERS_OVERRIDE,
) {
    konanVersion.set(KOTLIN_VERSION)
    compileArgs("-std=gnu99", "-D_GNU_SOURCE")
    optimizationLevel(2)
    include(SHIM_DIR.asFile)
    compileFile(SHIM_DIR.file("ddbt_shim.c").asFile)
    compileFile(JNI_DIR.file("ddbt_jni.c").asFile)
}

val jvmJniCopyTasks = jvmJniTargets.zip(jvmJniBuilds).map { (target, build) ->
    val ext = when (target.family) {
        Family.MINGW -> "dll"
        Family.OSX -> "dylib"
        else -> "so"
    }
    val outDir = layout.buildDirectory.dir("processed-resources/native/${target.name}")
    tasks.register("copyDuckdbkJni${target.name}", Copy::class.java) {
        from(build.dynamicFile)
        rename { "libduckdbk.$ext" }
        into(outDir)
    }
}

tasks.named("jvmProcessResources", Copy::class.java) {
    dependsOn(jvmJniCopyTasks)
    from(layout.buildDirectory.dir("processed-resources/native"))
    include("**/*.so", "**/*.dylib", "**/*.dll")
}

/*
 * Тесты должны видеть собранную .so: `jvmTest` берёт ресурсы из
 * processedResources, но задачи копирования не были в его графе — тесты
 * падали с «в jar нет нативной библиотеки», хотя .so уже собрана.
 */
tasks.named("jvmTest") {
    dependsOn("jvmProcessResources")
}

// --------------------------------------------------------------- Android

/*
 * Android ABI: bionic-сборки шва+JNI. Собираются тем же kn-clang; NDK-sysroot
 * kn-clang скачивает сам при первой сборке статики для этого таргета.
 */
val androidAbiByTarget = linkedMapOf(
    KonanTarget.ANDROID_ARM64 to "arm64-v8a",
    KonanTarget.ANDROID_X64 to "x86_64",
    KonanTarget.ANDROID_ARM32 to "armeabi-v7a",
    KonanTarget.ANDROID_X86 to "x86",
)

val androidJniBuilds = androidAbiByTarget.keys.map { target ->
    target to clangBuildStatic(
        target = target,
        name = "ddbt_shim_android",
        taskName = "buildAndroidStatic${target.name.replaceFirstChar { it.uppercase() }}",
    ) {
        konanVersion.set(KOTLIN_VERSION)
        compileArgs("-std=gnu99", "-D_GNU_SOURCE")
        optimizationLevel(2)
        include(SHIM_DIR.asFile)
        compileFile(SHIM_DIR.file("ddbt_shim.c").asFile)
    }
}

androidAbiByTarget.forEach { (target, abi) ->
    val dy = clangBuildJni(name = "duckdbk", targets = listOf(target)) {
        konanVersion.set(KOTLIN_VERSION)
        compileArgs("-std=gnu99", "-D_GNU_SOURCE")
        optimizationLevel(2)
        include(SHIM_DIR.asFile)
        compileFile(SHIM_DIR.file("ddbt_shim.c").asFile)
        compileFile(JNI_DIR.file("ddbt_jni.c").asFile)
    }.first()
    androidJniCopyTasks += tasks.register("copyAndroidDuckdbk$abi", Copy::class.java) {
        from(dy.dynamicFile)
        rename { "libduckdbk.so" }
        into(ANDROID_JNI_LIBS_DIR.map { it.dir(abi) })
    }
}

android {
    namespace = "pw.binom.db.duckdb"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].jniLibs.srcDir(ANDROID_JNI_LIBS_DIR)
}

afterEvaluate {
    tasks.named("preBuild") {
        dependsOn(androidJniCopyTasks)
    }
}
