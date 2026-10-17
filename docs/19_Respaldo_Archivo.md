# 19 — Respaldo en archivo: generación, validación y exportación manual

Fecha: 2026-10-16. Iteración 08, en `develop`, sobre `90b866b` (iteración 07). Sin commits: los cambios están pendientes de revisión.

**Nota histórica, 2026-10-17:** la iteración 08 quedó confirmada en `ca141b3`. Las afirmaciones de ausencia de restauración de este documento describen ese cierre. La iteración 09 implementa y prueba restauración desde archivo y recuperación anterior: ver [20](20_Restauracion_Archivo.md). No cambia el contrato ni los límites siguientes; sus mediciones de exportación no son mediciones de restauración. Drive sigue pendiente y HU-09 parcial.

**Objetivo:** guardar una copia completa de la libreta en un archivo que la usuaria elige con el selector de Android. La app solo dice «Copia guardada y comprobada» después de volver a leer ese archivo y validarlo.

**No incluye:** restauración, Google Drive, respaldo automático ni retención de copias.

> **Recuperar las cuentas desde la app todavía no es posible.** La restauración (vista previa, base temporal, copia previa y reemplazo transaccional) es la siguiente iteración. El archivo de esta iteración ya tiene el formato que usará, y el validador es el mismo. Pero ninguna recuperación se ha implementado ni probado, ni en este teléfono ni en otra instalación. No declarar HU-09 ni RF-14 completos.

## Comportamiento implementado

| Función | Estado |
| --- | --- |
| «Respaldo» en el menú «⋮» de Clientes | Hecho |
| Pantalla con «Guardar copia en archivo», última copia (fecha de verificación y, si difiere, de captura), «Hay cambios sin respaldar» / «La copia incluye todos los cambios» | Hecho |
| Estados «Preparando la copia…», «Elige dónde guardar…», «Guardando…», «Comprobando…», éxito y errores | Hecho |
| Avisos de privacidad, copia fuera del teléfono y destinos en la nube | Hecho |
| Captura en una transacción Room, archivo privado, validación, selector `ACTION_CREATE_DOCUMENT` (`application/zip`), relectura y validación del destino | Hecho |
| Reintento con la misma captura en otra ubicación tras un fallo de escritura o de verificación | Hecho |
| Restauración, Drive, retención de siete copias, WorkManager | **No implementado** (fuera de alcance; no hay botones) |
| Esquema Room | **Sin cambios** (versión 1; `app/schemas` intacto). Solo consultas nuevas |
| Dependencias | **Sin cambios** (ver «JSON sin dependencia nueva») |

## Contrato exacto del archivo (formato 1, datos 1)

Concreta el doc 05. Los puntos marcados **(decidido)** no estaban fijados allí y se decidieron en esta iteración.

### Contenedor

- Nombre sugerido: `backup_<AAAA-MM-DD>_<HHmmss>_<backup_id>.pagaya.zip`, con la hora de captura en `America/Bogota`. El proveedor puede renombrarlo (p. ej. «(1)»): el nombre no determina identidad, validez ni orden.
- ZIP con **exactamente dos entradas en la raíz y en este orden: `manifest.json` y `data.json`** (el orden: decidido). El generador usa DEFLATE. El validador acepta STORED o DEFLATED.
- Se rechazan:
  - cifrado (bits 0, 6 y 13), ZIP64 y multivolumen;
  - enlaces simbólicos y atributos de carpeta;
  - cualquier otro nombre, incluidas rutas (`../`, `/`, `a/b`) y nombres repetidos;
  - datos tras el directorio central, o directorio central fuera de su lugar.

### `manifest.json`

UTF-8 sin BOM, objeto JSON con exactamente estas 15 claves:

| Clave | Tipo | Valor |
| --- | --- | --- |
| `formato` | texto | `paga-ya-backup` |
| `version_formato` | entero | `1` |
| `version_datos` | entero | `1` |
| `version_app` | texto, 1–100 | `versionName` del paquete que generó la copia (hoy `0.1.0-dev`) |
| `backup_id` | texto UUID v4 minúsculas | Identidad de la captura; igual en reintentos de esa misma captura |
| `libreta_id` | texto UUID v4 | |
| `capturado_en` | entero ≥ 0 | Epoch UTC en ms, leído dentro de la transacción de captura |
| `zona_negocio` | texto | `America/Bogota` |
| `moneda` | texto | `COP` |
| `revision_datos` | entero ≥ 0 | Revisión capturada |
| `clientes`, `movimientos`, `revisiones` | enteros ≥ 0 | Todas las filas, incluidos archivados, anulados y todas las revisiones |
| `bytes_datos` | entero ≥ 0 | Longitud exacta de `data.json` |
| `sha256_datos` | texto, 64 hex minúsculas | SHA-256 de los bytes exactos de `data.json` sin comprimir |

### `data.json`

UTF-8 sin BOM, JSON compacto **(decidido: sin espacios)** y orden determinista. Objeto con exactamente cuatro claves:

```json
{"libreta":{"libreta_id":"…","creado_en":0,"revision_datos":0,"siguiente_orden_registro":1},
 "clientes":[…],"movimientos":[…],"revisiones":[…]}
```

**(decidido)** La libreta usa la clave `libreta_id` (como en el modelo y el manifiesto), no `id`.

Las filas usan los nombres de columna del doc 03:

| Lista | Claves exactas | Orden **(decidido)** |
| --- | --- | --- |
| `clientes` | `id`, `nombre`, `telefono`, `nota`, `creado_en`, `modificado_en`, `activo` | por `id` |
| `movimientos` | `id`, `cliente_id`, `fecha`, `orden_registro`, `tipo`, `valor_cop`, `descripcion`, `origen`, `creado_en`, `modificado_en`, `activo`, `version` | por `orden_registro` |
| `revisiones` | `id`, `movimiento_id`, `version_anterior`, `version_nueva`, `accion`, `estado_anterior_json`, `cambiado_en`, `motivo` | por `(movimiento_id, version_anterior)` |

