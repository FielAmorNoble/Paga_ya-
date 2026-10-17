# 15 — Gestión básica de clientes

Fecha: 2026-10-12. Iteración 04, en `develop`. Commit local `b17c17b`. La iteración 05 amplió la cuenta con saldo, historial y movimientos: ver [16](16_Movimientos_Saldo_Historial.md). El texto «El registro de fiados y abonos todavía no está disponible» y el botón «Editar cliente» al pie de la cuenta de esta iteración ya no existen: ahora hay una acción «Editar» en el encabezado y los datos del cliente están en el diálogo del botón ⓘ (ver «Ajuste de UX» en el doc 16).

**Objetivo:** primer recorrido utilizable: abrir «Paga Ya! Dev», crear un cliente, encontrarlo, consultar y editar sus datos, y conservarlos al cerrar y volver a abrir.

**Alcance pendiente:** (actualizado en la iteración 07: archivar, reactivar y «Archivados» ya existen; ver [18](18_Archivo_Clientes.md))
- **HU-02 no está completa:** faltan archivar y reactivar (criterio 6).
- **HU-06 no está completa:** falta la vista «Archivados».
- No hay movimientos, saldos registrables, historial, respaldo ni Drive.

## Base guardada antes de esta iteración

Commit local `185c917` en `develop`: «build: arranque Android, base Room v1 y variantes dev/release». Contiene las iteraciones 01–03 y la nota VP-01.

- 43 archivos revisados: código, pruebas, esquema Room, wrapper de Gradle y documentación.
- Excluidos por `.gitignore`: `local.properties`, `.gradle/`, `app/build/`, bases de datos, APK y credenciales. Una búsqueda de patrones sensibles en los archivos incluidos no dio coincidencias.
- `gradlew` quedó con modo `100755`.

No se hizo push ni merge. Los cambios de esta iteración quedan sin commit, pendientes de revisión.

## Implementado

### Capa de datos y reglas

| Archivo | Contenido |
| --- | --- |
| `domain/ClientRules.kt` | Validación y normalización, en Kotlin puro |
| `data/ClientRepository.kt` | Único punto de escritura de clientes |
| `data/local/ClientWithAccount.kt` | Cliente con estado de cuenta derivado por consulta |
| `data/local/Daos.kt` | Consultas nuevas: lista y detalle observables (`Flow`), conteo de homónimos e incremento atómico de `revision_datos`. Sin cambios de esquema |

**Validación** (`ClientRules`), aplicada en la pantalla y de nuevo en el repositorio:

| Campo | Regla |
| --- | --- |
| Nombre | Obligatorio; se recortan los extremos; 1–120 caracteres |
| Teléfono | Opcional; vacío se guarda como NULL; hasta 30 caracteres. Solo dígitos, espacios y `+ - ( ) .`, con al menos un dígito |
| Nota | Opcional; vacía se guarda como NULL; hasta 1000 caracteres |

- Las longitudes se cuentan en caracteres Unicode, igual que la base.
- La regla de caracteres del teléfono es una decisión de esta iteración: el doc 03 solo dice «admite +, espacios y separadores».

