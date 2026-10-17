plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    // Identificador PROVISIONAL de desarrollo (VP-05). No registrar en Google ni distribuir con él.
    namespace = "com.example.pagaya"
    // Compose 1.12 (BOM 2026.09.00) exige compilar contra API 37; no cambia minSdk ni targetSdk.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.pagaya"
        // Mínimo provisional: falta verificar el teléfono real (VP-01).
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // Aplicación de desarrollo: otro applicationId, por tanto otro directorio de datos y otra
        // pagaya.db. Nombre visible "Paga Ya! Dev" en src/debug/res. Las pruebas instrumentadas
        // se instalan contra esta variante.
        debug {
            applicationIdSuffix = ".dev"
        }
        // Instalación aislada de verificación manual con datos sintéticos: copia de debug con otro
        // applicationId, así que nunca comparte datos con «Paga Ya! Dev». Nombre «Paga Ya! QA» en src/qa/res.
        // No tiene pruebas instrumentadas (testBuildType sigue siendo debug).
        create("qa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa"
            matchingFallbacks += listOf("debug")
        }
        // Aplicación normal, sin sufijo. Sin firma de distribución configurada (VP-05).
        release {
            isMinifyEnabled = false
        }
    }

    // Explícito: nunca compilar las pruebas contra la aplicación sin sufijo.
    testBuildType = "debug"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