Tipos **(decidido donde el doc 03 dice «INTEGER booleano»)**:

- **Booleanos** (`activo`): `true`/`false` de JSON, como en el snapshot de las revisiones.
- **Dinero, órdenes, versiones e instantes:** enteros JSON `-?(0|[1-9][0-9]*)` dentro de `Long`, sin fracción, exponente, NaN ni infinito.
- **Fecha:** texto `AAAA-MM-DD`.
- **Códigos:** texto (`FIADO`/`ABONO`, `MANUAL`/`PAGO_TOTAL`, `EDITAR`/`ANULAR`).
- **Opcionales** (`telefono`, `nota`, `descripcion`): texto no vacío o `null`.

**`estado_anterior_json` (decidido): texto JSON**, no un objeto anidado.
- Es la columna del modelo con su tipo (TEXT), y se exporta **byte a byte como está guardado**.
- El validador lo decodifica con `SnapshotJson` y exige que sea la forma canónica (`encode(decode(s)) == s`). Así, restaurar reproduce exactamente el texto original.

**No se exportan:**
- `nombre_busqueda`: se regenerará al restaurar;
- saldos;
- `epoca_local` ni los datos de restauración de la metadata;
- `backup_registro`;
- rutas, URI, preferencias, tokens ni secretos.

Es una prueba automática (`amountsTextsAndSnapshotsArePreservedExactly`).

Aplica a ambos JSON: cualquier clave desconocida o ausente, o una clave repetida, invalida el archivo.

## Arquitectura implementada

Paquete `data/backup/` (lógica JVM pura salvo `ContentResolverDocuments`) y `ui/backup/`:

| Archivo | Papel |
| --- | --- |
| `BackupFormat.kt` | Constantes y claves del contrato, nombre de archivo, `BackupLimits`, `BackupProblem`, `LimitedInputStream` (cuenta bytes reales) |
| `StrictJsonReader.kt` | Lector JSON por streaming y estricto (ver «Validaciones») |
| `BackupWriter.kt` | `LedgerCapture` → `data.json` (SHA-256 mientras se escribe) → ZIP con manifiesto |
| `BackupValidator.kt` | Validador reutilizable: archivo → `BackupValidation.Valid(BackupSummary)` / `Invalid(problema, detalle)`. Solo lee; no toca la base |
| `BackupService.kt` | Captura, temporales, exportación, verificación, registro de estado, limpieza, `observeStatus()` |
| `ContentResolverDocuments.kt` | `DocumentAccess` real sobre el URI del selector (`openFileDescriptor "wt"`→`"w"`, `fsync` al cerrar, `openInputStream`) |
| `ui/backup/BackupViewModel.kt`, `BackupScreen.kt` | Estados, selector `CreateDocument("application/zip")`, `SavedStateHandle` |
| `data/local/Daos.kt` | `all()` de clientes, movimientos y revisiones (solo para la captura); `LedgerMetadataDao.observe`; consultas de `backup_registro` |
| `AppContainer`, `MainActivity`, `PagaYaNavHost`, `ClientListScreen`, `strings.xml` | Cableado, limpieza al iniciar, ruta `backup`, menú y textos |

`DocumentAccess` es la única interfaz nueva. Es el límite que las pruebas sustituyen para simular proveedores que fallan al escribir o al releer (doc 04: interfaces solo donde hay un límite probado).

### Flujo

1. **Preparar** (`prepare`). En **una** `runInTransaction` lee metadata, mide conteos y bytes UTF-8 variables en SQLite y rechaza límites antes de llamar a cualquiera de los tres `all()`. Si cabe, lee todos los clientes, movimientos y revisiones y `clock.millis()`, e inserta `backup_registro` (`ARCHIVO`, `PREPARANDO`, libreta, **época**, revisión, `capturado_en`). La cota inferior de bytes es la documentada en el doc 05: no pretende predecir el tamaño exacto ni la memoria. Un rechazo previo revierte la transacción sin registrar un éxito ni alterar cuentas. Fuera de la transacción:
   - escribe `captura-<id>.data.json.tmp` y `captura-<id>.pagaya.zip.tmp` en `noBackupFilesDir/respaldo_tmp/`;
   - borra el JSON suelto;
   - deja de referenciar las listas de captura antes de validar (su liberación efectiva depende del GC);
   - valida el ZIP con el validador completo y exige que su manifiesto sea idéntico al generado.

   No se copia `pagaya.db` ni se toca el WAL. Tras la captura se puede seguir registrando: la copia representa solo esa revisión.
2. **Elegir destino:** `ACTION_CREATE_DOCUMENT` con el nombre sugerido. Si la usuaria lo cierra, `cancel` deja `FALLIDO/CANCELADO` y borra los temporales; la pantalla vuelve al estado inicial **sin mensaje de error ni de éxito**.
3. **Exportar** (`export`, no cancelable a mitad):
   - copia el ZIP al documento y lo cierra (con `fsync` si el proveedor lo admite);
   - lo **reabre** y lo copia a `verificacion-<id>.pagaya.zip.tmp` con límite real de 25 MiB;
   - lo valida y exige `summary.manifest == manifiesto preparado`: mismo `backup_id`, revisión, conteos, longitud y SHA-256. Otra copia válida de la misma libreta no basta.
   - Solo entonces: `VERIFICADO`, `verificado_en`, localizador (el URI, si mide ≤ 2048), borrar temporales y anunciar éxito.
4. **Fallos:**
   - escribir → `ESCRITURA`;
   - el proveedor no permite releer → `RELECTURA` («la copia no está confirmada»);
   - archivo releído distinto o inválido → `VERIFICACION`.

   En los tres casos el registro queda `FALLIDO` con su código y la captura privada se conserva: «Elegir otra ubicación» reintenta **la misma captura** (mismo `backup_id`). «Guardar copia en archivo» siempre hace una captura nueva y descarta la anterior (`ABANDONADO`). **Nunca se borra el archivo elegido**, aunque haya quedado incompleto: el mensaje dice que no se use como copia.

