# 12 — Arranque técnico

Fechas: 2026-10-06 (arranque) y 2026-10-07 (corrección de reproducibilidad). Iteración: comprobaciones técnicas iniciales de fase 0 y arranque de fase 1. **No completa ninguna de las dos fases.**

## Resultado

Existe un proyecto Gradle con un único módulo `app` que compila un APK debug y pasa Android Lint sin errores. La app muestra «Paga Ya!» y un estado vacío que declara que todavía no se pueden registrar clientes ni movimientos. Al cierre del arranque, Room y KSP solo estaban declarados; la base local se implementó después ([13](13_Persistencia_Local.md)).

| Comprobación | Estado |
| --- | --- |
| Compilación limpia del APK debug | **Comprobada** el 2026-10-07 con proceso nuevo (`--rerun-tasks --no-build-cache --no-daemon`), Windows 11, JDK 25 |
| Android Lint (debug) | **Comprobado**: 0 errores, 4 avisos de versiones |
| Compilación desde un proceso sin acceso de lectura al SDK (sandbox del orquestador) | **Falla** con «SDK location not found»; ver [incidente](#incidente-de-reproducibilidad-2026-10-07) |
| Instalación y apertura en Android | **Pendiente**: no hay teléfono conectado ni emulador |
| Persistencia, migraciones, integridad | Ver [13 — Persistencia local](13_Persistencia_Local.md) |
| Compilación con Android Studio | **No comprobada**: Android Studio no está instalado |

## Entorno necesario para reproducir el build

| Requisito | Valor verificado |
| --- | --- |
| JDK | 17 o superior en `PATH` o `JAVA_HOME`. Verificado con Temurin 25.0.2+10 en `C:\Users\lionb\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.2.10-hotspot` (en `PATH`; `JAVA_HOME` sin definir) |
| Android SDK | `C:\Users\lionb\AppData\Local\Android\Sdk` (instalación de usuario, propietario `HAPPY\lionb`) |
| Paquetes SDK requeridos | `platforms;android-37.0`, `build-tools;36.0.0`. Opcional: `platform-tools` (ADB) |
| Localización del SDK | `local.properties` (no versionado): `sdk.dir=C\:/Users/lionb/AppData/Local/Android/Sdk`. `ANDROID_HOME` y `ANDROID_SDK_ROOT` no están definidas (ni en el proceso, ni a nivel de usuario o máquina) |
| Permisos del proceso | El proceso que ejecuta Gradle **y el daemon que realiza el build** deben poder leer el directorio del SDK |
| Red | Necesaria la primera vez para descargar la distribución de Gradle y las dependencias; después basta la caché de `%USERPROFILE%\.gradle` |

Comando de verificación completa (PowerShell):

```
.\gradlew.bat assembleDebug lintDebug --rerun-tasks --no-build-cache --no-daemon --console=plain
```

Un build incremental que termina con tareas UP-TO-DATE **no prueba** que el proceso que lo lanzó tenga acceso al SDK: si se reutiliza un daemon de Gradle ya iniciado, el build se ejecuta con los permisos de ese daemon. Para evidencia de reproducibilidad usar `--no-daemon` (fuerza un daemon de un solo uso creado desde el entorno actual) y `--rerun-tasks`.

## Incidente de reproducibilidad (2026-10-07)

**Síntoma reportado por el orquestador:** `.\gradlew.bat assembleDebug lintDebug --console=plain` terminó bien con 46 tareas UP-TO-DATE, pero la misma orden con `--rerun-tasks --no-build-cache --no-daemon` falló indicando que el directorio de `sdk.dir` no existe.

**Evidencia** (logs de daemon en `%USERPROFILE%\.gradle\daemon\9.5.1\`; se inspeccionaron solo nombres de variables, no valores):

| Hora (2026-10-06, -05:00) | Daemon | Entorno del cliente que pidió el build | Resultado |
| --- | --- | --- | --- |
| 19:20 | — | Directorio del SDK creado (`LastWriteTime` de `Sdk`, 19:19–19:25) | — |
| 19:21–19:27 | pid 34228, larga vida, iniciado desde la sesión de desarrollo (variables `CLAUDE_*`) | Sesión de desarrollo | Builds y lint correctos |
| 19:34:25 | **mismo pid 34228, reutilizado** | Orquestador: 15 variables `CODEX_*`, entre ellas `CODEX_WINDOWS_SANDBOX_PACKAGE_FAMILY`, `CODEX_PERMISSION_PROFILE` y `CODEX_SANDBOX_NETWORK_DISABLED` | BUILD SUCCESSFUL, 1 ejecutada, 46 up-to-date |
| 19:35:55 | pid 33764, **nuevo, de un solo uso** (`--no-daemon` con `org.gradle.jvmargs`) creado desde el entorno del orquestador | Orquestador (mismas variables `CODEX_*`) | BUILD FAILED: «sdk.dir property in local.properties file. Problem: Directory does not exist» |
| 2026-10-07 18:01 | pid 33472, nuevo, de un solo uso | Sesión de desarrollo | BUILD SUCCESSFUL, 47 ejecutadas |

**Causa demostrada:** el éxito incremental se ejecutó dentro de un daemon iniciado fuera del sandbox del orquestador, por eso veía el SDK. El fallo ocurrió en el único build cuyo daemon se creó dentro del entorno sandbox del orquestador. El SDK ya existía en la ruta configurada a esa hora y sigue existiendo; `local.properties` es correcto y no se modificó.

**Actualización 2026-10-12:** el SDK está físicamente en la carpeta virtualizada del paquete MSIX de la app de escritorio de Claude. Esto concreta la causa más probable; ver [14](14_Entornos_Desarrollo.md#hallazgo-ubicación-física-del-android-sdk).

**Incertidumbre restante:** la política concreta del sandbox no es inspeccionable desde este entorno. La explicación más probable es que ese sandbox no concede lectura a `C:\Users\lionb\AppData\Local\Android`, aunque el mismo proceso sí pudo usar el JDK de `AppData\Local\Programs` y la caché de `.gradle`. No está demostrado qué regla lo impide.

**Resolución pendiente del lado del orquestador** (no requiere cambios en el repositorio): ejecutar la verificación fuera del sandbox, o conceder lectura a la ruta del SDK en su perfil de permisos. Mover el SDK no se hizo: solo se justificaría si el orquestador no puede ampliar permisos, y entonces habría que acordar una ruta legible y actualizar `local.properties`.

## Herramientas encontradas

| Herramienta | Estado inicial (2026-10-06) | Estado actual (2026-10-07) |
| --- | --- | --- |
| JDK | Temurin 25.0.2+10 LTS en `PATH`; `JAVA_HOME` sin definir | Sin cambios |
| Android Studio | No instalado | Sin cambios |
| Android SDK | No existía | Instalado (ruta arriba) con autorización del propietario |
| Gradle | No instalado globalmente | Wrapper 9.5.1 en el repositorio |
| ADB | No existía | `Sdk\platform-tools\adb.exe`, versión 1.0.41 (platform-tools 37.0.1) |
| Emuladores / dispositivos | Ninguno | Ninguno (`adb devices -l` vacío el 2026-10-07) |

Paquetes SDK instalados el 2026-10-06 y presentes el 2026-10-07: `cmdline-tools;latest` (23.0.0, zip SHA-1 verificado contra el manifiesto de Google), `platform-tools` 37.0.1, `platforms;android-36`, `platforms;android-37.0`, `build-tools;36.0.0`. Licencia aceptada: `android-sdk-license`.

Observaciones:

- `sdkmanager` está obsoleto en cmdline-tools 23 y delega en la Android CLI (`cmdline-tools\latest\bin\android.exe`). Esa CLI recoge métricas de uso salvo que se pase `--no-metrics`; la primera invocación se ejecutó sin esa opción, las siguientes con ella. Para instalar: `android --no-metrics --sdk=<ruta> sdk install <paquete>`.
- `platforms;android-36` no se usa; puede eliminarse.

## Soporte documentado (fuentes oficiales, consultadas el 2026-10-06)

| Hecho | Fuente |
| --- | --- |
| AGP 9.3 requiere Gradle ≥ 9.5.0, Build Tools 36.0.0, JDK ≥ 17; API máxima 37 | [Notas de AGP 9.3](https://developer.android.com/build/releases/agp-9-3-0-release-notes), [AGP ↔ Gradle](https://developer.android.com/build/releases/about-agp) |
| Android Studio Quail 2 (2026.1.2) o posterior admite AGP 9.3 | [Android Studio ↔ AGP](https://developer.android.com/studio/releases) |
| AGP 9 incluye Kotlin integrado, depende de KGP 2.2.10 y permite elevarlo con `classpath` en `buildscript`; no se aplica `org.jetbrains.kotlin.android` | [Notas de AGP 9.0](https://developer.android.com/build/releases/agp-9-0-0-release-notes#runtime-dependency-on-kotlin-gradle-plugin-upgrade), [Kotlin integrado](https://developer.android.com/build/migrate-to-built-in-kotlin) |
| Gradle 9.5.1 puede ejecutarse en Java 25 (desde 9.1.0). Lleva Kotlin 2.3.20 embebido para scripts y **declara probado KGP de 2.0.0 a 2.3.20** | [Compatibilidad de Gradle](https://docs.gradle.org/9.5.1/userguide/compatibility.html) |
| Kotlin 2.3 soporta Java 25 | [Novedades de Kotlin 2.3](https://kotlinlang.org/docs/whatsnew23.html) |
| KSP ≥ 2.3.0 es independiente de la versión de Kotlin; 2.3.1 soporta Kotlin integrado de AGP 9; 2.3.10 corrige clases R con AGP 9; 2.3.12 exige AGP ≥ 8.12 | [Versiones de KSP](https://github.com/google/ksp/releases) |
| Room 2.8.5: Kotlin ≥ 2.0, minSdk ≥ 23, plugin con AGP ≥ 8.4, KSP2 recomendado | [Versiones de Room](https://developer.android.com/jetpack/androidx/releases/room) |
| BOM 2026.09.00: Compose UI 1.12.1, Material3 1.4.0. El plugin del compilador Compose usa la versión de Kotlin | [Mapeo del BOM](https://developer.android.com/develop/ui/compose/bom/bom-mapping), [Compilador Compose](https://developer.android.com/develop/ui/compose/compiler) |
| Sumas SHA-256 oficiales de la distribución y del wrapper | [Distribuciones de Gradle](https://services.gradle.org/distributions/) |
| Configuración del respaldo del sistema | [Respaldo Android](https://developer.android.com/identity/data/autobackup) |

Versiones publicadas consultadas en `dl.google.com/dl/android/maven2`, Maven Central y `services.gradle.org/versions/all`.

## Configuración elegida

| Pieza | Versión | Motivo |
| --- | --- | --- |
| Gradle | 9.5.1 | Parche de la versión mínima exigida por AGP 9.3 |
| Android Gradle Plugin | 9.3.3 | Tercer parche de la versión menor anterior; 9.4.0 salió en septiembre de 2026 |
| Kotlin (KGP y plugin Compose) | 2.3.21 | Parche de corrección de la línea 2.3. **No figura en el rango que Gradle 9.5.1 declara probado (hasta 2.3.20)**; su uso se apoya en la verificación empírica de este proyecto, no en soporte documentado. Alternativa documentada si apareciera un problema atribuible: 2.3.20 |
| KSP | 2.3.12 | KSP2; incluye las correcciones para AGP 9 |
| Compose BOM | 2026.09.00 | Parche estable |
| activity-compose | 1.13.0 | Estable; `ComponentActivity`, `setContent`, `enableEdgeToEdge` |
| Room | 2.8.5 | Último estable de 2.8 |
| junit / androidx.test:runner / androidx.test.ext:junit | 4.13.2 / 1.7.0 / 1.3.0 | Añadidas el 2026-10-12, solo en `androidTest`, para las pruebas instrumentadas de Room. Estables desde julio de 2025 ([AndroidX Test](https://developer.android.com/jetpack/androidx/releases/test)) |
| Build Tools | 36.0.0 | Valor por defecto de AGP 9.3 |
| compileSdk | 37 | Obligatorio: Compose 1.12.1 declara en sus metadatos AAR que exige API 37 |
| targetSdk | 36 | No adoptar el comportamiento de API 37 sin probarlo en un dispositivo |
| minSdk | 26 (provisional) | Propuesta documentada. El teléfono de la usuaria (Android 16, API 36) lo cumple; sin probar todavía en él (VP-01, doc 09) |
| Bytecode Java/Kotlin | 17 | Valor por defecto de AGP 9.3; `jvmTarget` toma `targetCompatibility` |

Sin versiones dinámicas ni prereleases; todas en `gradle/libs.versions.toml`.

Configuración provisional adicional:

- `applicationId` y `namespace`: `com.example.pagaya`. **Provisional, solo desarrollo** (VP-05). No registrar en Google Cloud ni distribuir con él.
- `versionName` `0.1.0-dev`, `versionCode` 1. Sin firma de distribución; el APK debug usa la clave debug local. **Actualizado 2026-10-17:** identidad release `com.fielamornoble.pagaya` con firma propia ([21](21_Firma_Distribucion.md)); `versionName` base `0.1.0`, `versionCode` 2 (release `0.1.0-rc.2`, debug/QA `0.1.0-dev`). Ver [22](22_Validacion_Release_Drive.md).
- Respaldo del sistema desactivado: `allowBackup="false"`, `fullBackupContent="false"` y `data_extraction_rules.xml` excluye todos los dominios en `cloud-backup` y `device-transfer` (doc 04, R-13). Pendiente comprobarlo en el teléfono (T-37).
- Icono adaptativo provisional; tema Material 3 claro/oscuro sin color dinámico.
- Plugin Room con `schemaDirectory("app/schemas")`; esquema versión 1 exportado desde el 2026-10-12.

## Pruebas ejecutadas

### 2026-10-06 (arranque)

| Comando | Resultado |
| --- | --- |
| Descarga de `gradle-9.5.1-bin.zip` y `sha256sum` | Coincide con la suma oficial `bafc141b…b031f` |
| `gradle wrapper --gradle-version 9.5.1 --distribution-type bin --gradle-distribution-sha256-sum …` (directorio temporal) | `gradle-wrapper.jar` coincide con la suma oficial `497c8c2a…a9c7` |
| `./gradlew assembleDebug` con compileSdk 36 | Falló: Compose 1.12.1 exige compileSdk ≥ 37 |
| `./gradlew assembleDebug` con compileSdk 37 | BUILD SUCCESSFUL |
| `aapt2 dump badging app-debug.apk` | `com.example.pagaya`, minSdk 26, targetSdk 36, compileSdk 37, etiqueta «Paga Ya!», `MainActivity` lanzable |
| `./gradlew lintDebug` | BUILD SUCCESSFUL; 0 errores, 4 avisos |

### 2026-10-07 (corrección)

| Comando | Resultado |
| --- | --- |
| `Test-Path` y listado de `C:\Users\lionb\AppData\Local\Android\Sdk` | Existe, con `platforms\android-36`, `platforms\android-37.0`, `build-tools\36.0.0`, `platform-tools`, `licenses\android-sdk-license` |
| `.\gradlew.bat assembleDebug lintDebug --rerun-tasks --no-build-cache --no-daemon --console=plain` (desde la sesión de desarrollo, sin sandbox) | **BUILD SUCCESSFUL in 1m 13s, 47 actionable tasks: 47 executed**. Daemon de un solo uso nuevo. Se ejecutaron `kspDebugKotlin`, `compileDebugKotlin`, `packageDebug`, `assembleDebug`, `lintAnalyzeDebug` y `lintDebug`. Solo aparecen como UP-TO-DATE tareas de ciclo de vida sin acciones (`preBuild`, `preDebugBuild`, `generateDebugAssets`, `preDebugUnitTestBuild`). APK regenerado: 2026-10-07 18:02:09, 11 767 630 bytes |
| Lint (SARIF de esa ejecución) | 0 errores. 4 avisos: `OldTargetApi` (targetSdk 36), `AndroidGradlePluginVersion` ×2 (Gradle 9.8.0 y AGP 9.4.1 disponibles), `NewerVersionAvailable` (Kotlin 2.4.20). Aceptados; no se actualiza solo para eliminarlos |
| `Sdk\platform-tools\adb.exe devices -l` | Lista vacía |

## Pendientes de fase 0

- **Reproducibilidad en el entorno del orquestador:** su sandbox no lee el SDK. Requiere ejecutar fuera del sandbox o ampliar sus permisos de lectura (ver incidente).
- **VP-01 Teléfono real:** parcialmente resuelta el 2026-10-12. Es un Redmi Note 14 Pro 24090RA29G con Android 16 y ≈ 99,7 GB libres (detalle en doc 09). Faltan la RAM, Google Play services y todas las pruebas en ese teléfono, incluidas las exclusiones de respaldo.
- **Instalación y apertura:** con depuración USB, `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Alternativa: emulador (paquetes `emulator` e imagen de sistema, no autorizados ni instalados).
- **VP-05 Paquete y custodia de firma:** `applicationId` definitivo y keystore fuera del repositorio.
- **Prueba temprana de Drive (fase 0, tareas 5–6): no realizada.** Prerrequisitos: paquete definitivo, keystore y SHA-1 de distribución, proyecto Google Cloud con Drive API, consentimiento y cliente Android, cuenta destinataria (VP-04) y revisión OAuth vigente (VP-06). Drive sigue en V1.
- **VP-02 Experiencia del mantenedor** y validaciones con la usuaria (tareas 1, 7 y 8 de fase 0).
- Si se usa Android Studio: Quail 2 (2026.1.2) o posterior.
- En el primer commit, marcar `gradlew` como ejecutable: `git add --chmod=+x gradlew`.

## Siguiente incremento recomendado

Implementado el 2026-10-12 como base Room v1 ([13](13_Persistencia_Local.md)); sus pruebas instrumentadas siguen pendientes de ejecutar en un dispositivo.
