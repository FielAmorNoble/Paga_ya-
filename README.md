# Paga Ya!

Una libreta digital de cuentas por cliente para un pequeño emprendimiento familiar en Colombia.

**Estado: base local en desarrollo, 12 de octubre de 2026.** Existe un proyecto Android que compila, muestra una pantalla inicial vacía y contiene la base de datos local (Room, esquema 1) sin pantallas que la usen. **Ninguna funcionalidad de la libreta está disponible para la usuaria todavía.** Los criterios de prueba son compromisos para el desarrollo, no resultados de pruebas ejecutadas.

| | Estado |
| --- | --- |
| Proyecto Gradle, APK debug y Android Lint | Implementado y comprobado en Windows |
| Pantalla inicial «Paga Ya!» con estado vacío | Implementada; no probada en un teléfono |
| Base local Room v1: tablas, restricciones, metadata inicial | Implementada; 21 pruebas instrumentadas correctas en un teléfono con Android 15 (API 35); pendiente API 26. Ver [13](docs/13_Persistencia_Local.md) |
| Repositorio financiero (saldo, prefijos, revisiones) | Planificado |
| Clientes, fiados, abonos, saldo, historial | Planificado |
| Exportación, restauración y Google Drive | Planificado (Drive forma parte de V1) |

La V1 permitirá registrar fiados, abonos y pagos, consultar cuánto debe cada cliente y recuperar la información desde un respaldo. Funcionará sin Internet; Google Drive se utilizará únicamente para copias autorizadas.

## Decisiones de partida

- Android instalado, Kotlin, Jetpack Compose y Room sobre SQLite; un solo módulo y sin servidor propio.
- Dinero en pesos colombianos enteros; el saldo se obtiene de los movimientos.
- Compra a crédito y fiado representan el mismo movimiento. Un pago completo es un abono por el saldo vigente.
- Correcciones con versiones anteriores conservadas; anulación lógica, sin borrado financiero silencioso.
- Backup lógico en JSON, empaquetado con un manifiesto de integridad; exportación local y Google Drive manual dentro de V1.
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

Se conservan los nueve documentos solicitados. Se agregan tres anexos para que decisiones, pruebas y fuentes puedan mantenerse sin sobrecargar el plan de implementación. Este índice es el punto de entrada; cada regla tiene un documento responsable.

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
| Normal (release) | `com.example.pagaya` | Paga Ya! | `assembleRelease` | `app/build/outputs/apk/release/app-release-unsigned.apk` (sin firma de distribución) |

Al tener `applicationId` distintos, cada una usa su propio directorio de datos y su propia `pagaya.db`. Las pruebas instrumentadas se ejecutan **solo contra la variante de desarrollo**:
- `assembleDebugAndroidTest` compila el APK de pruebas `com.example.pagaya.dev.test`.
- `connectedDebugAndroidTest` instala `.dev` y `.dev.test`, ejecuta las pruebas y desinstala solo esos dos paquetes.

Las pruebas se conservan en el repositorio, también en `main`, pero se empaquetan aparte: el APK release no contiene clases de prueba. Para instalar la app de desarrollo en un teléfono con depuración USB: `adb install -r app/build/outputs/apk/debug/app-debug.apk`. No instalar el APK release sin una decisión explícita. Detalles en [14](docs/14_Entornos_Desarrollo.md).

## Ramas

- `main`: entregas revisadas y estables.
- `develop`: integración y desarrollo.

Ambas conservan pruebas, documentación y esquemas Room.

El identificador `com.example.pagaya` es provisional y solo sirve para desarrollo. Versiones y justificación en [12 — Arranque técnico](docs/12_Arranque_Tecnico.md).

## Cómo continuar

1. Completar las verificaciones pendientes de fase 0: teléfono real, experiencia del mantenedor, paquete y firma definitivos y la prueba temprana de Drive.
2. Ejecutar las pruebas instrumentadas de la base local también en API 26 y registrar el resultado en el documento 13.
3. Construir por fases y registrar evidencia de sus criterios de salida.

Los primeros datos de prueba deben ser ficticios. Nunca incorporar cuentas reales, respaldos, tokens o llaves de firma al repositorio. La licencia de distribución del código queda pendiente de decisión del propietario; no se asigna una licencia por defecto.
