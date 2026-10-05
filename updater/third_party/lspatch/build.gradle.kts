import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.BaseExtension
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import com.android.build.gradle.LibraryExtension

plugins {
    alias(libs.plugins.agp.lib) apply false
    alias(libs.plugins.agp.app) apply false
    alias(lspatch.plugins.compose.compiler) apply false
    alias(lspatch.plugins.kotlin.android) apply false
    alias(lspatch.plugins.diffplug.spotless) apply false
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.eclipse.jgit:org.eclipse.jgit:7.3.0.202506031305-r")
    }
}

// Count from the current HEAD, not a fixed remote branch: every commit -- on any branch, before it is
// pushed -- bumps the version code, so a build made after a new commit is never mistaken for the one
// before it (counting origin/master left branch builds sharing the master version code).
val commitCount = run {
    val repo = FileRepository(rootProject.file(".git"))
    val head = repo.resolve("HEAD")!!
    Git(repo).log().add(head).call().count()
}

val (coreCommitCount, coreLatestTag, coreCommitHash) = FileRepositoryBuilder()
    // Resolve the core's real git dir from its worktree rather than assuming `.git/modules/core`:
    // when the submodule is checked out as a full clone (its own `core/.git` directory), the modules
    // copy is stale — wrong HEAD, no tags — and would drop us to the "1.0" fallback. findGitDir
    // follows a gitdir-file too, so a fresh submodule clone still resolves correctly.
    .setWorkTree(rootProject.file("core"))
    .findGitDir()
    .runCatching {
        build().use { repo ->
            val git = Git(repo)
            val head = repo.refDatabase.exactRef("HEAD").objectId
            val coreCommitCount =
                git.log()
                    .add(head)
                    .call().count() + 4200
            val ver = git.describe()
                .setTags(true)
                .setAbbrev(0).call().removePrefix("v")
            // The exact Vector commit the core was built from, so the manager can link to it.
            Triple(coreCommitCount, ver, head.name)
        }
    }.getOrNull() ?: Triple(1, "1.0", "")

// sync from https://github.com/JingMatrix/LSPosed/blob/master/build.gradle.kts
val defaultManagerPackageName by extra("org.lsposed.lspatch")
val apiCode by extra(102)
val verCode by extra(commitCount)
val verName by extra("1.2")
val coreVerCode by extra(coreCommitCount)
val coreVerName by extra(coreLatestTag)
val coreVerHash by extra(coreCommitHash)
val androidMinSdkVersion by extra(28)
val androidTargetSdkVersion by extra(36)
val androidCompileSdkVersion by extra(37)
val androidCompileNdkVersion by extra("29.0.13113456")
val androidBuildToolsVersion by extra("37.0.0")
val androidSourceCompatibility by extra(JavaVersion.VERSION_21)
val androidTargetCompatibility by extra(JavaVersion.VERSION_21)

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}

listOf("Debug", "Release").forEach { variant ->
    tasks.register("build$variant") {
        description = "Build LSPatch with $variant"
        dependsOn(":jar:build$variant")
        dependsOn(":manager:build$variant")
    }
}

tasks.register("buildAll") {
    dependsOn("buildDebug", "buildRelease")
}

