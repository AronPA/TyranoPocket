import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val signing = Properties()
val signingFile = rootProject.file("signing.properties")
if (signingFile.exists()) signingFile.inputStream().use { signing.load(it) }

android {
    namespace = "com.local.tyranopocket"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = providers.gradleProperty("tyranopocket.applicationId").get()
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (signingFile.exists()) create("personal") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storeType = signing.getProperty("storeType", "PKCS12")
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            if (signingFile.exists()) signingConfig = signingConfigs.getByName("personal")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = true; checkReleaseBuilds = true }
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("fixtures"))
}
dependencies {
    implementation(project(":core"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui:1.9.3")
    implementation("androidx.compose.foundation:foundation:1.9.3")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
