import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingProperties = Properties()
val signingPropertiesFile = rootProject.file("keystore.properties")
if (signingPropertiesFile.isFile) {
    signingPropertiesFile.inputStream().use { signingProperties.load(it) }
}
val signingStoreFile = signingProperties.getProperty("storeFile")
val signingStorePassword = signingProperties.getProperty("storePassword")
val signingKeyAlias = signingProperties.getProperty("keyAlias")
val signingKeyPassword = signingProperties.getProperty("keyPassword")
val hasReleaseSigning = listOf(
    signingStoreFile,
    signingStorePassword,
    signingKeyAlias,
    signingKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.example.jingdu"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.jingdu"
        minSdk = 26
        targetSdk = 35
        versionCode = 27
        versionName = "1.3.23"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.webkit:webkit:1.12.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
