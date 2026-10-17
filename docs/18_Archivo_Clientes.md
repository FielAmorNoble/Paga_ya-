# 18 — Archivar y reactivar clientes

Fecha: 2026-10-16. Iteración 07, en `develop`, sobre el commit `2622e55` (iteración 06). Sin commits: los cambios quedan pendientes de revisión.

**Objetivo:** retirar de la lista principal los clientes que ya no se usan, sin perder sus datos ni su historial, y poder reactivarlos. Completa el criterio 6 de HU-02 y el criterio 6 de HU-06 (RF-10, RN-10, T-04).

**Esta todavía no es una versión para llevar cuentas reales.** Falta el respaldo (exportación, restauración y Drive). Usar solo datos ficticios.

## Comportamiento implementado

| Función | Estado |
| --- | --- |
| Archivar desde la cuenta (menú «⋮» → «Archivar cliente»), con confirmación | Hecho |
| Rechazo con deuda, con explicación y sin ofrecer liquidarla | Hecho |
| Volver a la lista de origen con «{Nombre} quedó archivado. Lo encuentras en «Archivados».» | Hecho |
| «Archivados» en el menú «⋮» de Clientes, con buscador, orden alfabético y estados de vacío, sin resultados, carga y error | Hecho |
| Cuenta archivada: aviso «Cliente archivado», «Reactivar cliente», sin Fiado/Abono/Pagar saldo/Corregir/Anular | Hecho |
| Consultar información (ⓘ, copiar teléfono), historial, detalle de movimientos y «Ver cambios» de un archivado | Hecho |
| Reactivar: estado activo inmediato, aviso «Cliente reactivado» y ambas listas actualizadas | Hecho |
| Esquema Room, migraciones, dependencias | **Sin cambios** (versión 1; `app/schemas` intacto) |
| Borrado físico de clientes, reactivar movimientos anulados, respaldo, Drive | **No implementado** (fuera de alcance) |

### Archivos

| Archivo | Cambio |
| --- | --- |
| `data/ClientRepository.kt` | `archive`, `reactivate` (resultado `ClientStatusResult`), `observeArchivedClients` |
| `data/local/Daos.kt` | `ClientDao.observeArchived` (mismas reglas que `observeActive` con `activo = 0`) y `MovementDao.activeBalance` |
| `ui/clients/ClientListViewModel.kt`, `ClientListScreen.kt` | Modo archivados (`ArchivedFactory`), menú «⋮», pantalla `ArchivedClientListScreen`, aviso tras archivar |
| `ui/clients/ClientDetailViewModel.kt`, `ClientDetailScreen.kt` | Diálogos de archivar (`ArchiveState`), reactivar, aviso «Cliente archivado», menú «⋮» de la cuenta |
| `ui/clients/MovementDetail*`, `MovementEdit*` | Explicación y bloqueo de corregir/anular en clientes archivados |
| `ui/PagaYaNavHost.kt` | Ruta `clients/archived`; aviso de archivo a la pantalla anterior por su `SavedStateHandle` |
| `res/values/strings.xml`, `res/drawable/ic_more_vert.xml` | Textos y el icono «⋮» como vector local |

## Reglas y decisiones

**Archivar** (`ClientRepository.archive`), en **una** transacción:
1. Lee el cliente. Si no existe → `NotFound`. Si ya está archivado → `Unchanged`: no escribe, no cambia `modificado_en` ni suma `revision_datos`.
2. Lee el **saldo vigente** de la cuenta en esa misma transacción (FIADO suma, ABONO resta, solo movimientos vigentes; la misma regla que la lista). Si no es exactamente cero → `BalanceNotZero(saldo)` y no escribe nada.
3. Incrementa `revision_datos` y actualiza `activo = 0` y `modificado_en`. Conserva ID, `creado_en`, nombre, teléfono, nota, movimientos y revisiones.

Se puede archivar un cliente sin movimientos, con la cuenta saldada (fiados y abonos que suman cero) o con todos sus movimientos anulados.