### Estado del respaldo

- Usa `backup_registro` existente; registrar no incrementa `revision_datos` (probado).
- **Última copia exportada:** el `VERIFICADO` más reciente por `verificado_en` de `ARCHIVO` para la **libreta y época actuales**. Muestra la fecha de verificación y, si difiere, «Con las cuentas del …» (fecha de captura).
- **Cambios sin respaldar:** si `MAX(revision_datos)` de esas copias verificadas ≠ revisión actual. Dentro de una época la revisión solo crece, así que una copia antigua exportada tarde no cubre movimientos posteriores (probado).
- Un registro `VERIFICADO` nunca se degrada. Un intento fallido nuevo no cambia la última copia verificada (probado).
- Hoy solo cuenta `ARCHIVO`. Cuando exista Drive habrá que decidir si una copia en Drive también cubre la revisión, con la comprobación de cuenta del doc 03.

### Serialización, cierre y recreación

- `userOperation` (Mutex con `tryLock`): una preparación o exportación a la vez. Un doble toque o una ejecución simultánea recibe `Busy`. El ViewModel también ignora toques mientras hay operación y desactiva el botón.
- `stateLock` (Mutex con espera): todo acceso a registros, captura pendiente y temporales, incluida la limpieza.
- **Hallazgo de las pruebas:** con un solo Mutex, la limpieza que lanza la pantalla al abrirse hacía que un toque inmediato recibiera `Busy` y no pasara nada. Por eso hay dos candados.
- **Limpieza** (`recoverInterrupted`), al iniciar la app en frío (`MainActivity`, solo sin `savedInstanceState`) y al abrir la pantalla:
  - los `PREPARANDO` que no son la captura en curso pasan a `FALLIDO/INTERRUMPIDO`;
  - se borran **solo** archivos de `respaldo_tmp/` cuyo nombre coincide exactamente con `(captura|verificacion)-<uuid v4>.(data.json|pagaya.zip).tmp`.
- **Recreación:** `SavedStateHandle` guarda captura, nombre y fase.
  - Si el proceso murió con el selector abierto, la pantalla espera el resultado y exporta esa captura: el servicio la reconstruye validando el temporal.
  - Si murió escribiendo o verificando, muestra «La copia anterior no terminó de guardarse» y el intento queda `INTERRUMPIDO`.
  - Nunca queda «Preparando» ni un éxito no verificado.

## Validaciones (`BackupValidator`)

En orden. Cada fallo devuelve un `BackupProblem` sin datos personales en el detalle:

1. **Tamaño real del archivo** ≤ 25 MiB.
2. **Directorio central leído a mano** (`RandomAccessFile`):
   - fin de directorio exacto y exactamente dos entradas con nombres permitidos y en orden;
   - sin cifrado, ZIP64, enlaces ni carpetas;
   - métodos 0/8;
   - primera entrada en el byte 0 y directorio central contiguo al final.
3. **Cruce estructural y lectura secuencial** (`ZipInputStream`): cada offset central debe apuntar a su encabezado local, con igual nombre, método y flags; CRC y tamaños deben coincidir o estar a cero cuando hay descriptor. El descriptor debe repetir los valores centrales. Entradas y directorio deben ser contiguos. Se rechaza ZIP64 también en campos extra locales/centrales. Tras descomprimir se comparan CRC, longitud real y **tamaño comprimido consumido** con el índice: un descriptor duplicado que encubra relleno también da `CONTAINER`, aunque JSON y SHA sean correctos. No puede haber entradas adicionales.
4. **Manifiesto** con límite real de 64 KiB:
   - se lee de forma genérica para reconocer primero `formato`, `version_formato` y `version_datos`: una versión > 1 da `UNSUPPORTED_VERSION` aunque traiga claves nuevas;
   - después, claves exactas y tipos;
   - zona y moneda (`INCOMPATIBLE`);
   - UUID, SHA hex;
   - conteos declarados ≤ límites.
5. **`data.json` por streaming**, con límite real de 50 MiB que no confía en `bytes_datos` ni en los tamaños del ZIP:
   - SHA-256 y conteo de bytes al leer;
   - claves exactas por fila;
   - tipos exactos;
   - cadenas limitadas durante la lectura por puntos de código (no por unidades UTF-16): nombre 120, teléfono 30, nota/descripción 1.000, motivo 300, UUID 36, fecha 10, códigos 16 y snapshot 8.192; los escapes se cuentan después de decodificar. Claves hasta 64; textos conocidos del manifiesto limitados por campo y los genéricos hasta 1.024;
   - ≤ 100.000 filas por lista.
6. **Coincidencias con el manifiesto:** longitud (`SIZE_MISMATCH`), SHA-256 (`CHECKSUM_MISMATCH`), conteos reales (`COUNT_MISMATCH`), `libreta_id` y `revision_datos` (`IDENTITY_MISMATCH`).
7. **Reglas de la libreta**, reutilizando el dominio:
   - **IDs:** UUID canónicos y únicos.
   - **Clientes:** `ClientRules.validate`; el valor guardado debe ser ya el normalizado (recortado, opcionales `null`).
   - **Movimientos:** cliente existente; `orden_registro` ≥ 1 y único; tipo y origen conocidos; FIADO nunca PAGO_TOTAL; `MovementRules.validateAmount`; fecha ISO existente ≥ 1900-01-01; descripción recortada 1–1000; instantes ≥ 0; versión ≥ 1.
   - **`siguiente_orden_registro`** mayor que todos los órdenes.
   - **Revisiones:** movimiento existente; `version_nueva = anterior + 1`; `(movimiento, versión)` única; acción conocida; motivo según `MovementRules.validateReason` y recortado.
   - **Cadena de revisiones:** `MovementChangeLog.build`, es decir, continuidad 1..versión−1 y cada snapshot de su versión. Además:
     - snapshot canónico;
     - datos inmutables iguales al movimiento;
     - estado anterior vigente y con campos válidos;
     - `modificado_en` del estado siguiente = `cambiado_en`;
     - ANULAR solo cambia `activo`;
     - EDITAR cambia algo y su origen sigue `originAfterCorrection`;
     - un movimiento sin revisiones está vigente y en versión 1.
   - **Saldos:** `LedgerRules.check` por cliente (ningún prefijo negativo ni desbordado, `BALANCE`); cliente archivado con saldo ≠ 0 → `ARCHIVED_WITH_DEBT`.
   - **Fechas futuras:** `FUTURE_DATE` solo si se pasa `today`. **La exportación no lo pasa**: un reloj atrasado no debe impedir guardar. La restauración sí deberá pasarlo (doc 03).

