import java.io.File
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  // Persists edit state to disk for the Projects feature.
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.alex.photoeditor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.alex.photoeditor"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    // ---------------------------------------------------------------- signing
    //
    // Resolution order, first match wins:
    //   1. -D system properties      (CI, one-off builds)
    //   2. keystore.properties       (local, git-ignored, repeatable)
    //   3. nothing                   -> the release build FAILS
    //
    // The third case is deliberate. It used to fall back to the debug key so
    // that `assembleRelease` worked on a fresh clone, but that is a trap: a
    // debug-signed "release" APK installs and runs perfectly, so the problem
    // is invisible until someone tries to update the real app and Android
    // rejects it as a signature mismatch. Failing here turns a silent,
    // hard-to-diagnose problem into an obvious one at build time.
    // Imported at the top of the file rather than written as `java.util.Properties`
    // here: inside `android { }` the bare name `java` resolves to Gradle's
    // JavaPluginExtension, not the package, so the qualified form does not compile.
    val keystoreProperties = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { stream -> load(stream) }
    }

    fun signingValue(property: String, systemProperty: String): String? =
        System.getProperty(systemProperty)
            ?: keystoreProperties.getProperty(property)
                ?.takeIf { value -> value.isNotBlank() }

    val releaseStorePath = signingValue("storeFile", "RELEASE_STORE_FILE")
    val releaseStorePassword = signingValue("storePassword", "RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS")
    val releaseKeyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD")

    val hasReleaseSigning = listOf(
        releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
    ).all { !it.isNullOrBlank() }

    // `storeFile` in keystore.properties is documented as relative to that
    // file's own directory (the repo root). A bare `file(...)` here would
    // resolve it against the `app/` module instead and fail with
    // "Keystore file ... not found", so a relative path is anchored at the
    // root project and an absolute path is passed straight through.
    val releaseStoreFile = releaseStorePath?.let { path ->
        val candidate = File(path)
        if (candidate.isAbsolute) candidate else File(rootProject.projectDir, path)
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinks, renames and strips dead code. Without this the
            // release APK shipped with every class, method and field name
            // intact - the source laid out in readable form.
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                // Refusing here rather than shipping a debug-signed release.
                // `buildDebug` and `build-apk.bat` remain unaffected.
                throw GradleException(
                    """
                    |Release signing is not configured, so the release build has been stopped.
                    |
                    |Falling back to the debug key would produce an APK that installs and
                    |runs, but that anyone can replace on a user's device - and which
                    |cannot later be updated with a real key, because Android treats a
                    |different signature as a different app.
                    |
                    |To fix, create keystore.properties next to this file (git-ignored):
                    |
                    |    storeFile=release.jks
                    |    storePassword=<keystore password>
                    |    keyAlias=<alias>
                    |    keyPassword=<key password>
                    |
                    |Or pass -D properties instead:
                    |
                    |    gradlew assembleRelease -DRELEASE_STORE_FILE=release.jks ...
                    |
                    |To build the debug variant instead, run: gradlew assembleDebug
                    """.trimMargin()
                )
            }
        }

        debug {
            // The debug build stays unobfuscated so a stack trace during
            // development reads as the source that was actually typed.
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    // `ui-tooling` is a dependency inspector that can read and modify live
    // Compose state, and `ui-tooling-preview` is only useful at build time.
    // Both are already debugImplementation-scoped below, so this comment only
    // records why that scoping must not be relaxed.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
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

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Backs the "tap to select" background removal tool.
    implementation(libs.mediapipe.tasks.vision)

    testImplementation(libs.junit)
}