**Reactivar** (`ClientRepository.reactivate`): igual, sin condición de saldo. `activo = 1`, `modificado_en` y `revision_datos` +1 en la misma transacción. Sobre un cliente ya activo → `Unchanged` sin escrituras.

**Carreras.** Room ejecuta cada `runInTransaction` con la única conexión de escritura (`BEGIN IMMEDIATE` en WAL), así que archivar y registrar/corregir/anular se serializan. Las altas, correcciones y anulaciones ya comprobaban «cliente activo» dentro de su transacción (iteraciones 05–06); archivar comprueba el saldo dentro de la suya. Por eso solo hay dos resultados posibles: o se archiva primero y el movimiento se rechaza con `ClientArchived`, o el movimiento se guarda primero y el archivo se rechaza con `BalanceNotZero`. Nunca queda un archivado con deuda.

**Formularios abiertos antes de archivar.** El repositorio es la autoridad: un fiado, abono, pago completo, corrección o anulación sobre un cliente archivado se rechaza sin escribir nada. Las pantallas lo explican conservando lo escrito: «Este cliente está archivado y no admite movimientos nuevos. No se guardó nada.» (alta) o «Este cliente está archivado: sus movimientos no se pueden corregir ni anular. Reactívalo primero.» (corrección y anulación). Una corrección abierta sobre un archivado se bloquea desde el inicio y el diálogo de anular no se abre.

**Decisiones de esta iteración:**
- **«Archivar cliente» va en un menú «⋮» del encabezado de la cuenta**, como en el boceto de docs/06 («< Clientes · Nombre · ⋮»), lejos de Fiado y Abono. Se mantienen ⓘ y «Editar».
- **Con deuda no se abre la confirmación**, sino un aviso «Todavía tiene saldo pendiente: … primero debe quedar saldada» con un único botón «Entendido». No se ofrece registrar el pago. Si la deuda aparece entre la confirmación y el guardado, se muestra ese mismo aviso con el saldo nuevo y no se archiva.
- **Tras archivar se vuelve a la pantalla anterior** (la lista desde la que se abrió la cuenta, Clientes o Archivados) y allí se muestra la confirmación. La lista conserva búsqueda y posición porque su entrada de navegación sigue en la pila.
- **Reactivar se queda en la cuenta**, que pasa a mostrar Fiado, Abono y el menú en el acto (lee de Room); al volver, Archivados ya no lo muestra y Clientes sí.
- **Editar los datos de un cliente archivado sigue permitido** (no es una acción financiera; RF-01 no lo restringe).
- El historial vacío de un archivado dice «Este cliente no tiene fiados ni abonos.» en lugar de invitar a usar «+ Fiado», que no está disponible.
- **Doble toque:** archivar y reactivar se marcan «en curso» de forma síncrona en el ViewModel y el repositorio devuelve `Unchanged` en llamadas repetidas, así que no hay incrementos duplicados.
- **Avisos que no se cancelan:** el aviso de archivo y el de reactivación se lanzan en un `rememberCoroutineScope`. Lanzarlos dentro del `LaunchedEffect` cuya clave se limpia al consumir el aviso los cancelaba (se vio en el teléfono: la primera versión no mostró la confirmación).
- **Homónimos:** el aviso de nombre repetido sigue contando también los archivados (iteración 04).
- **Sin cambios de esquema:** la columna `activo` y el índice `(activo, nombre_busqueda, id)` ya existían.

## Pruebas ejecutadas y resultados

| Conjunto | Casos | Resultado |
| --- | --- | --- |
| Instrumentada `ClientArchiveRepositoryTest` (nueva, Room real en archivo) | 18 | **18/18** |
| Instrumentada `ClientArchiveViewModelsTest` (nueva) | 8 | **8/8** |
| Instrumentadas anteriores | 123 | **123/123** |
| JVM (sin cambios) | 54 | **54/54** |

