import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun secret(name: String): String? {
    return providers.gradleProperty(name).orNull
        ?: keystoreProperties.getProperty(name)
        ?: System.getenv(name)
}

val releaseStoreFile = secret("RELEASE_STORE_FILE")
val releaseStorePassword = secret("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = secret("RELEASE_KEY_ALIAS")
val releaseKeyPassword = secret("RELEASE_KEY_PASSWORD")

val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.tianqianguai.gramsieve"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    signingConfigs {
        // Fixed debug keystore for upgrade continuity (Build #7 fix):
        // Build #6 succeeded but its APK signature did NOT match the fixed
        // keystore (cert fingerprint 80:A6:... vs fixed 24:F7:...), because
        // AGP did not pick up ~/.android/debug.keystore on the CI runner
        // (it likely looks under ANDROID_SDK_HOME instead). Pin the debug
        // signing config explicitly to a repo-root debug.keystore file when
        // it exists (the workflow decodes debug.keystore.b64 to that file
        // before building). When the file is absent (local dev), AGP's
        // default debug keystore behaviour is left untouched.
        getByName("debug") {
            val fixedDebugKeystore = rootProject.file("debug.keystore")
            if (fixedDebugKeystore.exists()) {
                storeFile = fixedDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("release") {
            if (hasReleaseSigning) {
                val candidateStoreFile = rootProject.file(releaseStoreFile!!)
                require(candidateStoreFile.exists()) {
                    "RELEASE_STORE_FILE does not exist: $releaseStoreFile"
                }
                storeFile = candidateStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.tianqianguai.gramsieve.conso"
        minSdk = 33
        targetSdk = 36
        // Conso adaptation version scheme (Build #15): versionCode 1003
        // is deliberately above upstream GramSieve 2.6.10-beta (349), so
        // the upstream package is never offered as an 'update' for this
        // build. applicationId is now com.tianqianguai.gramsieve.conso,
        // distinct from upstream com.tianqianguai.gramsieve, so the two
        // can coexist and neither is offered as an update for the other.
        // The Java package (namespace) stays com.tianqianguai.gramsieve;
        // only the installed package changes. Upgrading from the old
        // package needs an uninstall + LSPosed re-enable, and rules do
        // not carry over automatically (they live in module prefs keyed
        // by package): export a full backup from the old build first,
        // then import it in the new build.
        versionCode = 1003
        versionName = "1.0.3-conso"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.gson)
    implementation(libs.libxposed.service)
    compileOnly(libs.libxposed.api)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
