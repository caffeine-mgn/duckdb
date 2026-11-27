import pw.binom.kotlin.clang.addStatic
import pw.binom.kotlin.clang.clangBuildJni
import pw.binom.kotlin.clang.clangBuildStatic
import pw.binom.kotlin.clang.compileTaskName
import pw.binom.kotlin.clang.eachNative
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kn.clang)
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

kotlin {
    /*
     * Иерархия: jvm + android делят JNI-реализацию, native-таргеты —
     * cinterop-реализацию. Два независимых плеча, общего кода между ними
     * нет, кроме контрактов в commonMain.
     */
    applyDefaultHierarchyTemplate()

    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    linuxX64()
    linuxArm64()
    macosX64()
    macosArm64()
    mingwX64()

    eachNative {
        val shim = clangBuildStatic(target = konanTarget, name = "ddbt_shim") {
            konanVersion.set(KOTLIN_VERSION)
            compileArgs("-std=gnu99")
            /*
             * _GNU_SOURCE нужен для dlopen/dlsym в glibc-профиле sysroot'а
             * Kotlin/Native: без него <dlfcn.h> объявляет их только при
             * _POSIX_C_SOURCE >= 200112L.
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
                /* dlopen/dlsym — из libdl; на musl/BSD она внутри libc,
                 * линкер это стерпит. */
                linkerOpts("-ldl")
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
        val jvmMain by getting {
            dependencies {
                /*
                 * JNI-путь не тянет ни JNA, ни JNI-обёрток: нативный метод
                 * объявлен в jvmMain, реализация — в src/jvmMain/c.
                 */
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
        val nativeMain by getting {
            /* Ничего: cinterop генерирует pw.binom.db.duckdb.internal сам. */
        }
    }
}

/*
 * JNI: `clangBuildJni` из kn-clang сам подставляет jni.h/jni_md.h нужного
 * таргета и корректно пропускает кросс-таргеты, для которых на хосте нет
 * заголовков (Linux-JDK не содержит include/win32).
 */
val jniTargets = buildList {
    add(KonanTarget.LINUX_X64)
    add(KonanTarget.LINUX_ARM64)
    add(KonanTarget.MINGW_X64)
    if (HostManager.host.family == Family.OSX) add(HostManager.host)
}

val jniBuilds = clangBuildJni(name = "duckdbk", targets = jniTargets) {
    konanVersion.set(KOTLIN_VERSION)
    compileArgs("-std=gnu99", "-D_GNU_SOURCE")
    optimizationLevel(2)
    include(SHIM_DIR.asFile)
    compileFile(SHIM_DIR.file("ddbt_shim.c").asFile)
    compileFile(JNI_DIR.file("ddbt_jni.c").asFile)
}

val jniCopyTasks = jniTargets.zip(jniBuilds).map { (target, build) ->
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
    dependsOn(jniCopyTasks)
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