Total: **149/149 instrumentadas** en el Motorola moto g84 5G (Android 15, API 35), 2026-10-16. La suite completa se ejecutó cuatro veces, siempre 149/149: una tras implementar, dos seguidas tras restaurar la mutación y la última con el código final (después de los dos ajustes de interfaz del recorrido manual y de la compilación desde cero), terminada a las 20:25 (hora de Colombia).

| Requisito | Prueba |
| --- | --- |
| Archivar sin movimientos | `aClientWithoutMovementsIsArchivedKeepingItsDataAndCountingOneRevision` |
| Archivar cuenta saldada con fiados y abonos | `aSettledAccountWithFiadosAndAbonosCanBeArchivedWithItsHistoryIntact` |
| Archivar con solo movimientos anulados | `anAccountWithOnlyAnnulledMovementsCanBeArchivedAndKeepsItsRevisions` |
| Rechazo con deuda sin cambios parciales | `aClientWithDebtIsNotArchivedAndNothingChanges`, `aDebtLeftByAnAnnulledAbonoAlsoBlocksArchiving`, `withDebtTheAccountExplainsItMustBeSettledFirstAndArchivesNothing` |
| Reactivar conservando ID, creación, movimientos y revisiones | `reactivatingKeepsIdCreationDataMovementsAndRevisions` |
| Llamadas repetidas y doble toque | `repeatedRequestsDoNotWriteOrCountAgain`, `concurrentDoubleTapArchivesOnceAndCountsOneRevision`, `doubleTapOnArchiveAndOnReactivateWritesOnceEach` |
| Rollback ante fallo | `aFailureWhileUpdatingTheClientRollsBackTheRevisionIncrement`, `aFailureWhileCountingTheRevisionLeavesTheClientUntouched` (triggers de prueba que fallan) |
| Separación de listas y búsquedas | `activeAndArchivedListsAndSearchesNeverMix` (tildes, mayúsculas, espacios, `%`, `_`, orden), `observedListsUpdateWhenArchivingAndReactivating`, `activeAndArchivedListsAreSeparateKeepTheirOwnSearchAndUpdateTogether` |
| Persistencia tras cerrar y reabrir | `theArchivedStateSurvivesClosingAndReopeningTheDatabase` |
| Formulario financiero abierto antes de archivar | `aMovementFormOpenedBeforeArchivingCannotSaveAndKeepsWhatWasTyped`, `aCorrectionReviewedBeforeArchivingIsRejectedOnConfirm`, `anAnnulmentOpenedBeforeArchivingIsRejectedOnConfirm`, `aDebtRegisteredWhileConfirmingPreventsArchiving` |
| Carrera deuda/archivo | `racingAFiadoAgainstArchivingNeverLeavesAnArchivedClientWithDebt` (40 rondas), `racingAnAnnulmentAgainstArchivingNeverLeavesAnArchivedClientWithDebt` (30 rondas); ambos hilos arrancan tras una barrera y al final se comprueba por SQL que ningún archivado tiene saldo distinto de cero |
| Bloqueos de un archivado | `anArchivedClientAcceptsNoNewMovementsOrPayments`, `movementsOfAnArchivedClientCannotBeCorrectedOrAnnulled`, `aCorrectionOpenedOnAnArchivedClientIsBlockedFromTheStart` |

**Comprobación de que las pruebas de carrera detectan el fallo:** mutación temporal que leía el saldo **fuera** de la transacción. Resultado: fallaron exactamente las dos pruebas de carrera (un fiado y una anulación guardados sobre un cliente que quedó archivado). Se restauró el archivo, se reconstruyó y la suite volvió a 149/149.

### Compilación y Lint

```
.\gradlew.bat assembleDebug assembleRelease assembleDebugAndroidTest testDebugUnitTest lintDebug --rerun-tasks --no-build-cache --no-daemon --console=plain
```

