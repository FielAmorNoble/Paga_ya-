# 13 — Persistencia local (Room, esquema versión 1)

Fecha: 2026-10-12. Iteración 02 de fase 1: base local y restricciones de integridad. **No completa la fase 1 ni ninguna historia de usuario.** No hay pantallas, repositorio financiero, cálculo de saldo, backup ni restauración.

## Estado de verificación

| Comprobación | Estado |
| --- | --- |
| Compilación de la app y del APK de pruebas instrumentadas | **Comprobada** (proceso nuevo, `--rerun-tasks --no-build-cache --no-daemon`) |
| Android Lint | **Comprobado**: 0 errores; los mismos 4 avisos de versiones del arranque |
| Esquema Room v1 exportado | **Generado** en `app/schemas/com.example.pagaya.data.local.PagaYaDatabase/1.json`, hash de identidad `6c9ad4064d7a689352d698537a3cb332`, igual al del código generado |
| 21 pruebas instrumentadas con Room real | **Ejecutadas: 21/21 correctas** en Motorola moto g84 5G, Android 15 (API 35), 2026-10-12 |
| DDL y triggers sobre SQLite de escritorio | Comprobación complementaria (ver abajo); no sustituye a las pruebas en Android |
| Persistencia en archivo, FK, WAL y `synchronous=FULL` en Android | **Comprobadas en API 35** por las pruebas; **pendiente API 26** (mínimo) |

## Modelo físico

Nombres de tablas y columnas en español, iguales al modelo lógico de [03](03_Modelo_Datos.md). Las propiedades Kotlin están en inglés con `@ColumnInfo` explícito (convención de [04](04_Arquitectura.md)). Paquete `com.example.pagaya.data.local`.

| Tabla | Entidad Kotlin | Clave | Índices y relaciones |
| --- | --- | --- | --- |
| `cliente` | `ClientEntity` | `id` TEXT | `(activo, nombre_busqueda, id)` |
| `movimiento` | `MovementEntity` | `id` TEXT | FK `cliente_id` → `cliente.id` RESTRICT; `(cliente_id, fecha, orden_registro)`; UNIQUE `(orden_registro)` |
| `movimiento_revision` | `MovementRevisionEntity` | `id` TEXT | FK `movimiento_id` → `movimiento.id` RESTRICT; UNIQUE `(movimiento_id, version_anterior)` |
| `libreta_metadata` | `LedgerMetadataEntity` | `id` INTEGER = 1 | Fila única |
| `backup_registro` | `BackupRecordEntity` | `(backup_id, destino)` | Sin FK |

Tipos:

- Importes, versiones, órdenes e instantes son `Long` → `INTEGER`. Los instantes se guardan en epoch milisegundos UTC.
- Los booleanos son `Boolean` → `INTEGER` 0/1.
- La fecha económica es `LocalDate`, guardada como TEXT `YYYY-MM-DD` mediante `LocalDateConverter`.
- Los códigos (`tipo`, `origen`, `accion`, `destino`, `estado`) son enums de Kotlin cuyo nombre de constante es el valor guardado como TEXT (`FIADO`, `PAGO_TOTAL`…).

No existe columna de saldo.

### Detalles físicos decididos en esta iteración

No estaban definidos en el doc 03. Son reversibles mientras no exista una base en uso real.

| Decisión | Motivo |
| --- | --- |
| `libreta_metadata.id` = 1 con trigger de fila única y trigger que impide borrarla | Garantiza una sola fila sin depender del código. Consecuencia: la restauración futura debe **actualizar** esa fila, no borrarla y reinsertarla |
| Clave `(backup_id, destino)` en `backup_registro` | Una misma captura puede registrarse en ARCHIVO, DRIVE o RECUPERACION sin colisión. Los reintentos de un destino actualizan su fila |
| Campos de `backup_registro`: `localizador` (URI o ID de Drive, 1–2048), `cuenta_drive_huella` (1–128, solo DRIVE), `error_codigo` (1–64, solo `A-Z`, `0-9`, `_`) | El doc 03 los nombra sin longitudes. El patrón del código impide guardar texto libre con datos financieros |
| Campos de restauración en metadata: `ultima_restauracion_id` y `ultima_restauracion_en` (ambos nulos o ambos presentes) y `copia_recuperacion_localizador` (1–2048) | Concreta «identificador, hora y localizador» del doc 03 |
| Textos opcionales: ausencia = NULL; la cadena vacía se rechaza | Una sola representación de «sin dato» |
| `nombre` y `motivo` se guardan ya recortados (`valor = trim(valor)`) | El doc 03 limita las longitudes «tras trim». El recorte se hace antes de guardar, no en SQL |
| UUID en formato canónico v4, en minúsculas | Lo que produce `UUID.randomUUID().toString()`; evita dos grafías de la misma clave |
| `estado_anterior_json` debe empezar con `{` y terminar con `}` | Control estructural mínimo. La validación del contrato del snapshot es del repositorio |
| Transiciones de `backup_registro`: `verificado_en` presente si y solo si `estado = VERIFICADO`; identidad de la captura inmutable | Evita marcar como verificada una copia sin hora de verificación |

