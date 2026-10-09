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
fun localString(key: String) = "\"" + (local.getProperty(key) ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "app.androplay"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.androplay"
        minSdk = 28
        targetSdk = 34
        versionCode = 10
        versionName = "0.8.0"

        buildConfigField("String", "DEFAULT_REDIS_URL", localString("androplay.redisUrl"))
        buildConfigField("String", "DEFAULT_S3_ENDPOINT", localString("androplay.s3Endpoint"))
        buildConfigField("String", "DEFAULT_S3_REGION", localString("androplay.s3Region"))
        buildConfigField("String", "DEFAULT_S3_BUCKET", localString("androplay.s3Bucket"))
        buildConfigField("String", "DEFAULT_S3_ACCESS_KEY", localString("androplay.s3AccessKey"))
        buildConfigField("String", "DEFAULT_S3_SECRET_KEY", localString("androplay.s3SecretKey"))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
