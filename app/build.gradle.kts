plugins {
    alias(libs.plugins.android.application)
    // ВНИМАНИЕ: Мы больше не пишем плагин Kotlin! Он теперь встроен в ядро Android Studio.

    // Используем современный KSP версии 2.2.10-2.0.2, который идеально подходит под AGP 9.4.0
    id("com.google.devtools.ksp") version "2.2.10-2.0.2"
}

android {
    namespace = "com.example.wifichipreader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.wifichipreader"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // ZXing для генерации QR-кодов
    implementation("com.google.zxing:core:3.5.1")

    // Room для базы данных истории
    val room_version = "2.6.1"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")

    // Используем ksp для генерации
    ksp("androidx.room:room-compiler:$room_version")
}