**BUILD SUCCESSFUL**, 132/132 tareas ejecutadas. Lint: **0 errores y 7 avisos**, los mismos de siempre (versiones más nuevas disponibles). El APK release no contiene clases de prueba (0 coincidencias de `ClientArchiveRepositoryTest`, `junit` y `androidx/test`) y no se instaló.

### Cómo se ejecutaron las pruebas instrumentales

Por la vía que conserva los datos de la app Dev:

```
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w com.example.pagaya.dev.test/androidx.test.runner.AndroidJUnitRunner
adb uninstall com.example.pagaya.dev.test
```

Las pruebas usan archivos `test-<uuid>.db` y nunca tocan `pagaya.db`.

## Recorrido manual en el teléfono

Con `com.example.pagaya.dev`, manejado por ADB (`input`, `uiautomator dump`, `screencap`). Clientes sintéticos nuevos: «Prueba Siete Archivo» (teléfono `300 000 0007`) y «Prueba Siete B». Ningún cliente existente se modificó.

| Paso | Resultado |
| --- | --- |
| Abrir la app | La lista mostró los clientes de antes (datos conservados tras `install -r`) |
| Crear «Prueba Siete Archivo» → «⋮» → «Archivar cliente» | Confirmación «¿Archivar a Prueba Siete Archivo?» con la explicación de historial y reactivación |
| «Archivar» | Vuelve a Clientes, que ya no lo muestra. **Primera versión: no apareció la confirmación** (ver «Avisos que no se cancelan»); corregido y comprobado más abajo |
| «⋮» → «Archivados», buscar `SIETE` | Aparece con «Sin movimientos» |
| Abrirlo | «Cliente archivado», explicación y «Reactivar cliente»; sin Fiado, Abono ni «⋮». ⓘ muestra teléfono, «Copiar teléfono» y «Cliente desde 16/10/2026» |
| «Reactivar cliente» | «Cliente reactivado»; aparecen Fiado, Abono y «⋮» en el acto |
| Volver | Archivados conserva la búsqueda `SIETE` y dice «No encontramos ese nombre»; sin búsqueda, «No tienes clientes archivados». En Clientes, buscar `siete` lo encuentra |
| Fiado de 10.000 → «⋮» → «Archivar cliente» | «Todavía tiene saldo pendiente — Prueba Siete Archivo debe $10.000. Solo se puede archivar un cliente sin deuda: primero debe quedar saldada.» Solo «Entendido»; no se archivó |
| Abono de 10.000 → archivar | Vuelve a Clientes con «Prueba Siete Archivo quedó archivado. Lo encuentras en «Archivados».» |
| Archivados → cuenta → tocar el fiado | Detalle completo, sin «Corregir» ni «Anular» y con «Cliente archivado: para corregir o anular este movimiento, primero reactiva al cliente.» |
| `am force-stop` y reabrir | Sigue en Archivados con «Pagado ✓» y fuera de Clientes |

Capturas sintéticas (barra de estado recortada) en `docs/capturas/18/`. No se incluye la lista principal porque muestra otros clientes del teléfono.

**Observación fuera de alcance: avisos de la cuenta tras guardar (confirmada y corregida).** Los avisos «Fiado guardado», «Abono guardado» y «Pago registrado» (iteración 05) no llegaban a verse: `ClientDetailScreen` consumía el aviso (`onSavedMovementShown` / `consumePaymentSaved`) dentro del `LaunchedEffect` que lo tenía como clave; la recomposición cambiaba la clave y cancelaba `showSnackbar`. Es el mismo defecto que tenía el aviso de reactivación.

