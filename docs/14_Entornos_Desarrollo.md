# 14 — Ramas y entornos de desarrollo

Fecha: 2026-10-12. Iteración 03: política de ramas y separación entre la aplicación de desarrollo y la normal. No incluye funcionalidades de negocio ni cambios del esquema Room.

## Política de ramas

| Rama | Uso |
| --- | --- |
| `main` | Entregas revisadas y estables |
| `develop` | Integración y desarrollo diario |

Las dos ramas conservan el código, las pruebas (`app/src/androidTest`), la documentación y los esquemas Room (`app/schemas`). Las pruebas viven en `main`, pero **no forman parte de la aplicación final**: se compilan en un APK de pruebas aparte. Esto está verificado más abajo.

### Estado en esta iteración

- Antes:
  - En local solo existía `main`, en `623c556`, igual a `origin/main`.
  - `git ls-remote --heads origin` solo mostraba `main`.
  - Todo el trabajo de las iteraciones 01–02 estaba sin commitear.
- Acción: `git switch -c develop`. `develop` nace en el mismo commit que `main`, y los cambios sin commitear siguen intactos en el árbol de trabajo (mismas 13 entradas en `git status` antes y después).
- No se hicieron commits, merges, pushes, `reset`, `clean` ni borrados. `develop` todavía no existe en el remoto.

## Variantes de la aplicación

Se usan dos build types; no hay product flavors ni módulos adicionales.

| | debug (desarrollo) | release (normal) |
| --- | --- | --- |
| `applicationId` | `com.example.pagaya.dev` (`applicationIdSuffix = ".dev"`) | `com.example.pagaya` |
| Nombre visible | «Paga Ya! Dev» (`app/src/debug/res/values/strings.xml`) | «Paga Ya!» |
| Depurable | Sí | No |
| Firma | Clave debug local | **Ninguna**: `app-release-unsigned.apk`. Sin firma de distribución (VP-05) |
| Pruebas instrumentadas | Sí: `testBuildType = "debug"` | No |

- `namespace` (`com.example.pagaya`), los paquetes Kotlin y el nombre `pagaya.db` no cambian. El aislamiento viene del `applicationId`: Android asigna a cada `applicationId` su propio directorio de datos, y `pagaya.db` vive en el `databases/` de ese directorio.
- El identificador base `com.example.pagaya` sigue siendo **provisional**. No se configuró OAuth ni firma de distribución.
- Los fixtures sintéticos siguen solo en `app/src/androidTest`. La aplicación normal no incluye datos de ejemplo.

## Verificaciones

### Ejecutadas

| Verificación | Resultado |
| --- | --- |
| `.\gradlew.bat assembleDebug assembleRelease assembleDebugAndroidTest lintDebug --rerun-tasks --no-build-cache --no-daemon --console=plain` | BUILD SUCCESSFUL, 126/126 tareas ejecutadas. Se generaron `app-debug.apk` (11,8 MB), `app-release-unsigned.apk` (8,4 MB) y `app-debug-androidTest.apk` (0,4 MB) |
| `aapt2 dump badging` del APK debug | `package: com.example.pagaya.dev`, etiqueta «Paga Ya! Dev», `application-debuggable` |
| `aapt2 dump badging` del APK release | `package: com.example.pagaya`, etiqueta «Paga Ya!», no depurable |
| Manifiesto del APK de pruebas | Paquete `com.example.pagaya.dev.test`; `<instrumentation android:name="androidx.test.runner.AndroidJUnitRunner" android:targetPackage="com.example.pagaya.dev">` |
| `apksigner verify app-release-unsigned.apk` | `DOES NOT VERIFY — Missing META-INF/MANIFEST.MF`: confirma que no tiene firma |
| Clases de prueba en el APK release (búsqueda de descriptores en `classes*.dex`) | 0 coincidencias de `ConstraintsTest`, `DatabaseSetupTest`, `TestDatabaseFile`, `org/junit/` y `androidx/test/`. En el APK de pruebas todas aparecen. Control: `PagaYaDatabase` sí aparece en el release |
| `lintDebug` | 0 errores; los mismos 4 avisos de versiones aceptados |
| `lintVitalRelease` (dentro de `assembleRelease`) | «No issues found» |
| Paquetes del teléfono **antes** de las pruebas (`pm list packages`, filtro `pagaya`) | Ninguno: no había instalación previa de `com.example.pagaya` ni de `.dev` |
| `.\gradlew.bat connectedDebugAndroidTest --info` | **21/21 correctas, 0 fallos, 0 errores, 0 omitidas** (ConstraintsTest 12, DatabaseSetupTest 6, TransactionTest 3), 3,9 s, 2026-10-12T20:35:27, en moto g84 5G con Android 15 (API 35). Configuración real del runner: `app_package: "com.example.pagaya.dev"`, `test_package: "com.example.pagaya.dev.test"`, `instrumentation_target_package_id: "com.example.pagaya.dev"`, `uninstall_after_test: true`. Instaló `app-debug.apk` y `app-debug-androidTest.apk`; al terminar desinstaló **solo** `com.example.pagaya.dev` y `com.example.pagaya.dev.test` |
| Directorio de datos de la app de desarrollo, en el teléfono | Tras instalar `app-debug.apk` a mano: `run-as com.example.pagaya.dev pwd` → `/data/user/0/com.example.pagaya.dev`; `dumpsys package` → `dataDir=/data/user/0/com.example.pagaya.dev`, `pkgFlags=[DEBUGGABLE …]`. Después se desinstaló `com.example.pagaya.dev` |
| Paquetes del teléfono **después** | Ninguno con `pagaya` |

