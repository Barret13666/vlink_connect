plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.vesc.ble"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.vesc.ble"
        // 26 покрывает практически все живые телефоны. Ниже опускаться нет
        // смысла, выше — тоже: разрешения на BLE всё равно приходится
        // разводить по двум веткам, до Android 12 и после.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures { viewBinding = true }

    // Тесты идут на обычной JVM: криптография построена на javax.crypto и
    // Android для неё не нужен. Заглушки android.jar по умолчанию бросают
    // исключение при вызове — нам достаточно, чтобы классы просто
    // загрузились.
    testOptions { unitTests.isReturnDefaultValues = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