fun Project.configureBaseExtension() {
    extensions.findByType(BaseExtension::class)?.run {
        compileSdkVersion(androidCompileSdkVersion)
        ndkVersion = androidCompileNdkVersion
        buildToolsVersion = androidBuildToolsVersion

        externalNativeBuild.cmake {
            version = "3.29.8+"
            buildStagingDirectory = layout.buildDirectory.get().asFile
        }

        defaultConfig {
            minSdk = androidMinSdkVersion
            targetSdk = androidTargetSdkVersion
            versionCode = verCode
            versionName = verName

            signingConfigs.create("config") {
                val androidStoreFile = project.findProperty("androidStoreFile") as String?
                if (!androidStoreFile.isNullOrEmpty()) {
                    storeFile = rootProject.file(androidStoreFile)
                    storePassword = project.property("androidStorePassword") as String
                    keyAlias = project.property("androidKeyAlias") as String
                    keyPassword = project.property("androidKeyPassword") as String
                }
            }

            externalNativeBuild {
                cmake {
                    // Vector master builds its hook engine as the `native` static lib under
                    // core/native, resolving core/external via a single VECTOR_ROOT.
                    arguments += "-DVECTOR_ROOT=${File(rootDir.absolutePath, "core")}"
                    abiFilters("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                    val flags = arrayOf(
                        "-Wall",
                        "-Qunused-arguments",
                        "-Wno-gnu-string-literal-operator-template",
                        "-fno-rtti",
                        "-fvisibility=hidden",
                        "-fvisibility-inlines-hidden",
                        "-fno-exceptions",
                        "-fno-stack-protector",
                        "-fomit-frame-pointer",
                        "-Wno-builtin-macro-redefined",
                        "-Wno-unused-value",
                        "-D__FILE__=__FILE_NAME__",
                        // parallel_hashmap's SSE2 group scan arrives twice on the x86 ABIs once
                        // dex_builder is imported; phmap's layout depends on this flag, so it must
                        // match core/native or the shared .so has an invisible ODR violation.
                        "-DPHMAP_HAVE_SSE2=0",
                        "-DPHMAP_HAVE_SSSE3=0",
                        // core/native's config.h reads these as compiler defines, as Vector's own
                        // build passes them; VERSION_NAME is a string literal.
                        "-DVERSION_CODE=$verCode",
                        "-DVERSION_NAME='\"$verName\"'",
                    )
                    cppFlags("-std=c++23", *flags)
                    cFlags("-std=c18", *flags)
                    arguments(
                        "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
                        // 16 KB page alignment for Android 15+ compatibility.
                        "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
                        "-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
                    )
                }
            }
        }

        compileOptions {
            targetCompatibility(androidTargetCompatibility)
            sourceCompatibility(androidSourceCompatibility)
        }

        buildTypes {
            all {
                signingConfig = if (signingConfigs["config"].storeFile != null) signingConfigs["config"] else signingConfigs["debug"]
            }
            named("debug") {
                externalNativeBuild {
                    cmake {
                        arguments.addAll(
                            arrayOf(
                                "-DCMAKE_CXX_FLAGS_DEBUG=-Og",
                                "-DCMAKE_C_FLAGS_DEBUG=-Og",
                            )
                        )
                    }
                }
            }
            named("release") {
                externalNativeBuild {
                    cmake {
                        val flags = arrayOf(
                            "-Wl,--exclude-libs,ALL",
                            "-ffunction-sections",
                            "-fdata-sections",
                            "-Wl,--gc-sections",
                            "-fno-unwind-tables",
                            "-fno-asynchronous-unwind-tables",
                            "-flto=thin",
                            "-Wl,--thinlto-cache-policy,cache_size_bytes=300m",
                            "-Wl,--thinlto-cache-dir=${layout.buildDirectory.get().asFile.absolutePath}/.lto-cache",
                        )
                        cppFlags.addAll(flags)
                        cFlags.addAll(flags)
                        val configFlags = arrayOf(
                            "-Oz",
                            "-DNDEBUG"
                        ).joinToString(" ")
                        arguments.addAll(
                            arrayOf(
                                "-DCMAKE_CXX_FLAGS_RELEASE=$configFlags",
                                "-DCMAKE_CXX_FLAGS_RELWITHDEBINFO=$configFlags",
                                "-DCMAKE_C_FLAGS_RELEASE=$configFlags",
                                "-DCMAKE_C_FLAGS_RELWITHDEBINFO=$configFlags",
                                "-DDEBUG_SYMBOLS_PATH=${layout.buildDirectory.get().asFile.absolutePath}/symbols",
                            )
                        )
                    }
                }
            }
        }
    }

    extensions.findByType(ApplicationExtension::class)?.lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    extensions.findByType(ApplicationAndroidComponentsExtension::class)?.let { androidComponents ->
        val optimizeReleaseRes = tasks.register("optimizeReleaseRes") {
            doLast {
                val aapt2 = File(
                    androidComponents.sdkComponents.sdkDirectory.get().asFile,
                    "build-tools/${androidBuildToolsVersion}/aapt2"
                )
                val zip = java.nio.file.Paths.get(
                    project.layout.buildDirectory.get().asFile.path,
                    "intermediates",
                    "optimized_processed_res",
                    "release",
                    "optimizeReleaseResources",
                    "resources-release-optimize.ap_"
                )
                val optimized = File("${zip}.opt")
                val process = ProcessBuilder(
                    aapt2.absolutePath, "optimize",
                    "--collapse-resource-names",
                    "--enable-sparse-encoding",
                    "-o", optimized.absolutePath,
                    zip.toString()
                ).redirectErrorStream(true).start()
                process.inputStream.bufferedReader().readText().takeIf { it.isNotBlank() }?.let(::println)
                if (process.waitFor() == 0) {
                    java.nio.file.Files.deleteIfExists(zip)
                    optimized.renameTo(zip.toFile())
                }
            }
        }

        tasks.configureEach {
            if (name == "optimizeReleaseResources") {
                finalizedBy(optimizeReleaseRes)
            }
        }
    }
}

subprojects {
    // :apkzlib is vendored Google code (com.android.tools.build.apkzlib) -- keep its upstream
    // style, don't subject it to our formatter.
    if (name != "apkzlib") {
        apply(plugin = "com.diffplug.spotless")
        extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
            // Adopt formatting without a mass reflow: ratchet formats only files that differ from
            // the baseline, so the never-formatted tree converges as it is touched rather than in
            // one sweeping commit. A clean checkout has nothing to format; only changed files are
            // enforced.
            ratchetFrom("origin/master")
            kotlin {
                target("src/**/*.kt")
                // The core (Vector) formats Kotlin with ktfmt in kotlinlang (4-space) style; match
                // it, but widen to 120 cols so the repo's existing long lines are not rewrapped --
                // that keeps each ratcheted file's diff to the real change, not a width reflow.
                ktfmt(lspatch.versions.ktfmt.get()).kotlinlangStyle().configure { it.setMaxWidth(120) }
            }
            java {
                target("src/**/*.java")
                // 4-space, 120-col -- closest to the hand-written Java already in the tree.
                palantirJavaFormat()
            }
        }
    }
    plugins.withId("com.android.application") {
        configureBaseExtension()
    }
    plugins.withId("com.android.library") {
        configureBaseExtension()
    }
}
