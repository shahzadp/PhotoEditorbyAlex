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

    // A release build is signed with the debug key so `assembleRelease` works
    // out of the box. That is a convenience, not a security posture: the debug
    // key is in the repository and anyone can sign an APK that claims to be
    // this app. Replace the block below with a real keystore before shipping
    // anything, or the signature is worth nothing.
    signingConfigs {
        create("release") {
            storeFile = file(System.getProperty("RELEASE_STORE_FILE") ?: "release.jks")
            storePassword = System.getProperty("RELEASE_STORE_PASSWORD")
            keyAlias = System.getProperty("RELEASE_KEY_ALIAS")
            keyPassword = System.getProperty("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // Minification was off entirely, so the release APK shipped with
            // every class, method and field name intact - the source laid out
            // in readable form. R8 shrinks, renames and strips dead code.
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Signed with a real keystore only when one has been supplied,
            // otherwise the debug key so `assembleRelease` still works on a
            // fresh clone.
            //
            // Falling back is a convenience and nothing more. The debug key is
            // public and in the repository, so a build produced that way can be
            // replaced by anyone and still install over this app. Before any
            // real distribution, pass the four -D properties in [signingConfigs]
            // and verify the APK is signed by it.
            signingConfig = if (
                System.getProperty("RELEASE_STORE_FILE") != null
            ) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
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