- **Evidencia antes de corregir** (app Dev instalada, «Prueba Siete B»): tras tocar «Guardar fiado» ($1.000), «Guardar abono» ($500) y «Confirmar pago» ($500) se hizo en el teléfono un `screencap` continuo (6 capturas, ~0,4 s cada una, de 0,5 a 2,4 s después del toque). En los tres casos la cuenta ya mostraba el movimiento nuevo y ninguna captura contenía el aviso, que de lo contrario dura ~4 s. Ejemplo: `docs/capturas/18/06_abono_sin_aviso_antes.png`.
- **Corrección:** los tres snackbars se lanzan en el mismo `rememberCoroutineScope` (`snackbarScope`) que ya usaba el aviso de reactivación; el consumo del aviso queda igual. Sin otros cambios.
- **Evidencia después** (`assembleDebug`, `install -r`, mismos pasos y mismas capturas): las capturas de 1,2 a 2,4 s muestran «Fiado guardado», «Abono guardado» y «Pago registrado», y el `uiautomator dump` hecho ~5 s después del toque todavía contenía cada texto. Capturas: `07_abono_guardado_aviso.png`, `08_pago_registrado_aviso.png`.
- **Compilación y pruebas:** `assembleDebug lintDebug assembleDebugAndroidTest` BUILD SUCCESSFUL, Lint con los mismos 7 avisos de versiones y 0 errores. Instrumentadas por `am instrument` contra `.dev`: **OK (149 tests)**; luego se desinstaló solo `.dev.test`. No hay prueba automática del snackbar (no hay dependencias de prueba de UI Compose): la comprobación es manual.
- **Datos:** «Prueba Siete B» recibió dos rondas sintéticas (fiado $1.000, abono $500, pago completo $500), antes y después de corregir; termina en $0, «Pagado ✓». Ningún otro cliente se tocó.

**Avisos del detalle del movimiento (corregidos).** «Corrección guardada» y «Movimiento anulado» (iteración 06) tenían el mismo defecto en `MovementDetailScreen`: el aviso se consumía (`onSavedMessageShown` / `consumeAnnulled`) dentro del `LaunchedEffect` que lo tenía como clave y después se llamaba a `showSnackbar` en esa misma corrutina. Ahora se lanzan en un `rememberCoroutineScope` (`snackbarScope`), igual que en `ClientDetailScreen`; el consumo no cambia. Así, los avisos tras guardar de las dos pantallas siguen el mismo patrón.

- **Compilación:** `assembleDebug lintDebug` BUILD SUCCESSFUL; Lint 0 errores y los mismos 7 avisos de versiones. Instalado con `install -r` (datos conservados). No se volvieron a ejecutar las pruebas instrumentadas: el cambio solo afecta a cómo se lanza un snackbar, que ninguna prueba automática cubre.
- **Verificación en el teléfono** («Prueba Siete B»), sondeando la pantalla con `uiautomator dump` tras cada acción:

| Acción | Aviso visto | Al salir y volver |
| --- | --- | --- |
| Corregir el fiado de $1.000 a $1.500 (motivo «Prueba aviso») | «Corrección guardada» | Volver a la cuenta y reabrir el movimiento (6 s): no reaparece |
| Anular el último pago completo de $500 | «Movimiento anulado» | Volver y reabrir el movimiento anulado (6 s): no reaparece |
| Pagar saldo completo ($1.000) | «Pago registrado» | Volver a Clientes y reabrir la cuenta (6 s): no reaparece |
| Fiado de $2.000 | «Fiado guardado» | Abrir un movimiento y volver (6 s): no reaparece |
| Abono de $2.000 | «Abono guardado» | Volver a Clientes y reabrir la cuenta (6 s): no reaparece |
| Botón Inicio y volver a abrir la app, en el detalle y en la cuenta | — | Ninguno de los cinco reaparece (5 s cada vez) |

- **Datos:** «Prueba Siete B» recibió además la corrección y la anulación anteriores, un pago completo de $1.000, un fiado y un abono de $2.000; termina en $0, «Pagado ✓». Ningún otro cliente se tocó.

**Incidente de la pantalla en blanco:** sigue abierto. No reapareció en este recorrido (decenas de pantallas con `uiautomator dump`). Si vuelve, seguir el procedimiento de captura de docs/17.

### Datos del teléfono