El teléfono es del desarrollador (Motorola moto g84 5G, Android 15). Las pruebas no se han ejecutado en el teléfono de la usuaria final.

### Pendientes o no verificadas

- **Directorio de datos de la app normal en un dispositivo:** no se comprobó. Por instrucción, el APK release no se instaló. Se espera `/data/user/0/com.example.pagaya`, distinto del de desarrollo, por ser otro `applicationId`; está demostrado en los manifiestos, no en un dispositivo.
- **Convivencia de las dos apps instaladas a la vez:** no se probó, por la misma razón.
- **Pruebas en API 26 (mínimo):** pendientes desde la iteración 02.
- **Firma de distribución, identificador definitivo y OAuth:** fuera de alcance (VP-05).

## Hallazgo: ubicación física del Android SDK

Al ejecutar `apksigner`, Java resolvió su ruta real como `C:\Users\lionb\AppData\Local\Packages\Claude_pzs8sxrjxfjjc\LocalCache\Local\Android\Sdk\build-tools\36.0.0\lib\apksigner.jar`. Ese directorio existe y contiene `build-tools`, `cmdline-tools`, `licenses`, `platform-tools` y `platforms`.

Es la carpeta de datos virtualizada del paquete MSIX de la app de escritorio de Claude. Las escrituras que la sesión de desarrollo hizo en `%LOCALAPPDATA%\Android\Sdk` el 2026-10-06 quedaron redirigidas ahí. Dentro de esa app la ruta configurada (`C:\Users\lionb\AppData\Local\Android\Sdk`) funciona; un proceso fuera del contenedor MSIX probablemente no la encuentre.

Esto explica de forma más concreta el incidente del 2026-10-07 ([12](12_Arranque_Tecnico.md#incidente-de-reproducibilidad-2026-10-07)), en el que el proceso del orquestador no veía el SDK. **No está demostrado desde fuera del contenedor**: desde esta sesión no se puede observar la vista no virtualizada del disco. No se movió ni se reinstaló el SDK.

Consecuencia: Android Studio, una terminal de Windows normal u otro agente pueden necesitar uno de estos cambios, a decidir por el propietario:
- un `sdk.dir` que apunte a la ruta física;
- un SDK instalado fuera de la virtualización.

## Comandos comprobados

```
.\gradlew.bat assembleDebug assembleRelease assembleDebugAndroidTest lintDebug --rerun-tasks --no-build-cache --no-daemon --console=plain
.\gradlew.bat connectedDebugAndroidTest
```

`connectedDebugAndroidTest` instala y desinstala solo `com.example.pagaya.dev` y `com.example.pagaya.dev.test`. No instalar el APK release en el teléfono de desarrollo sin una decisión explícita.