**Lector JSON:**
- Rechaza:
  - UTF-8 inválido (decodificador con `REPORT`, incluidas secuencias sobrelargas) y BOM;
  - sustitutos UTF-16 sueltos y caracteres de control sin escapar;
  - números no enteros, con exponente, con ceros a la izquierda o fuera de `Long`;
  - claves repetidas en cualquier nivel;
  - comas finales y contenido sobrante;
  - profundidad > 20.
- Nunca carga el documento completo como texto.

**JSON sin dependencia nueva.** La elección y su costo de mantenimiento están sincronizados en el doc 04: lector estricto propio acotado al respaldo, con regresiones para gramática, Unicode, duplicados y límites. Los escapes `\\u` admiten solo hexadecimales ASCII. `version_app` también cuenta puntos de código. El escritor usa búfer de caracteres además del de bytes; el contrato y los bytes JSON no cambian.

**Memoria:** el validador conserva filas parseadas, índices, snapshots y estructuras de dominio para cruzar relaciones. La captura Room conserva listas completas. Streaming evita materializar el documento entero como texto, pero no hace constante el consumo. Ver mediciones y combinaciones no comprobadas en la revisión correctiva al final de este documento.

## Pruebas y resultados

### Ejecución inicial JVM (`testDebugUnitTest`): 88/88 correctas (histórico anterior a la revisión correctiva)

| Clase | Casos | Cubre |
| --- | --- | --- |
| `BackupValidatorTest` | 26 | Libreta vacía (contenido exacto de `data.json`); libreta con activos, archivado saldado, anulado y movimiento corregido tres veces; manifiesto = bytes y SHA reales; **conservación exacta** de importes (1 y 999.999.999), textos con acentos, comillas, `\`, saltos, tabulador, emoji y snapshots; claves prohibidas ausentes; nombre de archivo en hora de Bogotá; fechas futuras; checksum, longitud, conteos, identidad; formatos futuros, moneda/zona/formato ajenos; tipos exactos en manifiesto y datos; claves duplicadas; códigos desconocidos y valores fuera de rango; referencias rotas y duplicados; cadenas de revisiones inválidas (hueco, versión, anulado sin revisión, cliente cambiado, snapshot dañado y no canónico, acción incoherente); saldo cronológico negativo; archivado con deuda; no-ZIP, vacío y truncado; datos comprimidos dañados; entradas repetidas, extra, en otro orden, rutas `../`, `/`, carpetas, enlace simbólico; cifrado y método no soportado; entrada oculta; límites de tamaño reales (incluida una «bomba» que declara 10 bytes), de manifiesto, de filas y de profundidad; JSON truncado, sobrante, BOM y UTF-8 inválido |
| `StrictJsonReaderTest` | 6 | Lectura válida, solo enteros, duplicados en cualquier nivel, documentos mal formados, Unicode inválido, profundidad |
| `BackupFixtures` | — | `LedgerBuilder` (libreta sintética con las mismas transiciones que los repositorios) y un constructor ZIP byte a byte |

Los límites se prueban con `BackupLimits` reducidos (p. ej. 64 KiB de datos), sin generar archivos enormes.

### Ejecución inicial instrumentada: 167/167 correctas (histórico anterior a la revisión correctiva)

Ejecutadas en Motorola moto g84 5G, Android 15 (API 35), el 2026-10-16, por la vía que conserva datos: `adb install -r` de ambos APK y `am instrument -w com.example.pagaya.dev.test/androidx.test.runner.AndroidJUnitRunner`. Las 18 de respaldo se repitieron tres veces más: 3 × OK.

| Clase | Casos | Cubre |
| --- | --- | --- |
| `BackupServiceTest` | 13 | Libreta vacía exportada y verificada; `revision_datos` sin cambios; temporales borrados; exportación con datos reales de repositorios (correcciones, anulación, pago completo, archivo) comparada fila a fila con Room; `ContentResolverDocuments` real con un `file://` local; **captura coherente con escrituras concurrentes** (6 capturas mientras otra corrutina registra fiados: siempre `revision − base = movimientos − base`); cambios posteriores a la captura no marcados; fallo de escritura que conserva el último éxito y reintento con la misma captura; destino no releíble (no confirmado); relectura distinta o truncada; cancelación; serialización (prepare/export durante una escritura → `Busy`); captura que sobrevive a un proceso recreado; limpieza solo de temporales propios (archivos ajenos con nombres parecidos y el destino elegido intactos); un registro verificado nunca se degrada |
| `BackupViewModelTest` | 5 | Doble toque → una captura y un selector; éxito solo tras verificar; cancelar sin error ni éxito; fallo de escritura → «Elegir otra ubicación» → éxito; proceso recreado con el selector abierto (continúa) o durante la escritura (informa interrupción) |

Primera ejecución: 5 fallos del ViewModel por el defecto de un solo candado (corregido, ver arriba) y 1 fallo intermitente de una prueba con reloj fijo (dos verificaciones con el mismo milisegundo; se avanzó el reloj en la prueba).

### Compilación y Lint

- `assembleDebug assembleDebugAndroidTest lintDebug testDebugUnitTest`: BUILD SUCCESSFUL.
- Lint: 0 errores y los mismos 7 avisos de versiones.
- Lint detectó un BOM literal en el código del lector y dos `UseKtx`; se corrigieron.

