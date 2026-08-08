import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing config is read from keystore.properties at the repo root
// (git-ignored). When the file is absent (e.g. fresh clone / CI without
// secrets) the release build is simply left unsigned instead of failing.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}

android {
    namespace = "com.localagenda.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.localagenda.android"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        vectorDrawables { useSupportLibrary = true }
        // Smoke test no emulador (CI): abrir o app de verdade e ver a UI subir.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Auto-sign when keystore.properties is present; otherwise unsigned.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
    sourceSets["test"].kotlin.srcDirs("src/test/kotlin")

    testOptions {
        unitTests {
            // Os testes JVM usam org.json (real, do Maven — o do SDK é um stub
            // que lança em teste unitário).
            isReturnDefaultValues = false
            isIncludeAndroidResources = false
            // stdout/stderr dos testes aparecem no log do CI (essencial p/ depurar).
            all { it.testLogging.showStandardStreams = true }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // ── AndroidX core ────────────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    // Material XML themes (Theme.Material3.DayNight) — necessárias para o tema
    // base/splash definidos em res/values/themes.xml.
    implementation("com.google.android.material:material:1.12.0")

    // ── Compose ──────────────────────────────────────────────────────────
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Ícones das ações da tela (Calendário/Evento/Tarefa, salvar, travar, …).
    implementation("androidx.compose.material:material-icons-extended")

    // ── SAF (abrir/criar o banco .db no armazenamento do usuário) ────────
    implementation("androidx.documentfile:documentfile:1.0.1")

    // ── Persistência de preferências (URI do banco, opt-ins locais) ──────
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ── Kotlin coroutines ────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ── Testes (JVM) ─────────────────────────────────────────────────────
    // A serialização das Settings (JSON no meta do .db, igual ao desktop)
    // roda sem Android. org.json vem do Maven porque o do SDK é stub.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    // ── Testes instrumentados (smoke no emulador do CI) ──────────────────
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    // ── Debug / preview ──────────────────────────────────────────────────
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
