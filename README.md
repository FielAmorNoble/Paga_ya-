# Paga Ya!

Una libreta digital de cuentas por cliente para un pequeño emprendimiento familiar en Colombia.

[Descargar versión de prueba](https://github.com/FielAmorNoble/Paga_ya-/releases/tag/v0.1.0-rc.3) · [Guía de instalación y respaldo](docs/releases/v0.1.0-rc.3.md)

**Estado: versión preliminar `0.1.0-rc.3` para prueba familiar, 17 de octubre de 2026.** La app permite gestionar clientes, fiados, abonos, pagos, correcciones, anulaciones y archivado; exportar una copia verificada; y restaurarla con vista previa, protección privada del estado anterior y reemplazo transaccional. Todo funciona sin Internet con archivos locales; las copias pueden guardarse y abrirse en una carpeta de Google Drive desde el selector de archivos de Android. El APK oficial firmado, la actualización conservando datos y el respaldo/restauración mediante Drive se comprobaron en un teléfono de pruebas ([22](docs/22_Validacion_Release_Drive.md)). **Durante el piloto se conserva la libreta habitual. La validación en el teléfono final, la licencia y la recuperación independiente de la clave siguen pendientes para el cierre estable.** No se integrará una API propia de Drive en V1. Firma y distribución: [21](docs/21_Firma_Distribucion.md).

| | Estado |
| --- | --- |
| Proyecto Gradle, APK debug y Android Lint | Implementado y comprobado en Windows |
| Clientes: crear, listar, buscar sin distinguir mayúsculas ni acentos, consultar y editar (HU-02 y HU-06) | Implementado; probado en el teléfono del desarrollador (Android 15). Ver [15](docs/15_Gestion_Clientes.md) |
| Archivar (solo con saldo cero) y reactivar clientes; vista «Archivados» con buscador (HU-02.6, HU-06.6) | Implementado; 149 pruebas instrumentadas y 54 JVM correctas; recorrido manual en el teléfono del desarrollador (Android 15). HU-02 y HU-06 completas en su funcionalidad. Ver [18](docs/18_Archivo_Clientes.md) |
| Base local Room v1: tablas, restricciones, metadata inicial | Implementada; 21 pruebas instrumentadas correctas en un teléfono con Android 15 (API 35); pendiente API 26. Ver [13](docs/13_Persistencia_Local.md) |
| Movimientos: fiado, abono y pago completo; saldo derivado; historial con saldo por movimiento (HU-01, HU-03, HU-04, HU-05 y HU-07 en su parte de registro y lectura) | Implementado; 71 pruebas instrumentales y 30 JVM correctas; recorrido manual en el teléfono del desarrollador (Android 15). Ver [16](docs/16_Movimientos_Saldo_Historial.md) |
| Detalle, corrección y anulación de movimientos con revisiones y «Ver cambios» (HU-08; parte de HU-07) | Implementado; 54 pruebas JVM y 123 instrumentadas correctas; recorrido manual en el teléfono del desarrollador (Android 15). No hay reactivación de anulados. Ver [17](docs/17_Correcciones_Movimientos.md) |
| Respaldo en archivo: «Respaldo» en el menú, captura consistente, ZIP con `manifest.json` y `data.json`, validador reutilizable, selector del sistema, relectura y verificación, estado de la última copia y cambios sin respaldar (RF-11, RF-13 para archivo) | Implementado; 88 pruebas JVM y 167 instrumentadas correctas. En el teléfono, en la app aislada «Paga Ya! QA» con datos sintéticos: exportación a Descargas en modo avión verificada (también fuera del teléfono), cambios sin respaldar, cancelación y proceso cerrado con el selector abierto. Destino Drive comprobado después en [22](docs/22_Validacion_Release_Drive.md). Ver [19](docs/19_Respaldo_Archivo.md) |
| Restauración desde archivo y «Recuperar datos anteriores» | Implementado; 23 pruebas de servicio y 4 de pantalla correctas; ensayo manual QA → QA Restauración vacía, reemplazo y recuperación anterior. Regresión completa previa: 195 correctas y 2 auxiliares opt-in omitidos (runner 197), 105 JVM. Ver [20](docs/20_Restauracion_Archivo.md) |
| Google Drive mediante selector Android | Comprobado con APK firmado y datos sintéticos: guardar en carpeta privada, archivo visible en Drive web, restaurar en otra instalación con contenido idéntico, cancelación y comportamiento sin conexión. Sin OAuth propio, appDataFolder ni retención automática en V1. Ver [22](docs/22_Validacion_Release_Drive.md) |
| APK oficial firmado y actualización | rc.1 instalado y actualizado a rc.2 conservando datos exactamente. Ver [22](docs/22_Validacion_Release_Drive.md) |

La V1 permitirá registrar fiados, abonos y pagos, consultar cuánto debe cada cliente y recuperar la información desde un respaldo. Funcionará sin Internet; Google Drive se utilizará únicamente para guardar copias elegidas por la usuaria en el selector.

## Decisiones de partida

- Android instalado, Kotlin, Jetpack Compose y Room sobre SQLite; un solo módulo y sin servidor propio.
- Dinero en pesos colombianos enteros; el saldo se obtiene de los movimientos.
- Compra a crédito y fiado representan el mismo movimiento. Un pago completo es un abono por el saldo vigente.
- Correcciones con versiones anteriores conservadas; anulación lógica, sin borrado financiero silencioso.
- Backup lógico en JSON, empaquetado con un manifiesto de integridad; exportación local y a Google Drive mediante el selector Android dentro de V1.
- Restauración validada y transaccional de toda la libreta; sin mezcla ni sincronización entre teléfonos.

Estas decisiones son la propuesta técnica del proyecto. Sus supuestos, límites y condiciones de revisión están en los documentos siguientes.

## Documentación

| Documento | Contenido |
| --- | --- |
| [00 — Visión](docs/00_Vision_Proyecto.md) | Problema, alcance, supuestos y medidas de éxito |
| [01 — Requerimientos](docs/01_Requerimientos_Funcionales.md) | Funciones, reglas de negocio y requisitos de calidad |
| [02 — Historias de usuario](docs/02_Historias_Usuario.md) | HU-01 a HU-09 y criterios de aceptación |
| [03 — Modelo de datos](docs/03_Modelo_Datos.md) | Entidades, restricciones, cálculo y trazabilidad |
| [04 — Arquitectura](docs/04_Arquitectura.md) | Comparación tecnológica, stack y estructura futura |
| [05 — Offline y backup](docs/05_Estrategia_Offline_Backup.md) | Formato, Drive, retención y recuperación |
| [06 — UX y flujos](docs/06_UX_Flujos.md) | Wireframes, lenguaje, accesibilidad y errores |
| [07 — Plan de implementación V1](docs/07_Plan_Implementacion_V1.md) | Fases 0–8, tareas, dependencias y condiciones de entrega |
| [08 — Backlog futuro](docs/08_Backlog_Futuro.md) | Roadmap opcional y criterios de priorización |
| [09 — Decisiones y riesgos](docs/09_Decisiones_Riesgos.md) | Registro de decisiones, riesgos y validaciones pendientes |
| [10 — Plan de pruebas](docs/10_Plan_Pruebas.md) | Casos verificables y cobertura de historias |
| [11 — Fuentes técnicas](docs/11_Fuentes_Tecnicas.md) | Referencias oficiales y alcance de la investigación |
| [12 — Arranque técnico](docs/12_Arranque_Tecnico.md) | Herramientas, versiones fijadas, comandos y pendientes de fase 0 |
| [13 — Persistencia local](docs/13_Persistencia_Local.md) | Modelo físico Room, restricciones, inicialización, pruebas y límites de verificación |
| [14 — Ramas y entornos](docs/14_Entornos_Desarrollo.md) | Política main/develop, variantes debug (.dev) y release, verificaciones |
| [15 — Gestión de clientes](docs/15_Gestion_Clientes.md) | Recorrido de clientes, reglas, pruebas, recorrido en el teléfono y pendientes |
| [16 — Movimientos, saldo e historial](docs/16_Movimientos_Saldo_Historial.md) | Registro de fiados, abonos y pago completo; reglas, decisiones, pruebas, pendientes y pasos para probar en el teléfono |
| [17 — Correcciones de movimientos](docs/17_Correcciones_Movimientos.md) | Detalle, corrección y anulación con trazabilidad; integridad, decisiones, pruebas, pendientes y pasos para probar |
| [18 — Archivo de clientes](docs/18_Archivo_Clientes.md) | Archivar y reactivar clientes, vista «Archivados»; reglas, carreras, pruebas, revisión de HU-02/HU-06 y pasos para probar |
| [19 — Respaldo en archivo](docs/19_Respaldo_Archivo.md) | Contrato exacto del archivo, arquitectura de captura/validación/exportación, validaciones, pruebas, pendientes y pasos para probar |
| [20 — Restauración desde archivo](docs/20_Restauracion_Archivo.md) | Vista previa, base temporal, exclusión, rollback, copia previa y recuperación; evidencia y límites reales |
| [21 — Firma y distribución](docs/21_Firma_Distribucion.md) | Identidad oficial, clave, compilación firmada y verificación |
| [22 — Validación release y Drive](docs/22_Validacion_Release_Drive.md) | Guía sencilla de instalación, actualización, respaldo y recuperación; evidencia del APK firmado y de Drive mediante el selector; pendientes antes de publicar |

Se conservan los nueve documentos solicitados. Se agregan tres anexos para que decisiones, pruebas y fuentes puedan mantenerse sin sobrecargar el plan de implementación. Este índice es el punto de entrada; cada regla tiene un documento responsable.

## Firma oficial

La clave privada se custodia fuera del repositorio. El procedimiento de compilación firmada, huella pública y pendientes están en [21 — Firma y distribución](docs/21_Firma_Distribucion.md). La primera entrega se distribuye como prerelease para prueba familiar; no es la V1 estable.

## Compilar

Requisitos: JDK 17 o superior (verificado con JDK 25) y Android SDK con `platforms;android-37.0` y `build-tools;36.0.0`. Android Studio es opcional; si se usa, debe ser Quail 2 (2026.1.2) o posterior.

1. Crear `local.properties` en la raíz (no se versiona) con la ruta del SDK, por ejemplo:
   `sdk.dir=C\:/Users/<usuario>/AppData/Local/Android/Sdk`
2. Compilar y revisar:

```bash
./gradlew assembleDebug lintDebug
```

En PowerShell: `.\gradlew.bat assembleDebug lintDebug`. Para una verificación desde cero, añadir `--rerun-tasks --no-build-cache --no-daemon`; el proceso que ejecuta Gradle debe tener permiso de lectura sobre el directorio del SDK.

### Variantes

| Variante | applicationId | Nombre visible | Comando | APK |
| --- | --- | --- | --- | --- |
| Desarrollo (debug) | `com.example.pagaya.dev` | Paga Ya! Dev | `assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` |
| Verificación (qa) | `com.example.pagaya.qa` | Paga Ya! QA | `assembleQa` | `app/build/outputs/apk/qa/app-qa.apk`. Copia de debug para comprobaciones manuales con datos sintéticos; no comparte datos con Dev |
| Restauración aislada (qaRestore) | `com.example.pagaya.qa.restore` | Paga Ya! QA Restauración | `assembleQaRestore` | `app/build/outputs/apk/qaRestore/app-qaRestore.apk`. Segunda libreta independiente para ensayos sintéticos |
| Oficial (release) | `com.fielamornoble.pagaya` | Paga Ya! | `assembleRelease` o script de firma | `app/build/outputs/apk/release/app-release.apk` con credenciales; `app-release-unsigned.apk` sin ellas. Ver [21](docs/21_Firma_Distribucion.md) |

Al tener `applicationId` distintos, cada una usa su propio directorio de datos y su propia `pagaya.db`. Las pruebas instrumentadas se ejecutan **solo contra la variante de desarrollo**:
- `assembleDebugAndroidTest` compila el APK de pruebas `com.example.pagaya.dev.test`.
- **No usar `connectedDebugAndroidTest` con datos Dev:** desinstala `.dev` al terminar y borra sus datos. Usar `adb install -r` para debug y tests, seguido de `adb shell am instrument -w com.example.pagaya.dev.test/androidx.test.runner.AndroidJUnitRunner`. Las pruebas usan bases independientes; retirar solo `.dev.test` al terminar.

Las pruebas se conservan en el repositorio, también en `main`, pero se empaquetan aparte: el APK release no contiene clases de prueba. Para instalar la app de desarrollo en un teléfono con depuración USB: `adb install -r app/build/outputs/apk/debug/app-debug.apk`. No instalar el APK release sin una decisión explícita. Detalles en [14](docs/14_Entornos_Desarrollo.md).

## Ramas

- `main`: entregas revisadas y estables.
- `develop`: integración y desarrollo.

Ambas conservan pruebas, documentación y esquemas Room.

La identidad oficial es `com.fielamornoble.pagaya`; se conserva `com.example.pagaya` como namespace interno y base de las variantes de pruebas. Versiones y justificación en [12 — Arranque técnico](docs/12_Arranque_Tecnico.md).

## Cómo continuar

1. Cerrar los pendientes antes de publicar listados en el [documento 22](docs/22_Validacion_Release_Drive.md): teléfono final, licencia del repositorio y recuperación independiente de la clave de firma.
2. Ejecutar las pruebas instrumentadas de la base local también en API 26 y registrar el resultado en el documento 13.
3. Construir por fases y registrar evidencia de sus criterios de salida.

Los primeros datos de prueba deben ser ficticios. Nunca incorporar cuentas reales, respaldos, tokens o llaves de firma al repositorio. La licencia de distribución del código queda pendiente de decisión del propietario; no se asigna una licencia por defecto.
