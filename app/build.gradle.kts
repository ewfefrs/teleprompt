import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Начиная с Kotlin 2.0 Compose-компилятор подключается отдельным плагином,
    // версия которого ОБЯЗАНА совпадать с версией Kotlin (2.0.21).
    id("org.jetbrains.kotlin.plugin.compose")
}

// Секреты релизной подписи берём из keystore.properties (в .gitignore).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}

android {
    namespace = "com.example.teleprompter"
    compileSdk = 35

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
            // v1 (JAR) подпись ОБЯЗАТЕЛЬНА для сторонних магазинов (APKPure): их верификатор
            // читает сертификат из META-INF. Без неё upload отклоняется («signature mismatch»).
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    defaultConfig {
        applicationId = "com.whisprompt.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
        // По умолчанию (release/debug) премиум-разблокировки НЕТ — только личная сборка author.
        buildConfigField("boolean", "AUTHOR_UNLOCKED", "false")
    }

    buildTypes {
        release {
            // Подпись релизным ключом (из keystore.properties).
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
            // Защита от реверс-инжиниринга: R8 обфусцирует и урезает код/ресурсы в
            // релизе (имена классов/методов, мёртвый код). Debug-сборка (критерий
            // приёмки №1) остаётся неминифицированной — правила см. proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
        // Личная «авторская» сборка: премиум всегда включён, все лимиты сняты
        // (AUTHOR_UNLOCKED=true). НЕ для магазинов — assembleRelease остаётся закрытым.
        // Наследует подпись/минификацию релиза. Собирается: assembleAuthor.
        create("author") {
            initWith(getByName("release"))
            buildConfigField("boolean", "AUTHOR_UNLOCKED", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true   // нужен BuildConfig.DEBUG для IntegrityGuard (пропуск в dev)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")

    // Lifecycle: ViewModel + корутины + интеграция с Compose.
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Compose BOM фиксирует согласованные версии всех compose-артефактов.
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    // Расширенный набор иконок (Star, Help, Language, Lock, Email, WorkspacePremium…)
    // для интерактивных иконок в настройках/справке.
    implementation("androidx.compose.material:material-icons-extended")

    // Google Play Billing — покупка премиум-версии (подтверждение оплаты банком/Play).
    implementation("com.android.billingclient:billing-ktx:7.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