## Comprobación en el teléfono (app Dev, 2026-10-16)

Se instaló con `install -r`. Sus datos se conservan: la lista de clientes es la anterior y `pagaya.db` / `pagaya.db-wal` mantienen sus marcas de tiempo (20:40/20:43) tras las pruebas.

| Comprobación | Resultado |
| --- | --- |
| Menú «⋮» de Clientes muestra «Archivados» y «Respaldo» | Visto |
| Pantalla: «Todavía no has guardado ninguna copia en archivo.», «Hay cambios sin respaldar», botón y los tres avisos | Visto |
| «Guardar copia en archivo» abre el selector del sistema (DocumentsUI) con `backup_2026-10-16_212053_<uuid>.pagaya.zip` | Visto |
| Atrás en el selector → pantalla inicial sin error ni éxito; `no_backup/respaldo_tmp/` vacío | Visto |

No se exportó la libreta Dev: podría contener datos reales. La cancelación en Dev dejó un registro operativo `FALLIDO/CANCELADO` en `backup_registro`; no es un dato de negocio y no cambia `revision_datos`.

### Exportación real sin conexión en la instalación aislada «Paga Ya! QA» (2026-10-16)

Para sacar datos de la app sin tocar Dev se añadió el build type `qa`:
- `com.example.pagaya.qa`, «Paga Ya! QA», `initWith(debug)`;
- usuario de sistema y `pagaya.db` propios;
- ver doc 14.

Se instaló junto a Dev (que no se tocó: `pagaya.db` siguió en 20:40). Los datos sintéticos se introdujeron por la interfaz (adb + uiautomator):

| Cliente | Contenido |
| --- | --- |
| Cliente Sintetico A | Fiado $60.000, fiado $25.000 **anulado**, abono $15.000 **corregido dos veces** (valor → $20.000 y nota) |
| Cliente Sintetico B | Fiado $10.000, **pago completo**, **archivado** |
| Cliente Sintetico C | Sin movimientos |

La propietaria activó el modo avión (`airplane_mode_on=1`, «Active default network: none»).

| Comprobación | Resultado |
| --- | --- |
| «Guardar copia en archivo» → DocumentsUI en Descargas con `backup_2026-10-16_213528_<uuid>.pagaya.zip` → «GUARDAR» | «Copia guardada y comprobada», «El archivo se volvió a leer y está completo». Tarjeta: «Última copia guardada en archivo · 16 oct 2026, 9:35 p. m.» y «La copia incluye todos los cambios». Los estados intermedios no se alcanzaron a leer: el archivo mide 1.600 bytes |
| Archivo copiado al computador (`adb pull`) y revisado con Python, independiente de la app | Dos entradas DEFLATE (`manifest.json` 436 B, `data.json` 3.987 B); longitud y SHA-256 coinciden; sin BOM; manifiesto `revision_datos` 12, 3/5/3 filas. Las 3 filas de clientes, las 5 de movimientos y las 3 de revisiones son **idénticas** a una copia de la `pagaya.db` de QA (snapshots incluidos); 1 archivado y 1 anulado. Registro `ARCHIVO/VERIFICADO` con `content://com.android.providers.downloads.documents/document/…` |
| Fiado nuevo de $5.000 y volver a Respaldo | «Hay cambios sin respaldar»; la última copia (9:35) se conserva |
| Abrir el selector, cerrar el proceso de QA con `am kill` (PID desaparece), pulsar «GUARDAR» | Proceso nuevo; la pantalla continúa la misma captura y termina en «Copia guardada y comprobada» (9:37, «incluye todos los cambios», revisión 13) |
| Abrir el selector y cerrarlo con Atrás | Sin mensajes; última copia sin cambios; `FALLIDO/CANCELADO`; `respaldo_tmp` vacío; revisión sigue en 13 |

Quedan en Descargas los dos `.pagaya.zip` sintéticos y la app QA instalada, por decisión de la propietaria. La app no borra archivos de la usuaria.

**No comprobado:**
- Un destino en la nube (Drive u otro) sin conexión: implicaría usar la cuenta Google personal del teléfono y dejar un archivo en cola de subida.
- Un fallo real de proveedor al escribir o releer: está cubierto solo por las pruebas con destino simulado.
- El cierre del proceso durante la escritura o la verificación: con 1,6 KB no hay tiempo de provocarlo; está cubierto por la prueba del ViewModel.

## Limitaciones y pendientes

- **Restauración no implementada**, ni ensayada en otra instalación. Tampoco Drive, la retención de siete copias ni el respaldo automático.
- **Pruebas sin hacer:**
  - proveedores en la nube;
  - API 26 y el teléfono de la usuaria (Redmi Note 14 Pro, Android 16);
  - combinaciones máximas de memoria no cubiertas por las mediciones correctivas;
  - rendimiento en API 26 y en el teléfono final; la carga de referencia ya se midió en el Motorola (ver abajo).
- El archivo **no está cifrado** (DT-15) y SHA-256 no autentica al autor: detecta corrupción accidental, no modificaciones intencionales con el checksum recalculado.
- **Destinos que fallan:** si la escritura falla a mitad, el archivo parcial queda en el destino. La app no lo borra (no borra archivos de la usuaria) y lo advierte. Un proveedor en la nube puede aceptar la escritura y fallar al releer: se informa como no confirmada.
- **Copia guardada solo en el teléfono:** si se guarda en el almacenamiento del mismo teléfono, no protege frente a su pérdida. La pantalla lo dice; la app no puede comprobar dónde terminó físicamente el archivo.
- **Rechazos estrictos a vigilar:**
  - El validador rechaza datos que las reglas actuales no habrían producido, como un teléfono con caracteres no admitidos o una corrección sin cambios. Si alguna instalación tuviera datos anteriores a esas reglas, la exportación fallaría con «No se pudo preparar la copia».
  - En la base Dev no se comprobó, porque no se exportó; los datos de QA, generados por la app, se exportaron sin rechazos.
