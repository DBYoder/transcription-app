import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// --- sherpa-onnx (speech recognition engine) ---------------------------------------------
// Not published to Maven, so we fetch the official Android library from its GitHub
// release and verify its checksum (see gradle.properties).
val sherpaOnnxVersion: String = providers.gradleProperty("sherpaOnnxVersion").get()
val sherpaOnnxSha256: String = providers.gradleProperty("sherpaOnnxSha256").getOrElse("")
val sherpaOnnxAar: File = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaOnnxVersion.aar").asFile

val downloadSherpaOnnx by tasks.registering {
    description = "Downloads the sherpa-onnx Android library."
    val target = sherpaOnnxAar
    val version = sherpaOnnxVersion
    val expected = sherpaOnnxSha256.trim().lowercase()
    outputs.file(target)
    onlyIf { !target.exists() }
    doLast {
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/sherpa-onnx-$version.aar"
        target.parentFile.mkdirs()
        val partial = File(target.parentFile, target.name + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        URI(url).toURL().openStream().use { input ->
            partial.outputStream().use { output ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (expected.isEmpty()) {
            logger.warn("sherpa-onnx $version AAR SHA-256 = $actual (set sherpaOnnxSha256 in gradle.properties to pin it)")
        } else if (actual != expected) {
            partial.delete()
            throw GradleException("sherpa-onnx AAR checksum mismatch: expected $expected but got $actual")
        }
        if (!partial.renameTo(target)) throw GradleException("Couldn't move ${partial.name} into place")
    }
}

// --- Release signing ------------------------------------------------------------------
// CI decodes the SIGNING_KEYSTORE_BASE64 secret to a file and points this at it, so every
// build is signed with the same key and installs as an update. Without it (e.g. a local
// build) the release APK falls back to the debug key.
val releaseKeystore: String? = providers.environmentVariable("SIGNING_KEYSTORE_PATH").orNull

android {
    namespace = "io.github.dbyoder.transcribe"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.dbyoder.transcribe"
        minSdk = 31
        targetSdk = 35
        // CI run numbers only go up, so each build installs over the previous one.
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 1
        versionName = "0.6.0"

        // Pixel phones (and nearly all current Android phones) are 64-bit ARM.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        releaseKeystore?.let { keystorePath ->
            create("release") {
                storeFile = file(keystorePath)
                storeType = "pkcs12"
                storePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/DEPENDENCIES",
                "/META-INF/versions/9/module-info.class",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.named("preBuild") { dependsOn(downloadSherpaOnnx) }

dependencies {
    implementation(project(":core"))
    implementation(files(sherpaOnnxAar).builtBy(downloadSherpaOnnx))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.litertlm.android)
}