## Restricciones implementadas

**Por Room (anotaciones):** NOT NULL, claves primarias, claves foráneas `ON DELETE/UPDATE RESTRICT` (sin cascada), índices únicos.

**Por triggers** (`SchemaConstraints.kt`): 85 triggers `BEFORE INSERT/UPDATE/DELETE` que abortan con `PY_CHECK:<tabla>.<regla>`. Una sola definición en Kotlin genera los triggers de INSERT y de UPDATE de cada regla. La condición se evalúa como `(condición) IS NOT 1`, así que un resultado NULL también se rechaza. Cada regla es independiente de las demás: SQLite no garantiza el orden en que dispara los triggers. La comprobación de escritorio se ejecutó con ambos órdenes de creación.

| Tabla | Reglas (INSERT y UPDATE salvo indicación) |
| --- | --- |
| cliente | UUID; nombre recortado de 1–120 caracteres; `nombre_busqueda` texto; teléfono 1–30; nota 1–1000; instantes enteros ≥ 0; activo 0/1. Solo UPDATE: `id` y `creado_en` inmutables |
| movimiento | UUID; fecha `YYYY-MM-DD` existente en el calendario (mes 1–12, días por mes y bisiestos calculados en SQL; no se usa `date()`, ver hallazgo); `orden_registro` ≥ 1; tipo FIADO/ABONO; origen MANUAL/PAGO_TOTAL; FIADO+PAGO_TOTAL prohibido; `valor_cop` entero 1–999 999 999 (rechaza texto y reales); descripción 1–1000; instantes; activo 0/1; versión ≥ 1. Solo UPDATE: `id`, `cliente_id`, `tipo`, `orden_registro` y `creado_en` inmutables |
| movimiento_revision | Solo INSERT: UUID; `version_anterior` ≥ 1 y `version_nueva` = anterior + 1; acción EDITAR/ANULAR; snapshot `{…}`; `cambiado_en` ≥ 0; motivo recortado de 1–300. UPDATE prohibido (inmutable) |
| libreta_metadata | id = 1; `libreta_id` y `epoca_local` UUID; `revision_datos` ≥ 0; `siguiente_orden_registro` ≥ 1; `creado_en` ≥ 0; datos de restauración coherentes; localizador 1–2048. DELETE prohibido |
| backup_registro | UUID en backup, libreta y época; revisión ≥ 0; `capturado_en` ≥ 0; destino y estado válidos; coherencia de verificación; localizador; huella solo en DRIVE; código de error restringido. Solo UPDATE: identidad de la captura inmutable |

Las longitudes se miden con `length()` de SQLite, en caracteres Unicode (puntos de código). La validación de Kotlin del incremento siguiente debe contar igual, no en unidades UTF-16.

`REPLACE` no se usa: `@Insert` y `@Update` usan ABORT. No hay `fallbackToDestructiveMigration`.

## Restricciones pendientes (repositorio financiero, incremento posterior)

Cruzan filas o requieren contexto transaccional, así que no se declaran protegidas por la base:

- Saldo y prefijos cronológicos no negativos; rechazo de abono superior a la deuda.
- Asignación de `orden_registro` desde `siguiente_orden_registro`, su incremento y que siga siendo mayor que todos los órdenes existentes.
- Incremento de `revision_datos` en cada cambio de negocio.
- Continuidad de versiones: `movimiento.version` = número de revisiones + 1, y una revisión por cada cambio. Hoy la base exige `version_nueva = version_anterior + 1` dentro de cada revisión, no frente al movimiento.
- Control de versión esperada (concurrencia) e idempotencia por UUID de solicitud.
- Transición de origen PAGO_TOTAL → MANUAL al editar un importe; anulación lógica con su revisión.
- Normalización de `nombre_busqueda` y validación del contrato del snapshot JSON.
- Que un cliente archivado no tenga saldo positivo.
- Borrado de filas: solo lo tendrá la restauración completa (`DELETE` de clientes, movimientos y revisiones está permitido por la base salvo cuando lo impide RESTRICT).