- La selección del lector JSON propio y su mantenimiento están documentados en el doc 04.

## Pasos manuales de comprobación

Usar una instalación con **solo datos ficticios**, por ejemplo «Paga Ya! QA» (`.\gradlew.bat assembleQa`, `adb install -r app/build/outputs/apk/qa/app-qa.apk`).

1. Clientes → «⋮» → «Respaldo». Debe decir que no hay copias y, si hay datos, «Hay cambios sin respaldar».
2. Activar el modo avión. «Guardar copia en archivo» → elegir Descargas (u otra carpeta local) → «Guardar». Debe pasar por «Guardando…» y «Comprobando…» y terminar en «Copia guardada y comprobada». La tarjeta muestra la fecha y «La copia incluye todos los cambios».
3. Registrar un fiado y volver a Respaldo: «Hay cambios sin respaldar».
4. «Guardar copia en archivo» y cerrar el selector con Atrás: sin mensajes; la última copia no cambia.
5. Opcional: abrir el `.pagaya.zip` en un computador. Contiene solo `manifest.json` y `data.json`, legibles. El SHA-256 de `data.json` coincide con `sha256_datos`.
6. Elegir una carpeta de Drive del selector sin conexión: debe informar un error, sin éxito falso, y conservar la última copia comprobada.
7. Girar el teléfono o salir y volver durante la preparación: no debe quedar «Preparando» indefinidamente.

## Revisión correctiva de iteración 08 — 2026-10-16

Se continuó el checkout local `develop`, conservando todos los cambios previos y sin crear otro checkout, restaurar archivos desde Git ni hacer commits.

### Hallazgos y cambios

- Ya estaban implementados: lectura acotada por campo de `data.json`, conteo Unicode por puntos de código, comprobación estructural del ZIP, medición SQL previa dentro de la transacción y separación de `captureAndWrite` para soltar referencias antes de validar. Se conservaron.
- Se cerró un hueco adicional del ZIP: duplicar un descriptor y ajustar el índice podía hacer coherente la estructura estática aunque DEFLATE consumiera menos bytes comprimidos. Se comparan ahora los tamaños publicados por `ZipInputStream` al llegar a EOF con los centrales. Se rechaza además ZIP64 en campos extra locales y centrales, incluso sin tamaños centinela.
- Se acotaron durante la lectura también los campos conocidos del manifiesto. Se corrigieron escapes `\\u` con dígitos Unicode no ASCII y el conteo de `version_app` para que acepte 100 puntos de código, incluidos emoji.
- Se añadió `BufferedWriter` sobre `OutputStreamWriter`: evita convertir/escribir carácter por carácter. No cambia el contrato ni el esquema Room.
- `BackupCaptureLimitsTest` añade cinco pruebas: cada límite de filas, límite de bytes y aceptación exactamente en el límite real de bytes/filas. Observa `QueryCallback` de Room con ejecutor directo: en los rechazos se ejecuta la medición SQL y **ningún** `SELECT *` de las tres listas. Comprueba igualdad de metadata y filas financieras, archivo previo idéntico, registro verificado intacto y ausencia de temporales/nuevos éxitos. Usa límites reducidos para verificar el mecanismo sin cargas peligrosas.
- JVM añade regresiones del descriptor duplicado, ZIP64 extra, escapes ASCII, campos genéricos acotados y máximos Unicode/snapshot con los escapes más costosos. El escritor sigue siendo aceptado, incluyendo STORED en las pruebas del contenedor.
- La primera suite instrumentada reveló una espera insuficiente en `MovementViewModelsTest.fullPaymentDoubleTapCreatesASinglePayment`: cuenta e historial son flujos distintos. Esperar solo saldo cero podía leer todavía el historial anterior. La prueba espera también las dos filas; no se cambió el comportamiento de negocio.

### Comandos y validación final