Se conservaron todos los clientes anteriores («Dairo», «Felipe», «Sandra», los sintéticos de iteraciones previas y lo que haya agregado la usuaria). Nuevos: «Prueba Siete Archivo» (archivado, fiado y abono de $10.000, «Pagado ✓») y «Prueba Siete B» (activo, fiado y abono de $5.000, más los movimientos sintéticos de la verificación de avisos: dos rondas de fiado $1.000, abono $500 y pago completo $500, una corrección, una anulación, un pago completo de $1.000 y un fiado y un abono de $2.000; saldo $0). No se desinstaló la app Dev, no se borraron datos ni se restauró ninguna copia.

## Estado del teléfono al cerrar la iteración

- Instalada: `com.example.pagaya.dev` («Paga Ya! Dev», 0.1.0-dev), versión final de esta iteración, actualizada con `install -r` (datos conservados).
- No instalados: `com.example.pagaya` (release) ni `com.example.pagaya.dev.test`.

## Revisión de HU-02 y HU-06

| Historia | Criterio | Estado |
| --- | --- | --- |
| HU-02 | 1–5 (nombre, opcionales, homónimos, abrir cuenta nueva, editar) | Hechos en la iteración 04 |
| HU-02 | 6 Archivar con saldo cero y confirmación; accesible en «Archivados»; reactivable | **Hecho** (esta iteración) |
| HU-02 | 7 Sin borrado físico desde la interfaz | **Hecho**: no existe ninguna acción de borrar |
| HU-06 | 1 Lista alfabética de activos con saldo e identificación de homónimos | Hecho |
| HU-06 | 2 «maria» encuentra «María» | Hecho, también en Archivados |
| HU-06 | 3 «Debe», «Pagado ✓», «Sin movimientos» | Hecho con movimientos reales (iteraciones 05–06) |
| HU-06 | 4 Libreta vacía, búsqueda sin resultados, error de lectura | Hechos; el estado de error está implementado en ambas listas pero no se provocó en el teléfono |
| HU-06 | 5 Sin falso $0 mientras carga | Hecho (estado «Cargando…») |
| HU-06 | 6 «Archivados» como vista secundaria, sin mezclar | **Hecho** (esta iteración) |

**HU-02 y HU-06 quedan completas en su funcionalidad**, comprobadas en el teléfono del desarrollador. Siguen pendientes, como para todas las historias, las verificaciones transversales: accesibilidad (T-21), medición de tiempos, API 26 y el teléfono de la usuaria.

## Pendientes

- Respaldo, exportación, restauración y Drive (HU-09). La copia debe incluir los clientes archivados, y la restauración debe comprobar que ningún archivado tenga saldo distinto de cero (docs/03).
- «Respaldo» en el menú «⋮» de Clientes cuando exista.
- Accesibilidad (TalkBack, fuente al 200 %, pantallas pequeñas) en las pantallas nuevas; las etiquetas existen pero no se escucharon.
- Pruebas de interfaz Compose automáticas (no hay dependencias de prueba de UI); la interfaz se comprobó a mano.
- Reinicio del teléfono, modo avión explícito, API 26 y el teléfono de la usuaria.
- Incidente de la pantalla en blanco (abierto).

## Pasos para probar en el teléfono

1. Abrir **Paga Ya! Dev** y crear un cliente de prueba.
2. En su cuenta, tocar **⋮** (arriba a la derecha) → **Archivar cliente**. Leer la confirmación y tocar **Archivar**: vuelve a Clientes con el aviso y el cliente ya no está en la lista.
3. En Clientes, tocar **⋮** → **Archivados**. Buscarlo por nombre (sin tildes también funciona) y abrirlo: dice «Cliente archivado» y no tiene Fiado ni Abono. Se puede ver ⓘ, el historial y el detalle de cada movimiento.
4. Tocar **Reactivar cliente**: vuelve a tener Fiado y Abono. Al volver, ya no está en Archivados y sí en Clientes.
5. Registrar un **fiado** e intentar archivar: la app explica que primero debe quedar saldado y no archiva.
6. Registrar un **abono** por el mismo valor y archivar: ahora sí se archiva.
