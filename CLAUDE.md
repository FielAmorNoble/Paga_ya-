# CLAUDE.md — Paga Ya!

## Producto

Libreta digital de cuentas por cliente (fiados, abonos, pagos) para un pequeño negocio familiar en Colombia. Android, offline, un solo teléfono, COP enteros, saldo derivado de movimientos. Drive manual para respaldo forma parte de V1.

Fuera de alcance: inventario, facturación, usuarios/roles, sincronización, backend propio, autenticación obligatoria. Ver `docs/00_Vision_Proyecto.md`.

## Documentos autoritativos

- Alcance y supuestos: `docs/00_Vision_Proyecto.md`, `docs/01_Requerimientos_Funcionales.md`
- Modelo de datos y reglas de integridad: `docs/03_Modelo_Datos.md`
- Arquitectura y convenciones técnicas: `docs/04_Arquitectura.md`
- Backup, Drive y restauración: `docs/05_Estrategia_Offline_Backup.md`
- Fases y criterios de salida: `docs/07_Plan_Implementacion_V1.md`
- Decisiones, validaciones pendientes y riesgos: `docs/09_Decisiones_Riesgos.md`
- Casos de prueba: `docs/10_Plan_Pruebas.md`
- Toolchain, versiones y estado del arranque: `docs/12_Arranque_Tecnico.md`
- Esquema físico Room, restricciones y su verificación: `docs/13_Persistencia_Local.md`
- Ramas, variantes y entornos: `docs/14_Entornos_Desarrollo.md`
- Gestión de clientes (estado y pendientes de HU-02/HU-06): `docs/15_Gestion_Clientes.md`
- Movimientos, saldo e historial (reglas, decisiones, pruebas, pendientes): `docs/16_Movimientos_Saldo_Historial.md`
- Detalle, corrección y anulación de movimientos (revisiones, versiones, idempotencia): `docs/17_Correcciones_Movimientos.md`
- Archivar y reactivar clientes, vista «Archivados»: `docs/18_Archivo_Clientes.md`
- Respaldo en archivo (contrato del ZIP, validador, exportación y estado): `docs/19_Respaldo_Archivo.md`

Si un documento contradice a otro o al código, señalarlo antes de cambiar una decisión de producto.

## Convenciones

- Kotlin + Jetpack Compose + Room/SQLite, un único módulo `app`, Gradle Kotlin DSL, versiones fijadas en `gradle/libs.versions.toml`. Sin versiones dinámicas.
- AGP 9 con Kotlin integrado: no aplicar `org.jetbrains.kotlin.android`. Kotlin se eleva vía `buildscript` en el `build.gradle.kts` raíz.
- Actualizar Gradle, AGP, Kotlin y KSP en bloque y registrar el cambio en `docs/12_Arranque_Tecnico.md`.
- Textos de UI en español en `res/values/strings.xml`; código en inglés.
- Sin Hilt/Koin/Retrofit/WorkManager/Firebase salvo decisión documentada. Inyección por constructor.
- No crear carpetas, interfaces o clases vacías para anticipar la arquitectura.
- `com.example.pagaya` es un identificador provisional de desarrollo: no registrarlo en Google ni distribuir con él.
- Nunca incluir datos reales, respaldos, tokens, keystores ni `local.properties` en el repositorio. Fixtures solo sintéticos.

## Comandos verificados (Windows)

Requiere JDK 17+ en `PATH` o `JAVA_HOME`, y `local.properties` con `sdk.dir` (no versionado).

```
.\gradlew.bat assembleDebug     # app de desarrollo com.example.pagaya.dev («Paga Ya! Dev»)
.\gradlew.bat assembleQa        # app aislada com.example.pagaya.qa («Paga Ya! QA») para verificaciones manuales con datos sintéticos
.\gradlew.bat assembleRelease   # app normal com.example.pagaya, sin firma de distribución
.\gradlew.bat lintDebug         # informe en app/build/reports/lint-results-debug.html
.\gradlew.bat testDebugUnitTest # pruebas JVM (reglas de dominio)
.\gradlew.bat assembleDebugAndroidTest   # compila las pruebas instrumentadas
.\gradlew.bat connectedDebugAndroidTest  # las ejecuta contra com.example.pagaya.dev (verificado en API 35);
                                         # instala y desinstala solo .dev y .dev.test
```

En Git Bash: `./gradlew assembleDebug lintDebug`.

Evidencia de reproducibilidad (proceso nuevo, sin caché ni daemon reutilizado):

```
.\gradlew.bat assembleDebug lintDebug --rerun-tasks --no-build-cache --no-daemon --console=plain
```

Un build incremental con un daemon reutilizado corre con los permisos de ese daemon, no del proceso actual: no sirve como evidencia de acceso al SDK. El proceso debe poder leer el SDK (ver `docs/12_Arranque_Tecnico.md`, incidente 2026-10-07).

## Ramas y variantes

- `main`: entregas revisadas y estables. `develop`: integración y desarrollo; se trabaja en `develop`.
- Ambas ramas conservan pruebas, documentación y `app/schemas`. Las pruebas se empaquetan en un APK aparte y no entran en la app final.
- Las pruebas instrumentadas usan solo la variante debug (`com.example.pagaya.dev`). Nunca desinstalar ni borrar datos de `com.example.pagaya` ni instalar el APK release en un teléfono sin una decisión explícita.
- `qa` (`com.example.pagaya.qa`, «Paga Ya! QA»): copia de debug con datos propios, solo para comprobaciones manuales con datos sintéticos (p. ej. exportar o restaurar). Usarla en vez de Dev cuando una prueba saque datos de la app. No desinstalarla sin decisión explícita.
- Antes de ejecutar pruebas en un dispositivo, listar los paquetes `pagaya` instalados.
- `connectedDebugAndroidTest` desinstala `com.example.pagaya.dev` al terminar y con ello **borra sus datos** (los clientes ficticios de la usuaria). Si la app Dev tiene datos, usar `adb install -r` de ambos APK y `adb shell am instrument -w com.example.pagaya.dev.test/androidx.test.runner.AndroidJUnitRunner` (ver `docs/16` y `docs/17`), y desinstalar solo `.dev.test`.
- No usar `reset --hard`, `clean`, force push ni borrar ramas para ordenar el repositorio.

## Forma de trabajo

- Ejecutar solo la iteración pedida. No ampliar el alcance por iniciativa propia ni continuar con la siguiente fase sin instrucción.
- Distinguir siempre: compilación comprobada, ejecución en Android comprobada y pendientes por falta de entorno. No declarar resultados no ejecutados.
- Cambios de esquema Room incluyen migración y prueba; nunca `fallbackToDestructiveMigration`.
- Las restricciones adicionales viven en `SchemaConstraints.kt` como triggers que Room no exporta ni migra: toda migración debe dejar exactamente `SchemaConstraints.definitions` (se verifica al abrir).
- Las escrituras pasan por los repositorios (`data/`); las pantallas usan ViewModels y nunca DAOs. Toda escritura de negocio incrementa `revision_datos` en la misma transacción.
- No hacer commits, pushes ni publicaciones salvo que se pida.
