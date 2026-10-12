import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

// Optional: bake lab backend defaults into the build so devices need zero setup.
// Put androplay.* keys in local.properties (never committed).
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun localString(key: String, default: String = "") =
    "\"" + (local.getProperty(key) ?: default).replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "app.androplay"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.androplay"
        minSdk = 28
        targetSdk = 34
        versionCode = 18
        versionName = "0.8.8"

        buildConfigField("String", "DEFAULT_REDIS_URL", localString("androplay.redisUrl"))
        buildConfigField("String", "DEFAULT_S3_ENDPOINT", localString("androplay.s3Endpoint"))
        buildConfigField("String", "DEFAULT_S3_REGION", localString("androplay.s3Region"))
        buildConfigField("String", "DEFAULT_S3_BUCKET", localString("androplay.s3Bucket"))
        buildConfigField("String", "DEFAULT_S3_ACCESS_KEY", localString("androplay.s3AccessKey"))
        buildConfigField("String", "DEFAULT_S3_SECRET_KEY", localString("androplay.s3SecretKey"))
        buildConfigField(
            "String",
            "DEFAULT_S3_FALLBACK_BUCKETS",
            localString("androplay.s3FallbackBuckets", "androplay1,androplay2,androplay3"),
        )
    }

    // Every machine must sign with the lab key, or devices reject the in-app update.
    // Point androplay.keystore in local.properties at a copy of it (default: ~/.android/debug.keystore).
    signingConfigs {
        create("lab") {
            storeFile = file(local.getProperty("androplay.keystore") ?: "${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = local.getProperty("androplay.keystorePassword") ?: "android"
            keyAlias = local.getProperty("androplay.keyAlias") ?: "androiddebugkey"
            keyPassword = local.getProperty("androplay.keyPassword") ?: "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("lab")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("lab")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.reorderable)
}