SDK físico: `C:\Users\lionb\AppData\Local\Packages\Claude_pzs8sxrjxfjjc\LocalCache\Local\Android\Sdk`. Se corrigió únicamente el archivo local ignorado `local.properties`. JDK, catálogo y wrapper sin cambios.

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleQa assembleDebugAndroidTest lintDebug --no-daemon --console=plain
# ADB desde <SDK físico>/platform-tools/adb.exe; -s <serial confirmado> en cada orden:
adb devices -l
adb -s <serial> shell pm list packages pagaya
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <serial> shell am instrument -w com.example.pagaya.dev.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> shell am instrument -w -e class com.example.pagaya.data.backup.BackupProfileTest -e backupProfile reference com.example.pagaya.dev.test/androidx.test.runner.AndroidJUnitRunner
```

- Build final: **BUILD SUCCESSFUL**, 122 tareas (25 ejecutadas, 97 actualizadas). Logs locales ignorados en `app/build/review-final.log`.
- JVM: **105 pruebas, 0 fallos, 0 errores** (`app/build/test-results/testDebugUnitTest/`).
- Suite instrumentada final: **OK (174 tests), 22,64 s**, sin fallos; dos auxiliares opt-in se omiten sin sus argumentos y se ejecutan explícitamente por separado. Log `app/build/review-instrumented-final.log`.
- Lint: **0 errores, 7 avisos** de versiones/target ya existentes. No se elevaron versiones para silenciarlos.
- Se confirmó un Motorola moto g84 5G, Android 15/API 35; `.dev` y `.qa` instalados y sin variante normal. Se usaron solamente bases `test-*.db` y temporales `cache/test-*` para los datasets. No se ejecutó `connectedDebugAndroidTest`, no se desinstaló Dev, no se borraron datos ni se instaló la variante normal.

### Método de medición

`BackupProfileTest` es opt-in. Genera datos sintéticos incrementalmente dentro de una base de prueba en archivo, con restricciones SQLite activas, WAL y `synchronous=FULL`. Las referencias existen, los abonos siguen a fiados suficientes, las revisiones son continuas y los snapshots corresponden a cada versión; las anulaciones son de abonos y los archivados no tienen deuda. Cada archivo generado pasa el validador completo. El servicio real también prepara, exporta, relee y valida, conservando la metadata de negocio.

Se excluye la generación del dataset de los tiempos de las fases. El desglose usa una captura transaccional con las mismas consultas, `BackupWriter.write` y `BackupValidator.validate`; mide por separado el servicio real completo, incluyendo el registro del intento. El desglose y el servicio son ejecuciones sucesivas, **no se deben sumar entre sí**.

Tiempo: `SystemClock.elapsedRealtimeNanos`. Memoria: un hilo solicita muestras cada **10 ms**, más inicio y fin de fase, con `Runtime.totalMemory() - freeMemory()` para Java y `Debug.getNativeHeapAllocatedSize()` para el heap nativo. Se solicita GC una sola vez antes de la serie, no entre fases ni dentro de ellas. Java incluye basura aún no recolectada. Se informa memoria inicial y **máximo observado durante la fase**; no se presenta la diferencia antes/después como pico. Los máximos Java/nativo pueden ocurrir en instantes distintos y no se suman como pico del proceso. No se mide PSS, GPU, toda la memoria del proceso ni un máximo absoluto; el muestreo puede perder ráfagas y pausarse durante GC/planificación. Un pase por escenario, APK debug instrumentado, sin UI Compose activa: no es un benchmark estadístico ni reproduce el consumo total de una sesión de UI.

El heap máximo de la app en este teléfono es **256 MiB**. Antes de sembrar se exige más de 512 MiB libres de sistema y una proyección heurística de trabajo menor al 60% del heap: `16 MiB + 700×clientes + 1600×movimientos + 3000×revisiones + 2×texto_adicional×(movimientos+revisiones)`. Es un guardado conservador de la prueba, **no** una cota demostrada de memoria ni un nuevo límite de producto; el pico observado puede superar esa proyección. Se revisan los resultados de cada escalón antes del siguiente; no se busca el punto de agotamiento, no se cambia el heap y no se captura `OutOfMemoryError`.

El destino de la medición del servicio es un archivo privado mediante `DocumentAccess` de prueba; se incluye cerrar/reabrir/copiar/validar, pero no un proveedor SAF real ni `fsync` explícito del adaptador de documentos. La exportación real por DocumentsUI en QA se comprueba aparte, sin atribuirle estos tiempos.

### Resultados medidos

Unidades: segundos y MiB (1 MiB = 1.048.576 bytes). Las tres primeras fases desglosan el arnés; las dos siguientes son ejecuciones posteriores del servicio real. El pico Java es el máximo de las fases muestreadas, **excluye la siembra** y no es el pico absoluto del proceso.

| Escenario (clientes / movimientos / revisiones) | JSON MiB | Captura | Escritura JSON+ZIP | Validación | `prepare` completo | Exportar+releer+validar | Pico Java observado MiB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Referencia: 1.000 / 20.000 / 5.000 | 8,84 | 0,486 | 0,959 | 5,481 | 5,738 | 4,500 | 83,74 |
| Medio: 2.000 / 40.000 / 10.000 | 17,69 | 0,893 | 1,665 | 10,024 | 10,749 | 8,474 | 131,37 |
| Textos largos: 2.000 / 40.000 / 10.000 (+450 caracteres de descripción) | 39,15 | 1,227 | 2,922 | 13,085 | 15,439 | 11,534 | 176,79 |
| Movimientos: 1.000 / 80.000 / 0 | 23,51 | 1,476 | 2,136 | 12,932 | 14,196 | 11,439 | 140,81 |
| Clientes: 100.000 / 1.000 / 1.000 | 18,06 | 1,036 | 1,536 | 12,001 | 11,901 | 10,081 | 126,56 |
| Revisiones: 1.000 / 1.000 / 45.000 (45 revisiones por movimiento) | 26,17 | 0,787 | 2,419 | 11,695 | 12,963 | 10,039 | 146,93 |

Los seis escenarios terminaron con **OK**, preparación y exportación verificadas. El mayor JSON comprobado fue **41.052.509 bytes**, 78,3% de 50 MiB. Se alcanzó el límite de clientes, el 80% del de movimientos y 45.000 revisiones con cadenas válidas. Son combinaciones distintas: no se han probado esos máximos simultáneamente.

Memoria inicial y pico observado por fase de referencia:

| Fase | Java inicial MiB | Java pico observado MiB | Nativo inicial MiB | Nativo pico observado MiB |
| --- | ---: | ---: | ---: | ---: |
| Captura | 2,16 | 21,52 | 9,86 | 9,87 |
| Escritura JSON+ZIP | 21,52 | 26,28 | 9,87 | 10,21 |
| Validación | 17,36 | 60,27 | 9,90 | 71,91 |
| `prepare` completo | 30,29 | 81,17 | 10,01 | 118,41 |
| Exportar+releer+validar | 22,47 | 83,74 | 10,05 | 129,85 |

Los valores **exactos de inicio/pico Java y nativo de cada fase de todos los escenarios**, tiempos y número de muestras están en [CSV de mediciones](mediciones/08_respaldo_2026-10-16.csv), 35 filas. Incluye cinco filas históricas `reference-unbuffered`, anteriores a añadir el búfer: escritura 17,027 s y `prepare` 21,779 s. En el pase final fueron 0,959 s y 5,738 s. Es una comparación observada de un pase por variante, sin control estadístico de JIT, cachés o temperatura; no se deduce una mejora de memoria (los picos difieren entre ejecuciones).

Reproducción: usar la misma orden del perfil `reference`, sustituyendo su valor por `medium`, `nearBytes`, `movementNear`, `clientMax` o `revisionMedium`. Los logs completos permanecen ignorados en `app/build/profile-<escenario>.log`. `reference-unbuffered` identifica el pase histórico en el CSV; no es un argumento del perfilador actual.

### Alcance y máximos no comprobados

- Los perfiles `movementMax` (1.000/100.000/5.000) y `revisionNear` (1.000/1.000/70.000) se **omitieron antes de sembrar**: proyección de 192.477.216 y 229.077.216 bytes, respectivamente, superior al guardado de 161.061.273,6 bytes (60% del heap). El runner imprime `OK (1 test)` aun cuando una precondición `Assume` omite el cuerpo: **no significa que esas cargas se midieran ni verificaran**. Sus logs carecen de fases. No son rechazos del servicio por límite de producto.
- No se provocó agotamiento de memoria ni se observó `OutOfMemoryError`. No se midieron exactamente 50 MiB descomprimidos ni 25 MiB comprimidos; los datos sintéticos son repetitivos y los ZIP medidos no llegan a 1 MiB. Los límites reales de lectura se prueban con límites reducidos en JVM/instrumentación, algo distinto de medir capacidad máxima.
- Se mantienen 25 MiB/50 MiB/64 KiB y 100.000 filas por lista como límites defensivos de formato. Estos resultados no prueban que todas las combinaciones admisibles sean sostenibles, especialmente con UI activa, menor heap o textos de peor distribución. No hay evidencia para garantizar los máximos ni para elegir un nuevo límite universal seguro; esa incertidumbre queda explícita. Requiere otra revisión de memoria por dispositivo o procesamiento con almacenamiento temporal si se necesita garantizar toda la frontera del formato.
- En la referencia, el servicio medido tarda 10,238 s entre preparar y exportar/comprobar en destino privado: cumple la meta de 30 s en este entorno. No garantiza el tiempo de un proveedor SAF/nube ni de otros teléfonos. API 26 y Redmi Note 14 Pro/Android 16 siguen pendientes.

### Exportación QA y compatibilidad comprobadas al cierre

Se actualizó QA con `install -r`, conservando su libreta sintética existente. Lista: Cliente Sintetico A con $45.000 pendientes y C sin movimientos; no se sembró ninguna base operativa. Antes de exportar figuraba la copia de las 21:37 y «La copia incluye todos los cambios».

Desde la interfaz: Respaldo → «Guardar copia en archivo» → DocumentsUI/Descargas → «GUARDAR», con nombre nuevo `backup_2026-10-16_221654_b85a184e-572c-4bcb-96d6-325b479da3e5.pagaya.zip`. QA mostró **«Copia guardada y comprobada»**, **«El archivo se volvió a leer y está completo»**, fecha 22:17 y captura 22:16, con todos los cambios incluidos. Temporales de QA vacíos después. El modo avión estaba desactivado (`0`); esta comprobación confirma destino local, no una nueva prueba de exportación sin conexión.

`BackupCompatibilityTest`, ejecutado explícitamente en Android con `-e syntheticCompatibilityCount 3`, validó dentro del teléfono:

| Captura sintética | Revisión | Clientes/movimientos/revisiones | Resultado |
| --- | ---: | --- | --- |
| `b57231f3-f9c2-4ce7-a8cc-6ec6c16351e1` (21:35, anterior) | 12 | 3/5/3 | Válido |
| `22f4422b-01d6-4671-8c0b-ac3a8bf09813` (21:37, anterior) | 13 | 3/6/3 | Válido |
| `b85a184e-572c-4bcb-96d6-325b479da3e5` (22:16, nuevo) | 13 | 3/6/3 | Válido |

Resultado: **OK (1 test), 0,103 s**. El auxiliar elimina sus copias `cache/test-legacy-*`; conserva los originales en Descargas. Como comprobación independiente del lector de la app, `unzip -p <archivo> data.json | sha256sum` en el teléfono dio **el mismo SHA-256** para las copias de revisión 13: `04c85e39feb579927a239258bbc9fcdae2d084e5c3b9c0a60bd036dcb04379f7`. La exportación nueva conserva exactamente los bytes de datos anteriores, sin cambios financieros.

La revisión automática de permisos rechazó descargar los ZIP al computador por el riesgo de transferir nombres, teléfonos y cuentas sin prueba independiente de su carácter sintético. Se completó mediante la alternativa anterior: validación y hash **dentro del teléfono**, sin descargar el contenido ni convertirlo en fixture Git. Solo las métricas sintéticas de rendimiento se guardan en el CSV.

Las huellas SHA-256 de `pagaya.db` y `pagaya.db-wal` de **Dev** coincidieron antes de instalar/ejecutar y después de todas las pruebas y la comprobación QA. No se consultaron sus filas ni se exportó su contenido. Se retiró únicamente `com.example.pagaya.dev.test`; al cierre siguen instalados `.dev` y `.qa`, con la variante normal intacta y ausente.

### Archivos y estado para revisión

Cambios de esta continuación sobre el trabajo pendiente de Claude:

- `data/backup/StrictJsonReader.kt`, `BackupValidator.kt`, `BackupWriter.kt`; en `BackupService.kt` solo se aclaró que soltar referencias no equivale a liberar memoria inmediatamente.
- JVM: `StrictJsonReaderTest.kt`, `BackupContainerTest.kt`, `BackupValidatorTest.kt`.
- Nuevos auxiliares instrumentados: `BackupCaptureLimitsTest.kt`, `BackupProfileTest.kt`, `BackupCompatibilityTest.kt`; espera corregida en `ui/clients/MovementViewModelsTest.kt`.
- Documentación: docs 04, 05, 14, 19 y `docs/mediciones/08_respaldo_2026-10-16.csv`.
- Configuración local: `local.properties` ignorado. El esquema Room y las versiones del stack no cambian.

Se conservó el resto de cambios pendientes de la iteración 08. Checkout `develop`, HEAD `90b866b`; `git status` final: **13 archivos versionados modificados y 20 archivos nuevos sin versionar**, incluyendo el trabajo anterior. `git diff --check` sin errores; `app/schemas` y catálogo de versiones sin diferencias. **Sin commit, push ni merge**. Restauración y Drive siguen sin implementación ni pruebas; esta revisión termina aquí.
