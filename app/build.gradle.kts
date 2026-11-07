plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Se reutiliza la firma original de Android Studio para que Android acepte las
// actualizaciones sin desinstalar ni borrar los datos de la instalación anterior.
// Nunca insertar un archivo .keystore ni contraseñas en el repositorio público.
val originalSigningKeystorePath = System.getenv("SKC_SIGNING_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

android {
    signingConfigs {
        if (originalSigningKeystorePath != null) {
            create("original") {
                storeFile = file(originalSigningKeystorePath)
                storePassword = System.getenv("SKC_SIGNING_STORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "android"
                keyAlias = System.getenv("SKC_SIGNING_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "androiddebugkey"
                keyPassword = System.getenv("SKC_SIGNING_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: "android"
            }
        }
    }

    namespace = "com.skcingenieria.facturas"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.skcingenieria.facturas"
        minSdk = 26
        targetSdk = 36
        versionCode = 31507
        versionName = "1.5.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*"
        )
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".pilot"
            if (originalSigningKeystorePath != null) {
                signingConfig = signingConfigs.getByName("original")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.work:work-runtime-ktx:2.10.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
