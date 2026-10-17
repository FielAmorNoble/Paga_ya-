plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Solo rutas: nunca poner contraseñas en argumentos, propiedades versionadas o logs.
val releaseStorePath = providers.environmentVariable("PAGAYA_RELEASE_STORE_FILE").orNull
val releasePasswordPath = providers.environmentVariable("PAGAYA_RELEASE_PASSWORD_FILE").orNull
require((releaseStorePath == null) == (releasePasswordPath == null)) {
    "La firma requiere PAGAYA_RELEASE_STORE_FILE y PAGAYA_RELEASE_PASSWORD_FILE."
}

android {
    // Namespace interno conservado para no renombrar código ni esquemas Room.
    namespace = "com.example.pagaya"
    // Compose 1.12 (BOM 2026.09.00) exige compilar contra API 37; no cambia minSdk ni targetSdk.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.pagaya"
        // Mínimo provisional: falta verificar el teléfono real (VP-01).
        minSdk = 26
        targetSdk = 36
        // 1 y 2: candidatos locales; 3: primera entrega preliminar descargable.
        versionCode = 3
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStorePath != null && releasePasswordPath != null) {
            create("officialRelease") {
                storeFile = file(releaseStorePath)
                require(storeFile!!.isFile) { "No existe el almacén de firma indicado." }
                val passwordFile = file(releasePasswordPath)
                require(passwordFile.isFile) { "No existe el archivo local de contraseña." }
                val password = passwordFile.readText(Charsets.UTF_8).trimEnd('\r', '\n')
                require(password.isNotEmpty()) { "El archivo de contraseña está vacío." }
                storeType = "JKS"
                storePassword = password
                keyAlias = "pagaya-release"
                keyPassword = password
            }
        }
    }

    buildTypes {
        // Aplicación de desarrollo: otro applicationId, por tanto otro directorio de datos y otra
        // pagaya.db. Nombre visible "Paga Ya! Dev" en src/debug/res. Las pruebas instrumentadas
        // se instalan contra esta variante.
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        // Instalación aislada de verificación manual con datos sintéticos: copia de debug con otro
        // applicationId, así que nunca comparte datos con «Paga Ya! Dev». Nombre «Paga Ya! QA» en src/qa/res.
        // No tiene pruebas instrumentadas (testBuildType sigue siendo debug).
        create("qa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa"
            matchingFallbacks += listOf("debug")
        }
        // Segundo entorno vacío para ensayar restauración sin borrar la QA existente.
        create("qaRestore") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa.restore"
            matchingFallbacks += listOf("qa", "debug")
        }
        // Sin credenciales locales se puede compilar unsigned; nunca usar firma debug como sustituto.
        release {
            isMinifyEnabled = false
            versionNameSuffix = "-rc.3"
            if (releaseStorePath != null) {
                signingConfig = signingConfigs.getByName("officialRelease")
            }
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

// Cambiar solo release preserva los paquetes instalados de Dev/QA y sus datos.
androidComponents {
    onVariants(selector().withBuildType("release")) {
        it.applicationId.set("com.fielamornoble.pagaya")
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