**Búsqueda:**
- `nombre_busqueda` se obtiene pasando a minúsculas (`Locale.ROOT`), descomponiendo en NFD, quitando las marcas diacríticas y colapsando los espacios. La consulta se normaliza igual, así que «maria», «MARÍA» y «pérez» encuentran «María Pérez», y «nandu» encuentra «Ñandú».
- La consulta usa `LIKE '%' || ? || '%' ESCAPE '\'`, con `%`, `_` y `\` escapados: se buscan como texto literal.
- El orden es alfabético por nombre normalizado, con el ID como desempate estable.

**Repositorio** (`ClientRepository`):

- **Crear:**
  - El ID del cliente es el ID de solicitud del formulario, generado una sola vez y conservado en `SavedStateHandle`.
  - Un reintento con los mismos datos devuelve el cliente existente, sin duplicarlo ni sumar otra revisión. El mismo ID con datos distintos devuelve `Conflict` y no sobrescribe nada.
  - En la misma transacción incrementa `revision_datos` e inserta la fila.
- **Editar:**
  - Cambia nombre, teléfono, nota, `nombre_busqueda` y `modificado_en`.
  - Conserva el ID, `creado_en` y el estado activo.
  - Guardar sin cambios no escribe ni suma revisión.
  - El incremento de revisión va en la misma transacción.
- **Homónimos:** se cuentan los clientes (activos o archivados) con el mismo nombre normalizado. Es un aviso, nunca un bloqueo.
- **Hilos:**
  - Las escrituras usan `runInTransaction` sobre un dispatcher de E/S inyectable.
  - Las lecturas son `Flow` de Room, fuera del hilo principal.
  - Se reutiliza la única instancia Room de `AppContainer`, sin otra base ni framework de inyección.

**Estado de cuenta:**
- La lista y el detalle obtienen `movimientos_vigentes` y `saldo` con una consulta agregada sobre los movimientos vigentes: FIADO suma y ABONO resta.
- Con cero movimientos vigentes se muestra «Sin movimientos». Con deuda, «Debe: $X». Con historia y saldo cero, «Pagado ✓». Un saldo negativo, imposible en V1, aparece como «Revisar cuenta».
- No se persiste ningún saldo. Hoy no existe forma de registrar movimientos desde la app, así que todos los clientes muestran «Sin movimientos».

### Interfaz

Compose con ViewModel y `StateFlow`. Navigation Compose usa rutas por ID: `clients`, `clients/new`, `clients/{id}`, `clients/{id}/edit`.

**Clientes:**
- Muestra el nombre de la app, el buscador (conservado al navegar) y la lista.
- Cada fila es un único elemento accesible con el nombre, el teléfono si existe y el estado de cuenta.
- El botón «Nuevo cliente» mide 56 dp.
- Estados: cargando, vacío («Aquí verás las cuentas de tus clientes» con «Crear primer cliente»), búsqueda sin resultados («No encontramos ese nombre» con «Borrar búsqueda») y error con «Reintentar».

**Formulario de crear y editar:**
- Campos: nombre, teléfono (teclado telefónico) y nota multilínea.
- Los errores aparecen en cada campo y lo escrito se conserva.
- El aviso de homónimo aparece mientras se escribe y no bloquea.
- Al guardar, el botón muestra «Guardando…», se desactiva y queda protegido contra el doble toque.
- Un fallo de guardado muestra un mensaje sin perder lo escrito.
- Salir con cambios sin guardar pide confirmación («¿Descartar los cambios?»), tanto con el botón Atrás del sistema como con «Volver».
- Al crear se abre la cuenta del cliente nuevo (HU-02.4); al editar se vuelve al detalle.

**Detalle del cliente:**
- Muestra el estado de la cuenta, el teléfono, la nota, «Cliente desde dd/mm/aaaa» (zona America/Bogota) y el botón «Editar cliente».
- Dice expresamente: «El registro de fiados y abonos todavía no está disponible en esta versión.»
- No muestra botones de fiado, abono ni respaldo.

**Otros:**
- Todos los textos están en `strings.xml`; los límites de longitud usan `plurals`.
- El icono «Volver» es un vector local, sin dependencias de iconos.

### Dependencias añadidas

| Dependencia | Versión | Motivo |
| --- | --- | --- |
| `androidx.navigation:navigation-compose` | 2.9.8 | Navegación entre las tres pantallas (doc 04: «Navigation Compose estable»). Último parche de la línea 2.9, compatible con el lifecycle ya resuelto |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.9.4 | `viewModel()` y fábricas con `SavedStateHandle` |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.9.4 | `collectAsStateWithLifecycle` |
| `junit` (`testImplementation`) | 4.13.2 | Pruebas JVM de reglas (ya estaba en el catálogo) |

- Lifecycle 2.9.4 ya se resolvía de forma transitiva; declararlo no cambió ninguna versión.
- No hacía falta `room-ktx`: Room 2.8.5 trae `Flow` y transacciones en `room-runtime`.
- Ninguna dependencia existente cambió.

## Pruebas

| Conjunto | Casos | Resultado |
| --- | --- | --- |
| `ClientRulesTest` (JVM) | 8 | **8/8** |
| `ClientRepositoryTest` (instrumentada, Room real en archivo) | 12 | **12/12** |
| Pruebas de Room existentes (`DatabaseSetupTest`, `ConstraintsTest`, `TransactionTest`) | 21 | **21/21** |

- **JVM:** trim y nombre obligatorio; longitudes en caracteres Unicode (incluidos emojis); opcionales vacíos como NULL; caracteres y longitud del teléfono; longitud de la nota; normalización (acentos, mayúsculas, espacios, «İ», «ñ»); escape de comodines.
- **Instrumentadas:**
  - Crear y recuperar.
  - Rechazo sin escritura ni revisión.
  - Búsqueda con acentos, mayúsculas, homónimos y orden alfabético.
  - `%`, `_` y `\` literales.
  - Conteo de homónimos.
  - Edición que conserva ID y creación.
  - Revisión +1 por cambio y +0 al guardar sin cambios.
  - Rollback de la revisión si falla la inserción.
  - Reintento con el mismo ID sin duplicar, y conflicto con datos distintos.
  - Persistencia tras cerrar y reabrir el archivo.
  - Saldo derivado que excluye anulados.
  - Lista observable que se actualiza.

Ejecución final (2026-10-12T21:01:07, Motorola moto g84 5G, Android 15, teléfono del desarrollador): `connectedDebugAndroidTest` → **33/33**, 0 fallos. El runner instaló y desinstaló solo `com.example.pagaya.dev` y `com.example.pagaya.dev.test`.

### Comandos

```
.\gradlew.bat assembleDebug assembleRelease assembleDebugAndroidTest testDebugUnitTest lintDebug --rerun-tasks --no-build-cache --no-daemon --console=plain
.\gradlew.bat connectedDebugAndroidTest --info
```

- **Compilación:** BUILD SUCCESSFUL, 132/132 tareas ejecutadas.
- **Lint:** 0 errores y 7 avisos, todos de versiones más nuevas disponibles. Son los 4 anteriores más lifecycle 2.11.0 (×2) y navigation 2.10.2, elegidos deliberadamente. Los 3 avisos `PluralsCandidate` iniciales se corrigieron con `plurals`.
- **APK release:** `com.example.pagaya`, «Paga Ya!», sin clases de prueba (0 coincidencias de `ClientRepositoryTest`, `ConstraintsTest`, JUnit y `androidx.test`).

### Recorrido manual en el teléfono

Solo con `com.example.pagaya.dev` y datos ficticios, manejando la app por ADB (`input`, `uiautomator dump`, `screencap`). El teléfono lo desbloqueó el propietario; no se introdujo ninguna credencial.

| Paso | Resultado |
| --- | --- |
| Abrir con la libreta vacía | Estado vacío y «Crear primer cliente» |
| Guardar con el nombre vacío | «Escribe el nombre del cliente»; no se guarda |
| Crear «Maria Prueba», teléfono «300 000 0001», nota «Cliente ficticio de prueba» | Abre el detalle: «Sin movimientos», datos y «Cliente desde 12/10/2026» |
| Editar el teléfono y pulsar Atrás | Diálogo «¿Descartar los cambios?»; con «Seguir editando» se conserva lo escrito |
| Guardar la edición | El detalle muestra el teléfono nuevo y la misma fecha de creación |
| Crear «MARIA PRUEBA» | Aviso de homónimo; se guarda igualmente |
| Crear «Ana Ficticia» y volver a la lista | Orden alfabético; los homónimos se distinguen por el teléfono; todos con «Sin movimientos» |
| Buscar «maria» y luego «mariazzz» | Aparecen los dos homónimos; después «No encontramos ese nombre» |
| `am force-stop com.example.pagaya.dev` y reabrir | El proceso cambió (pid 4017 → 5463); siguen los 3 clientes con la edición |
| `run-as com.example.pagaya.dev ls databases/` | `pagaya.db`, `-wal` y `-shm` en el directorio privado de la app de desarrollo |

**Defecto encontrado y corregido:** en el dispositivo, el encabezado de las pantallas secundarias quedaba debajo de la barra de estado (edge-to-edge). Se añadió `statusBarsPadding()`. Las pruebas automatizadas no lo detectaban.

Las capturas, con la barra de estado recortada, están en `docs/capturas/15/`. Los clientes del recorrido se borraron al desinstalar las pruebas finales; la app que quedó instalada está vacía.

**No probado:**
- Reinicio del teléfono: no se reinició sin consultar.
- TalkBack, fuente al 200 % y pantallas pequeñas (T-21).
- Escritura con tildes desde el teclado: `adb input` no las admite; la búsqueda con acentos sí está cubierta por las pruebas instrumentadas.
- Teléfono de la usuaria (Redmi Note 14 Pro).
- API 26.

## Estado del teléfono al cerrar la iteración

- Instalada: `com.example.pagaya.dev` («Paga Ya! Dev», 0.1.0-dev), versión final de esta iteración, abierta y sin datos.
- No instalados: `com.example.pagaya` (release) ni `com.example.pagaya.dev.test`.

## Pendientes

- **HU-02:** archivar y reactivar (criterio 6). **HU-06:** vista «Archivados» (criterio 6), y «Debe» y «Pagado ✓» con movimientos reales (criterio 3).
- Comprobar en el dispositivo la recreación del proceso con el formulario a medias: lo escrito está en `SavedStateHandle`, pero no se forzó la muerte del proceso con la app en segundo plano.
- Accesibilidad (T-21) y medición de tiempos con la usuaria.
- Siguiente fase funcional: movimientos (fase 3), sin cambiar este recorrido.
