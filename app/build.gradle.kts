import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: keystore.properties locally, environment variables in CI. Without either, release builds use the debug key.
val signing = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun signingValue(key: String): String? = System.getenv("GLASSHOUSE_$key") ?: signing.getProperty(key)

android {
    namespace = "io.github.raphaelbleier.glasshouse"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.raphaelbleier.glasshouse"
        minSdk = 28
        targetSdk = 34
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0-dev"
    }
    signingConfigs {
        create("release") {
            signingValue("STORE_FILE")?.let { storeFile = rootProject.file(it) }
            storePassword = signingValue("STORE_PASSWORD")
            keyAlias = signingValue("KEY_ALIAS")
            keyPassword = signingValue("KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = if (signingValue("STORE_FILE") != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    sourceSets["main"].res.srcDirs(
        "src/main/res",
        // Drop your own fonts (same file names) into app/fonts-private/font to override the bundled Inter
        if (file("fonts-private/font").exists()) "fonts-private" else "src/main/res-fonts",
    )
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