## Inicialización y reapertura

- `PagaYaDatabase.build(context, fileName, clock, newId)` construye la instancia con WAL explícito. `AppContainer` (creado por `PagaYaApplication`) guarda la única instancia operativa, `pagaya.db`, en el directorio privado `databases/`, excluido del respaldo del sistema desde el arranque. Construirla no abre el archivo. Room rechaza consultas en el hilo principal (no se usa `allowMainThreadQueries`).
- **Creación:** el framework de Android ejecuta `SQLiteOpenHelper.onCreate` dentro de una transacción. Dentro de ella, Room 2.8.5 crea las tablas y luego invoca nuestro `Callback.onCreate`, que crea los 85 triggers e inserta la fila de metadata (`libreta_id` y `epoca_local` nuevos, revisión 0, siguiente orden 1). Si algo falla, no queda una base parcial. Esta cadena se confirmó en el bytecode de `room-runtime-android` 2.8.5: `SupportOpenHelperCallback.onCreate` → `createAllTables` → `invokeCreateCallback` → `Callback.onCreate(SupportSQLiteDatabase)`.
- **Reapertura:** `onCreate` no vuelve a ejecutarse, así que `libreta_id` y `epoca_local` no se regeneran. `newId` solo se invoca al crear la base.
- **Apertura (`onOpen`):**
  - Fija `PRAGMA synchronous = FULL`. En modo compatibilidad (sin driver), Room 2.8.5 no modifica `synchronous`; solo lo haría con drivers, donde en WAL pondría NORMAL. Las escrituras del framework usan la conexión primaria, que es en la que se ejecuta `onOpen`.
  - Verifica que los triggers `pagaya_*` coincidan **exactamente** (nombre y SQL) con `SchemaConstraints.definitions` y que haya una sola fila de metadata. Si no, lanza `IllegalStateException` y la base no se usa. No hay reparación silenciosa.
- Room activa `PRAGMA foreign_keys = ON` en el `onOpen` del código generado (`PagaYaDatabase_Impl`), antes de nuestro callback.

Hoy ningún código de producción abre la base: la primera consulta llegará con el repositorio. Mientras tanto, instalar la app no crea `pagaya.db`.

## Migraciones futuras

- Room exporta tablas e índices en `app/schemas/`, pero **no los triggers**. Para Room son invisibles: ni los crea en una migración ni los valida.
- Cada migración N→N+1 debe dejar en la base exactamente las definiciones de `SchemaConstraints.definitions` de la versión N+1:
  - eliminar los triggers cuya definición cambie y crearlos de nuevo;
  - recrear los triggers de cualquier tabla reconstruida (al hacer `DROP TABLE` se pierden sus triggers).
- La verificación de `onOpen` detecta cualquier diferencia de nombre o de SQL, así que una migración incompleta falla al abrir en vez de dejar la base sin protección.
- Cada migración necesitará su prueba con `MigrationTestHelper` y datos sintéticos, incluidas las restricciones tras migrar (T-20).
- No existe migración anterior a la versión 1 ni se inventa ninguna.

## Pruebas

En `app/src/androidTest/java/com/example/pagaya/data/local/`, con Room real sobre archivos `test-<uuid>.db` en el directorio de la app de pruebas.
- Cada prueba usa su propia base y la borra al terminar.
- El helper se niega a usar el nombre `pagaya.db`.
- Reloj fijo; datos sintéticos.

| Clase | Casos | Cubre |
| --- | --- | --- |
| `DatabaseSetupTest` | 6 | Metadata inicial completa y triggers exactos; reapertura del archivo con otra instancia, otro reloj y un generador de IDs que falla si se invoca; `foreign_keys=1`, `synchronous=2` (FULL) en la conexión de escritura y `journal_mode=wal`; fila única de metadata; rechazo de apertura con un trigger ausente o debilitado |
| `ConstraintsTest` | 12 | FK huérfanas y RESTRICT; PK, orden, (movimiento, versión) y backup duplicados; mismo backup en dos destinos; importes 0, −1, 10⁹, texto y real; combinaciones tipo/origen; fechas inválidas, booleanos, versión, UUID; nombres, teléfono y nota; revisiones e inmutabilidad; registros de backup; las mismas restricciones en UPDATE e inmutables |
| `TransactionTest` | 3 | Rollback de cliente + metadata cuando falla el tercer paso; una revisión rechazada deja intacto el movimiento actualizado en la misma transacción; un commit sobrevive a la reapertura |

Comandos:

```
.\gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug --rerun-tasks --no-build-cache --no-daemon --console=plain
.\gradlew.bat connectedDebugAndroidTest   # requiere dispositivo o emulador autorizado
```

### Resultados de la iteración (2026-10-12)

| Comando | Resultado |
| --- | --- |
| Verificación completa anterior (estado final del código) | BUILD SUCCESSFUL, 79 tareas ejecutadas; incluye `kspDebugKotlin`, `compileDebugKotlin`, `compileDebugAndroidTestKotlin`, `packageDebugAndroidTest`, `lintDebug` |
| Lint | 0 errores; 4 avisos ya aceptados (`OldTargetApi`, Gradle 9.8.0, AGP 9.4.1, Kotlin 2.4.20) |
| `adb devices -l` | `ZT322JX5BF`, Motorola moto g84 5G (`bangkk`), Android 15, API 35, SQLite 3.44.5. Depuración USB autorizada por el propietario durante la iteración |
| `connectedDebugAndroidTest`, 1.ª ejecución | 20/21: falló `movementRowValuesAreValidated`, que no rechazó una fecha imposible (ver hallazgo) |
| `connectedDebugAndroidTest`, ejecución final | **21/21 correctas, 0 fallos, 0 errores, 0 omitidas** (ConstraintsTest 12, DatabaseSetupTest 6, TransactionTest 3), 3,6 s, 2026-10-12T20:15:31. Gradle desinstaló la app y el APK de pruebas al terminar (`pm list packages com.example.pagaya` vacío) |

### Hallazgo: `date()` no valida fechas en Android

La primera versión validaba con `date(fecha) IS fecha`. Una sonda instrumentada temporal (ya eliminada) midió en el teléfono, con SQLite 3.44.5, que `date('2026-02-30')` devuelve `'2026-02-30'` y `date('2026-04-31')` devuelve `'2026-04-31'`: no los normaliza. En SQLite 3.50.4 de escritorio `date('2026-02-30')` devolvía otra fecha, y por eso la comprobación de escritorio la había dado por buena.

La regla se sustituyó por una validación aritmética explícita: mes 1–12, días por mes y año bisiesto gregoriano. La prueba ahora cubre 2026-02-30, 2026-02-31, 2026-04-31, 2026-02-29, 1900-02-29, mes 00 y 13, día 00 y formatos no ISO, además de los válidos 2024-02-29, 2000-02-29 y 2026-12-31. Si falla, el mensaje indica el valor concreto.

### Comprobación complementaria en SQLite de escritorio

Fuera del repositorio:
- Se cargaron las sentencias `CREATE TABLE/INDEX` del esquema exportado y los 85 triggers generados por la clase `SchemaConstraints` compilada en SQLite 3.50.4 (Python) con `foreign_keys=ON`.
- Se ejecutaron 75 casos equivalentes a las pruebas instrumentadas: inserciones y actualizaciones válidas e inválidas, FK, unicidad, rollback a mitad de transacción y conservación del SQL exacto en `sqlite_master`.
- **75/75 correctos**, con los triggers creados en orden normal e inverso.
- Esta comprobación detectó un solapamiento entre las reglas `tipo_origen` y `origen`, que se corrigió.
- **No detectó** el problema de `date()`, que solo apareció en el teléfono.

**Qué no demuestra:** el comportamiento en Android. Android usa otra versión de SQLite (por ejemplo 3.18 en API 26), su propio pool de conexiones y Room. Las funciones usadas (`typeof`, `GLOB`, `date`, `IS`, `RAISE`, `substr`) existen en SQLite desde mucho antes de 3.18, pero eso no sustituye a la ejecución en el dispositivo.

## Limitaciones de verificación

- Las pruebas se ejecutaron en un solo equipo, con API 35. Falta la API 26 (mínimo): su SQLite (3.18) y su framework son anteriores, y el doc 04 exige probar en la mínima y en una reciente. No hay emulador autorizado.
- El moto g84 5G es el teléfono del desarrollador. El de la usuaria es un Redmi Note 14 Pro con Android 16 (VP-01, doc 09) y las pruebas aún no se han ejecutado en él. Las pruebas no instalaron nada permanente.
- Las pruebas usan bases `test-*.db` del APK de pruebas; `pagaya.db` no se ha creado en ningún dispositivo.
- Cierre forzado del proceso y reinicio del teléfono (T-17) requieren un dispositivo; la reapertura in-process de las pruebas no los sustituye.
- `synchronous=FULL` mitiga cortes de energía; no protege ante daño físico del almacenamiento.
