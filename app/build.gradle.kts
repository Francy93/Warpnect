import io.warpnect.buildlogic.OwnedPairingTransform
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.tasks.bundling.Zip

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "io.warpnect"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "io.warpnect"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    lint {
        abortOnError = true
        checkDependencies = true
        warningsAsErrors = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

// Preserve the original module graph; transform only the pinned DEBUG pairing class closure.
val pairingPatch = Attribute.of("io.warpnect.libadb.pairing", String::class.java)
val pairingCompiler = configurations.create("libadbPairingCompiler") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    add(pairingCompiler.name, "com.github.osservatorionessuno:libadb-android-bc:3.4.0")
    add(pairingCompiler.name, "org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    // Artifact attributes, not component attributes: keep module variant selection unchanged.
    artifactTypes.maybeCreate("aar").attributes.attribute(pairingPatch, "original")
    registerTransform(OwnedPairingTransform::class) {
        from.attribute(pairingPatch, "original").attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar")
        to.attribute(pairingPatch, "owned-socket").attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar")
        parameters {
            source.set(
                rootProject.layout.projectDirectory.file("tools/libadb-pairing/patched/PairingConnectionCtx.java"),
            )
            compilerClasspath.from(
                pairingCompiler,
                android.sdkDirectory.resolve("platforms/android-${android.compileSdk}/android.jar"),
            )
        }
    }
}
configurations.configureEach {
    if (name.startsWith("debug") && isCanBeResolved) attributes.attribute(pairingPatch, "owned-socket")
}
tasks.register("reportOwnedPairingArtifact") {
    doLast {
        val configuration = configurations.getByName("debugRuntimeClasspath")
        configuration.incoming.artifactView {
            componentFilter {
                it is org.gradle.api.artifacts.component.ModuleComponentIdentifier && it.module == "libadb-android-bc"
            }
        }.files.forEach { println("OWNED_PAIRING_ARTIFACT=${it.absolutePath}") }
    }
}
tasks.register("verifyOwnedPairingReproduction") {
    doLast {
        val original = pairingCompiler.files.single { it.name == "libadb-android-bc-3.4.0.aar" }
        val source = rootProject.file("tools/libadb-pairing/patched/PairingConnectionCtx.java")
        val classpath = pairingCompiler.files + android.bootClasspath
        val output = layout.buildDirectory.dir("reports/owned-pairing").get().asFile
        val first = output.resolve("first/patched.aar")
        val second = output.resolve("second/patched.aar")
        OwnedPairingTransform.patch(original, source, classpath, first)
        OwnedPairingTransform.patch(original, source, classpath, second)
        check(first.readBytes().contentEquals(second.readBytes())) { "Non-reproducible pairing artifact" }
        val selected = configurations.getByName("debugRuntimeClasspath").incoming.artifactView {
            componentFilter {
                it is org.gradle.api.artifacts.component.ModuleComponentIdentifier && it.module == "libadb-android-bc"
            }
        }.files.singleFile
        check(selected.readBytes().contentEquals(first.readBytes())) { "Selected artifact differs from reproduction" }
        println("OWNED_PAIRING_REPRODUCTION=PASS ${selected.absolutePath}")
    }
}

// Separate API30 library: no strong API30 reference enters the minSdk26 SCL build.
val directShellPublicationJni = layout.buildDirectory.dir("generated/directshell-publication/jniLibs")
val directShellPublicationSource = layout.projectDirectory.file("src/main/native-publication/directshell_publication.c")
val directShellCompilerHost = when {
    System.getProperty("os.name").startsWith("Windows") -> "windows-x86_64"
    System.getProperty("os.name") == "Mac OS X" -> "darwin-x86_64"
    else -> "linux-x86_64"
}
val directShellLlvm = android.sdkDirectory.resolve(
    "ndk/${android.ndkVersion}/toolchains/llvm/prebuilt/$directShellCompilerHost",
)
val directShellClang = directShellLlvm.resolve(
    "bin/clang${if (directShellCompilerHost.startsWith("windows")) ".exe" else ""}",
)
val directShellPublicationCompilers = mapOf(
    "arm64-v8a" to "aarch64-linux-android30",
    "armeabi-v7a" to "armv7a-linux-androideabi30",
    "x86_64" to "x86_64-linux-android30",
).map { (abi, target) ->
    tasks.register<Exec>("compileDirectShellPublication${abi.replace("-", "").replace("_", "")}") {
        val output = directShellPublicationJni.get().dir(abi).file("libwarpnect_directshell_publication.so").asFile
        inputs.file(directShellPublicationSource)
        inputs.file(directShellClang)
        inputs.property("target", target)
        outputs.file(output)
        doFirst { check(output.parentFile.mkdirs() || output.parentFile.isDirectory) }
        commandLine(
            directShellClang, "--target=$target", "--sysroot=${directShellLlvm.resolve("sysroot")}",
            "-std=c11", "-Wall", "-Wextra", "-Werror", "-O2", "-fPIC", "-shared", "-fvisibility=hidden",
            "-Wl,--no-undefined", "-Wl,-z,max-page-size=16384", "-Wl,-soname,libwarpnect_directshell_publication.so",
            directShellPublicationSource.asFile, "-o", output,
        )
    }
}
val compileDirectShellPublication = tasks.register("compileDirectShellPublication") {
    dependsOn(directShellPublicationCompilers)
    outputs.dir(directShellPublicationJni)
}
android.sourceSets.getByName("main").jniLibs.srcDir(directShellPublicationJni)
tasks.configureEach {
    if (name == "mergeDebugJniLibFolders" || name == "mergeReleaseJniLibFolders") {
        dependsOn(
            compileDirectShellPublication,
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Expose the existing libadb TLS version only to host DEBUG protocol fixtures.
    add("testDebugImplementation", "org.bouncycastle:bctls-jdk15to18:1.84")
    // The target DEBUG runtime already supplies this version; instrumentation needs compile visibility only.
    add("androidTestDebugCompileOnly", "org.bouncycastle:bctls-jdk15to18:1.84")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // DEBUG-only Wireless Debugging feasibility client. It is deliberately not a production
    // runtime dependency while pairing, key storage, and lifecycle semantics are qualified.
    debugImplementation("com.github.osservatorionessuno:libadb-android-bc:3.4.0")
    debugImplementation("org.bouncycastle:bcpkix-jdk15to18:1.84")
    // Keep the Startup provider's generated R class in the DEBUG APK. The transitive 1.1.1
    // declaration is insufficient with this project's non-transitive R configuration.
    debugImplementation("androidx.startup:startup-runtime:1.2.0")
}

/**
 * Produces the DEX-only classpath artifact used by the DEBUG-only DirectShell app_process probe.
 * It intentionally contains no resource or native-library contract; the probe uses framework and
 * Java APIs only, and is never loaded by the production runtime.
 */
val directShellProbeDebugApk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
tasks.register<Zip>("packageDirectShellProbeDex") {
    group = "distribution"
    description = "Packages debug DEX files for the isolated DirectShell app_process probe."
    dependsOn("packageDebug")
    from({ zipTree(directShellProbeDebugApk.get().asFile) }) {
        include("classes*.dex")
    }
    archiveFileName.set("warpnect-directshell-probe.jar")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/directshell-probe"))
    includeEmptyDirs = false
}
