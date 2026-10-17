# 17 — Detalle, corrección y anulación de movimientos

Fecha: 2026-10-16. Iteración 06, en `develop`, con una revisión correctiva (sección propia más abajo). Sin commits en esta iteración: los cambios quedan pendientes de revisión sobre el commit `5148beb`.

**Objetivo:** que la usuaria pueda tocar un movimiento, consultar todos sus datos y corregir un error sin perder lo que se había registrado originalmente. Implementa HU-08 (y la parte de HU-07 sobre «Editado» y «Anulado»).

**Esta no es una versión para llevar cuentas reales.** Faltan archivar/reactivar clientes y el respaldo, y no se puede reactivar un movimiento anulado. Usar solo datos ficticios.

## Revisión previa de la implementación financiera

Antes de ampliar el flujo se revisó el repositorio, las reglas y las pruebas de la iteración 05 (transacción de alta, idempotencia, orden de registro, prefijos no negativos, `addExact`, pago completo y rollback).

- **No se encontró ningún defecto que comprometiera la integridad.** Evidencia: la revisión del código frente a docs/03, las 71 pruebas instrumentadas de la iteración 05 (siguen aprobadas, ahora entre las 123 actuales) y las pruebas nuevas, que ejercitan de nuevo las mismas reglas de saldo desde el lado de la corrección.
- **Brechas de producto detectadas y cerradas** (no son defectos de integridad, sino piezas que la corrección necesita):
  - El historial solo leía movimientos vigentes: no podía mostrar los anulados. Ahora los muestra en su lugar, sin saldo resultante.
  - La consulta agregada del estado de cuenta ignoraba los movimientos anulados, así que una cuenta con solo anulados decía «Sin movimientos». docs/06 pide «Sin movimientos vigentes»; ahora lo dice (columna calculada nueva `movimientos_total`, sin cambio de esquema).
  - Hasta hoy las filas del historial no eran interactivas.

## Alcance implementado

| Función | Estado |
| --- | --- |
| Detalle de un movimiento al tocar su fila | Hecho |
| Corregir valor, fecha y descripción con motivo obligatorio y confirmación | Hecho |
| Anular con motivo obligatorio y confirmación (sin borrado físico) | Hecho |
| Movimientos anulados visibles en el historial, identificados, sin saldo resultante | Hecho |
| Marca «Editado» y «Ver cambios» (valores anteriores y posteriores, motivo, fecha) | Hecho |
| Validación de toda la secuencia cronológica, control de versión, idempotencia, rollback | Hecho |
| «Sin movimientos vigentes» para cuentas con solo movimientos anulados | Hecho |
| Esquema Room, migraciones, dependencias | **Sin cambios** (versión 1) |
| Reactivar un movimiento anulado | **No implementado** (fuera de alcance) |

### Archivos

| Archivo | Contenido |
| --- | --- |
| `domain/MovementSnapshot.kt` | Instantánea completa de un movimiento, JSON del snapshot (escritor y lector propios, estrictos), diferencias entre estados y bitácora de cambios |
| `domain/LedgerRules.kt` | `rejectionForChange` y `rejectionForRemoval`; el historial incluye anulados sin saldo |
| `domain/MovementRules.kt` | Motivo (1–300), origen tras corregir el importe |
| `data/MovementRepository.kt` | `correct`, `annul`, `observeChanges` y demás lecturas de detalle |
| `data/local/Daos.kt`, `ClientWithAccount.kt` | Consultas de historial completo, de un movimiento y de revisiones; columna `movimientos_total` |
| `ui/clients/MovementDetail*`, `MovementEdit*`, `MovementChanges*`, `MovementUiText.kt` | Pantallas, ViewModels y textos compartidos |
| `ui/clients/ClientDetailScreen.kt`, `ui/PagaYaNavHost.kt`, `res/values/strings.xml` | Filas tocables, estado «Anulado», rutas `movement/{id}`, `/edit` y `/changes` |

## Detalle del movimiento

Al tocar una fila de la cuenta se abre una pantalla con el estilo actual:

- **Tarjeta:** cliente, tipo («Fiado», «Abono» o «Pago completo») e importe con signo; «Anulado · no afecta el saldo» y «Editado» cuando corresponde.
- **Datos:** fecha completa, **descripción completa** (con desplazamiento si es larga), estado «Vigente» o «Anulado», fecha y hora de creación y de la última modificación (hora de Bogotá).
- **Acciones:** «Corregir» y «Anular» en la barra inferior, solo con movimientos vigentes (de clientes activos); «Ver cambios» en el cuerpo, solo si ya hay revisiones.
- «Anular» va con color de error y fuera de la acción principal, como pide docs/06. No hay pulsación prolongada ni gestos ocultos.

## Corrección

Solo cambian **valor, fecha y descripción**. Cliente, tipo, ID, orden de registro y fecha de creación no se tocan (el esquema ya lo impone con triggers y el repositorio no los escribe).

1. El formulario arranca con los valores actuales. El valor se muestra con agrupación (`60.000`), que el lector estricto de importes acepta.
2. «Revisar cambios» valida el importe, la fecha (1900 a hoy) y la nota con las mismas reglas del alta. Pide un **motivo** obligatorio (1–300 caracteres tras recortar).
3. **Sin cambios:** muestra «No hay cambios que guardar» y no abre confirmación. Ni el formulario ni el repositorio crean revisión ni suman contadores; en ese caso ni siquiera se exige el motivo.
4. **Con cambios:** se congela una **solicitud inmutable** (`CorrectionRequest`: importe y nota tal como se escribieron, fecha, motivo y la versión que la usuaria vio). Mientras se evalúa (el botón dice «Revisando…»), el formulario no admite cambios ni una segunda revisión. La evaluación comprueba de antemano si la secuencia seguiría siendo válida: si no, lo avisa ya, sin mostrar un saldo imposible; si sí, muestra «Confirmar corrección» con los datos que cambian (`Valor: $60.000 → $50.000`, `Nota: «…» → «…»`), el motivo y el **nuevo saldo** de la cuenta. Con la vista previa abierta el formulario tampoco cambia.
5. «Guardar corrección» llama a `MovementRepository.correct` con **esa solicitud congelada**, nunca con los campos actuales del formulario. Si una evaluación se cancela o se reemplaza, su resultado tardío se descarta. La evaluación anticipada es solo informativa: el repositorio decide otra vez dentro de la transacción.
6. Si cambia el importe de un ABONO con origen `PAGO_TOTAL`, el origen pasa a `MANUAL`; la revisión conserva el origen y el valor anteriores. Cambiar solo la fecha o la nota no altera el origen (docs/03).
7. Un movimiento cuyo tipo o cliente se equivocó se corrige anulándolo y registrando uno nuevo, como pide docs/01 (RN-09).

## Anulación

- Se abre un diálogo desde «Anular»: «Vas a anular el abono de $40.000 del 16 oct 2026 de {cliente}», el saldo que quedaría (solo si no es negativo), la aclaración «El movimiento no se borra: queda en el historial como anulado y deja de contar en el saldo» y el **motivo obligatorio**.
- El movimiento **no se borra**: queda con `activo = 0`. Su importe, fecha y nota se conservan como referencia. Deja de contar en el saldo, en los prefijos y en las consultas agregadas.
- En el historial aparece en su lugar como **«Anulado · Abono»** (sin ✓), importe tachado pero legible, texto «Anulado · no afecta el saldo», sin relleno de color y **sin «Saldo después»**: no sugiere que participe en el cálculo. Su descripción hablada dice «Abono anulado de 40.000 pesos… No afecta el saldo».
- Anular un abono aumenta la deuda; anular un fiado la reduce. Si el saldo resultante pasara por debajo de cero en cualquier punto, se rechaza.
- No hay reactivación: la única salida a una anulación accidental es registrar la operación de nuevo con una nota de referencia (HU-08.7).

## Integridad y trazabilidad

`MovementRepository.correct` y `annul` ejecutan, en **una** transacción:

1. **Idempotencia:** si ya existe una revisión con el ID de solicitud, es el mismo intento (ver abajo).
2. Lee el movimiento; rechaza si no existe (`NotFound`), si ya está anulado (`AlreadyAnnulled`), si su versión no es la esperada (`VersionConflict`) o si su cliente está archivado (`ClientArchived`).
3. Construye el estado candidato; si corregir no cambia nada, devuelve `Unchanged` sin escribir. Valida el motivo.
4. Valida **toda la secuencia vigente** del cliente con el candidato (`rejectionForChange` / `rejectionForRemoval`): prefijos no negativos, sin desbordamiento, sin ajustes automáticos.
5. Inserta la **revisión** con el snapshot **completo** anterior (los doce campos en JSON con claves lógicas en español), el motivo recortado, la versión anterior y la nueva (+1) y la acción `EDITAR` o `ANULAR`.
6. Actualiza el movimiento (campos corregidos o `activo = 0`, `modificado_en` y `version`) y suma 1 a `revision_datos`.

Un rechazo no escribe nada. Cualquier excepción deshace todo: no queda una revisión sin su cambio ni un cambio sin su revisión. **No se consume `orden_registro`**: editar y anular conservan el orden original. La continuidad de versiones (`version` = revisiones + 1, docs/13) se comprueba al leer «Ver cambios».

### Qué se rechaza y cómo se explica

Ejemplo del modelo: fiado de 60.000 y abono de 40.000.

| Cambio | Resultado |
| --- | --- |
| Reducir el fiado a 30.000 | Rechazado: «Este cambio dejaría sin deuda suficiente al abono de $40.000 del 16 oct 2026. Revisa ese abono antes de cambiar este movimiento.» |
| Reducirlo a 40.000 | Válido (la cuenta queda en 0) |
| Anular el fiado | Rechazado: «No se puede anular: el abono de $40.000 del 16 oct 2026 quedaría sin deuda que cubrir. Revisa ese abono primero.» |
| Subir el abono por encima de lo que se debía sin él | «Este abono no puede superar lo que debe la cuenta sin él: $X» |
| Mover el abono antes de su deuda | «En esa fecha aún no había deuda suficiente…» |
| Anular el abono | Válido: la deuda vuelve a 60.000 |

Un caso se detecta aunque el saldo final sea positivo: con fiado 100k (1 oct), abono 100k (4 oct) y fiado 50k (5 oct), reducir el primer fiado a 90k dejaría 40k al final pero −10k el 4 de octubre, así que se rechaza señalando ese abono.

### Versiones, doble toque y reintentos

- **Versión esperada:** el formulario guarda en `SavedStateHandle` la versión que la usuaria vio al abrirlo (y el diálogo de anulación, la del momento en que se abre). Si otra pantalla cambió el movimiento, el guardado devuelve `VersionConflict`: «Este movimiento cambió desde que lo abriste. Vuelve al detalle, revísalo y corrige de nuevo.» No se sobrescribe nada (HU-08.8). Si la pantalla se recrea con una versión vieja, lo avisa al cargar.
- **ID de solicitud = ID de la revisión.** Se genera una vez por formulario o por diálogo y se conserva en los reintentos. Un reintento con el mismo ID, movimiento, acción, versión anterior y motivo, **y con el contenido del intento original**, devuelve el mismo resultado sin crear otra revisión ni sumar `revision_datos`. Ese contenido se compara con el **estado que el intento produjo**: el estado actual si nadie lo cambió después, o el snapshot que guardó la revisión siguiente si hubo cambios posteriores. Nunca se compara con un estado posterior que ya incluya otras correcciones. El mismo ID con otro contenido (otro importe, fecha, descripción, motivo, acción o versión) es `Conflict`; si falta la revisión siguiente o su snapshot no corresponde, es una inconsistencia real del historial y se informa con una excepción, no se resuelve a favor ni en contra.
- **Doble toque:** los ViewModels marcan «guardando» de forma síncrona y deshabilitan el botón; además el repositorio es idempotente aunque cuatro llamadas lleguen a la vez, y de dos formularios abiertos en la misma versión solo uno gana. La confirmación solo se admite con una vista previa abierta y sin otro guardado en curso.
- **Lecturas consistentes:** «Ver cambios» y el detalle leen el movimiento y sus revisiones en **una misma consulta transaccional observable** (relación de Room dentro de `@Transaction`), no con dos flujos combinados. Cada emisión corresponde a un único instante; se conservan las validaciones de continuidad de versiones y de snapshot, que fallan con un error visible si hay una inconsistencia real.

### «Ver cambios»

Una tarjeta por revisión, del cambio más reciente al más antiguo: «Corrección» o «Anulación», fecha y hora, las diferencias en lenguaje cotidiano y el motivo; al final, «Registro original» con valor, fecha y nota tal como se registró. Las diferencias son `Valor: $60.000 → $50.000`, `Fecha: 3 oct 2026 → 4 oct 2026`, `Nota: «uno» → «dos»` (o «sin nota»), `Tipo: Pago completo → Abono` y `Estado: Vigente → Anulado`. **Nunca se muestra JSON.** Si el snapshot está dañado o las versiones no son continuas, se informa («No pudimos leer los cambios de este movimiento») en lugar de mostrar un historial engañoso.

## Decisiones

- **Sin cambios de esquema ni de dependencias.** `movimiento_revision`, sus triggers y los índices ya cubrían la necesidad. El JSON del snapshot se escribe y lee con código propio (sin `kotlinx.serialization`, aún no adoptada), en Kotlin puro y con pruebas JVM.
- **ID de revisión = ID de solicitud** en lugar de una columna nueva: el esquema ya garantiza su unicidad y permite reconocer un reintento sin migración.
- **Guardar sin cambios no exige motivo** y no escribe nada.
- **Corregir en un cliente archivado se rechaza,** para no romper el invariante «un cliente archivado no tiene saldo positivo» (docs/03). Hoy no hay archivado, pero el repositorio ya lo protege.
- **Dos comprobaciones, una autoridad:** la pantalla anticipa el rechazo solo para dar mejor experiencia; el repositorio vuelve a validar dentro de la transacción.
- **«Editado» en la fila** (versión mayor que 1) cumple HU-07.4 sin añadir otro control; el acceso a «Ver cambios» está en el detalle.
- **La anulación muestra el saldo resultante solo si es válido.** Si fuera negativo, la acción se rechazará al confirmar con la explicación del abono afectado.
- **Rutas por ID:** `movement/{id}`, `movement/{id}/edit` y `movement/{id}/changes`. El aviso «Corrección guardada» vuelve al detalle por el `SavedStateHandle` de su entrada de navegación; «Movimiento anulado» lo emite el ViewModel.
- Alcance diferido: reactivar, mostrar «Saldo después» en el detalle, agrupar el historial por día (HU-07.1) y la corrección en lote.

## Pruebas y resultados

| Conjunto | Casos | Resultado |
| --- | --- | --- |
| JVM `CorrectionRulesTest` (nuevo) | 15 | **15/15** |
| JVM `SnapshotJsonTest` (nuevo) | 9 | **9/9** |
| JVM anteriores (`ClientRulesTest` 8, `MovementRulesTest` 10, `LedgerRulesTest` 12) | 30 | **30/30** |
| Instrumentada `MovementCorrectionRepositoryTest` (nuevo, Room real en archivo) | 36 | **36/36** |
| Instrumentada `MovementCorrectionViewModelsTest` (nuevo) | 16 | **16/16** |
| Instrumentadas anteriores (`MovementRepositoryTest` 30, `MovementViewModelsTest` 8, `ClientRepositoryTest` 12, `ConstraintsTest` 12, `DatabaseSetupTest` 6, `TransactionTest` 3) | 71 | **71/71** |

Total: **54/54 JVM** y **123/123 instrumentadas**, en el Motorola moto g84 5G (Android 15), 2026-10-16. La suite instrumentada completa se ejecutó tres veces seguidas con la versión final: 123/123 las tres (las pruebas concurrentes nuevas no mostraron inestabilidad).

Cobertura de lo pedido:

| Requisito | Dónde se comprueba |
| --- | --- |
| Corregir importe, fecha y descripción; lo inmutable no cambia | `correctingAmountDateAndDescriptionUpdatesOnlyThoseFieldsAndKeepsTheOriginal` |
| Recalcular saldo y posiciones del historial | `correctingTheDateRecalculatesEveryPositionOfTheHistory`, `correctingTheAmountMovesTheBalanceAndTheRowsAfterIt`, `correctingTheDateReordersTheHistoryByDateAndRegistrationOrder` |
| Rechazar saldo intermedio negativo | `correctionsThatLeaveAnIntermediateNegativeBalanceAreRejectedWithoutChanges`, `reducingTheFiadoOfTheDocumentedExample…`, `anAbonoCannotBeCorrectedAbove…`, `CorrectionRulesTest` |
| Anular abono aumenta la deuda; anular fiado permitido la reduce | `annullingAnAbonoIncreasesTheDebt…`, `annullingAFiadoThatNoAbonoDependsOnReducesTheDebt` |
| Rechazar anular un fiado que deja abonos sin cubrir | `annullingAFiadoThatLeavesAbonosUncoveredIsRejectedWithoutChanges` |
| Snapshot completo y versiones continuas | `theRevisionKeepsTheCompletePreviousSnapshotAndTheReason`, `versionsFormAContinuousSequenceAndEveryRevisionKeepsItsPreviousState`, `SnapshotJsonTest` |
| Conflicto de versión | `aStaleVersionIsRejectedAndNeverOverwritesTheNewerChange`, `twoDifferentFormsOpenedOnTheSameVersionLeaveExactlyOneWinner`, `aMovementChangedFromAnotherScreenIsNotOverwritten` |
| Doble toque y reintento | `retryingTheSameRequest…`, `theSameRequestWithDifferentContentIsAConflict…`, `aRetryStillSucceedsAfterLaterChanges…`, `doubleTapRunningConcurrentlyCreatesOneRevision`, `annulRetryIsIdempotent`, `doubleTapOnConfirmCreatesASingleRevision`, `annullingRequiresAReasonAndDoubleTap…` |
| Rollback sin cambios parciales | `aFailureWhileUpdatingTheDataRevisionRollsBack…`, `aFailureWhileUpdatingTheMovementRollsBack…`, `aFailureWhileInsertingTheRevisionLeaves…` (triggers de prueba que fallan en cada paso) |
| Guardar sin cambios | `savingWithoutChangesCreatesNoRevisionAndCountsNothing`, `theFormStartsWithTheCurrentValuesAndSavingWithoutChanges…` |
| Impedir corregir o anular dos veces | `anAnnulledMovementCannotBeCorrectedOrAnnulledAgain`, `anAnnulledMovementCannotBeOpenedForCorrection` |
| Persistencia tras reabrir | `correctionsAnnulmentsAndRevisionsSurviveClosingAndReopeningTheDatabase` |
| No consumir orden de registro; `revision_datos` +1 | Las dos primeras de la lista, `annullingAnAbono…` |
| «Ver cambios» sin JSON; snapshot dañado | `theChangeLogShowsPreviousAndNewValuesReasonAndDateNewestFirst`, `MovementChangeLog…`, `aDamagedSnapshotIsReportedAsAnError…` |
| Origen de pago completo → manual | `correctingTheAmountOfAFullPaymentChangesItsOriginToManual`, `CorrectionRulesTest` |
| Aislamiento entre clientes, clientes archivados | `correctionsOfOneClientNeverTouchAnotherAccount`, `archivedClientsAndUnknownMovementsAreNotChanged` |

**Comprobación de que las pruebas detectan fallos** (mutaciones temporales, siempre restauradas y seguidas de una reconstrucción, reinstalación y suite completa):

- Primera entrega: se anuló el control de versión y la validación del historial en `MovementRepository.change`; `MovementCorrectionRepositoryTest` falló en 7 de 32 casos.
- Revisión correctiva, una sola ronda con tres mutaciones: (1) `isSameAttempt` con la lógica anterior (aceptar sin comparar contenido si hay cambios posteriores); (2) formulario editable durante la evaluación y confirmación con los campos actuales; (3) `observeChanges` con la combinación de dos flujos anterior. Resultado: `MovementCorrectionRepositoryTest` falló en 4 de 36 casos (los dos reintentos, la inconsistencia y el observador) y `MovementCorrectionViewModelsTest` en 1 de 16 (la prueba de edición durante la evaluación).
- La mutación 3 **reproduce el defecto real**: el observador activo terminó con `IllegalStateException: La versión del movimiento no coincide con sus revisiones`, es decir, una emisión con el movimiento en una versión y las revisiones de otra.
- Una cuarta mutación (descartar solo por `reviewing`, sin invalidar por identificador de evaluación) hizo fallar `aLateResultOfAnInvalidatedEvaluationNeverReplacesTheCurrentPreview`; esa prueba se ajustó para que la evaluación vieja llegue **mientras** la nueva sigue pendiente, que es el caso donde el identificador importa.

### Compilación y Lint

```
.\gradlew.bat assembleDebug assembleRelease assembleDebugAndroidTest testDebugUnitTest lintDebug --rerun-tasks --no-build-cache --no-daemon --console=plain
```

**BUILD SUCCESSFUL**, 132/132 tareas ejecutadas. Lint: **0 errores y 7 avisos**, los mismos de siempre (versiones más nuevas disponibles). El APK release (`com.example.pagaya`) no contiene clases de prueba (0 coincidencias) y no se instaló.

### Cómo se ejecutaron las pruebas instrumentales

Por la vía que conserva los datos de la app Dev (no `connectedDebugAndroidTest`, que la desinstala):

```
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w com.example.pagaya.dev.test/androidx.test.runner.AndroidJUnitRunner
adb uninstall com.example.pagaya.dev.test
```

Las pruebas usan archivos `test-<uuid>.db` propios y nunca tocan `pagaya.db`. No se desinstaló la app Dev, no se borraron sus datos ni se restauró ninguna copia.

## Recorrido manual en el teléfono

Con `com.example.pagaya.dev`, manejado por ADB (`input`, `uiautomator dump`, `screencap`; el propietario dejó el teléfono conectado y desbloqueado). Cliente sintético «Prueba Seis C»; las cuentas existentes no se modificaron.

| Paso | Resultado |
| --- | --- |
| Fiado de 60.000 («Pedido de revista») y abono de 40.000 («Transferencia») | Saldo `$20.000` |
| Tocar la fila del fiado | Detalle: «Fiado», `+$60.000`, fecha completa, descripción, «Vigente», creación y última modificación; «Corregir» y «Anular» |
| «Corregir» → «Revisar cambios» sin tocar nada | «No hay cambios que guardar»; sin diálogo |
| Valor `30000` con motivo | **Rechazo antes de confirmar:** «Este cambio dejaría sin deuda suficiente al abono de $40.000 del 16 oct 2026…»; lo escrito se conserva |
| Valor `50000` y nota «Pedido corregido» | «Confirmar corrección»: `Valor: $60.000 → $50.000`, `Nota: «Pedido de revista» → «Pedido corregido»`, motivo y «Nuevo saldo de Prueba Seis C: $10.000» |
| «Guardar corrección» | Vuelve al detalle con «Editado» y «Ver cambios»; la fecha de modificación cambió, la de creación no |
| «Ver cambios» | «Corrección», fecha y hora, las dos diferencias, el motivo y «Registro original» (`$60.000`, fecha, nota) |
| Anular el fiado con motivo | **Rechazo:** «No se puede anular: el abono de $40.000 … quedaría sin deuda que cubrir. Revisa ese abono primero.»; el diálogo se queda abierto con el motivo |
| Anular el abono de 40.000 («Lo anote dos veces») | Detalle «Anulado · Abono», `−$40.000` tachado, «Anulado · no afecta el saldo» y «Estado: Anulado» |
| Cuenta tras anular | Saldo `$50.000`; fila «Anulado · Abono» sin ✓ ni «Saldo después»; el fiado sigue con su saldo y «Editado» |
| Anular también el fiado (ya sin abonos vigentes) | Cuenta «Sin movimientos vigentes» con las dos filas anuladas; la lista muestra «Prueba Seis C — Sin movimientos vigentes» |
| `am force-stop` y reabrir (antes de anular el fiado) | La lista mostraba «Debe: $50.000»: todo persistió |

Capturas sintéticas (barra de estado recortada) en `docs/capturas/17/`. Se encontró y corrigió durante el recorrido: la vista previa de la corrección mostraba «Nuevo saldo: -$10.000» antes de confirmar un cambio inválido y el diálogo de anulación decía «quedará en -$40.000»; ahora el rechazo se anuncia antes y no se muestra un saldo imposible.

**Incidente de la pantalla en blanco:** abierto; ver «Revisión correctiva» más abajo.

### Datos del teléfono

Se conservaron todos los datos: «Dairo», «Felipe», «Sandra», los clientes sintéticos de ajustes anteriores y lo que la usuaria haya agregado. Nuevos clientes sintéticos de esta iteración: «Prueba Seis C» (con un fiado y un abono anulados), «Prueba Seis B» y «Prueba Seis Correcciones» (vacíos, creados durante la prueba). La revisión correctiva añadió «Rev Uno» a «Rev Cinco» (creados al intentar reproducir el incidente; «Rev Uno» tiene un fiado corregido de $12.000). No hay forma de borrar clientes en la app todavía; «Borrar datos» en Ajustes elimina **todos**, incluidos los de la usuaria.

### No probado

- TalkBack, fuente al 200 % y pantallas pequeñas en las pantallas nuevas (T-21); las descripciones accesibles existen pero no se escucharon.
- Doble toque físico sobre «Guardar corrección» o «Anular movimiento» (cubierto por pruebas de ViewModel y de repositorio).
- Recreación del proceso con el formulario de corrección a medias en el dispositivo (el estado está en `SavedStateHandle` y hay una prueba de ViewModel, pero no se forzó la muerte del proceso).
- Reinicio del teléfono, modo avión explícito, API 26 y el teléfono de la usuaria.
- Textos largos de motivo y de descripción en el diálogo de confirmación (la nota larga se probó en la iteración anterior para el diálogo del cliente).

## Revisión correctiva

La revisión de la primera entrega encontró tres problemas. Se corrigieron sin cambiar el diseño, el esquema Room (versión 1; `app/schemas` sin cambios) ni agregar funciones.

### 1. La confirmación podía guardar un contenido distinto del revisado

**Defecto:** `review()` evaluaba la vista previa de forma asíncrona, pero el formulario seguía editable mientras esperaba, y `confirm()` guardaba los campos actuales, que podían diferir de los mostrados.

**Corrección** (`MovementEditViewModel`):
- Al revisar se congela una `CorrectionRequest` inmutable (importe, fecha, nota y motivo tal como se escribieron, más la versión esperada). La `CorrectionPreview` la lleva dentro y `confirm()` guarda **esa solicitud**.
- «Evaluando» se marca de forma atómica (`compareAndSet`) y síncrona antes de lanzar la corrutina: el formulario no admite cambios y una segunda revisión (o un doble toque) no lanza otra evaluación.
- Cada evaluación lleva un identificador; cancelar la vista previa (`dismissPreview`) o iniciar otra revisión invalida la anterior, y un resultado tardío se descarta en lugar de aplicarse.
- La evaluación es inyectable (`CorrectionEvaluator`, con `HistoryCorrectionEvaluator` como implementación real) para poder retrasarla de forma determinista en las pruebas. El botón muestra «Revisando…» y los campos quedan deshabilitados.

**Pruebas** (`MovementCorrectionViewModelsTest`, con una compuerta por evaluación):
- `editsWhileThePreviewIsBeingEvaluatedAreBlockedAndTheConfirmedContentIsExactlyTheReviewedOne`: retrasa la evaluación, intenta cambiar importe, fecha, nota y motivo, repite la revisión y confirma; comprueba que hubo una sola evaluación, que la solicitud y lo guardado (importe, nota, fecha y motivo de la revisión) son exactamente lo revisado, y que cambiar el formulario con la vista previa abierta tampoco altera nada.
- `cancellingAPendingEvaluationDiscardsItsLateResult` y `aLateResultOfAnInvalidatedEvaluationNeverReplacesTheCurrentPreview`: el resultado de una evaluación invalidada, aunque llegue mientras otra sigue pendiente o después de ella, nunca se muestra ni se guarda.

### 2. `isSameAttempt` aceptaba reintentos sin comparar el contenido

**Defecto:** con `if (now.version != newVersion) return true`, un reintento del mismo ID se aceptaba sin comparar su contenido cuando el movimiento ya había recibido cambios posteriores.

**Corrección** (`MovementRepository`): el contenido del intento se compara con el **estado que produjo**, obtenido de las revisiones existentes: el movimiento actual si sigue en la versión que produjo el intento, o el snapshot de la revisión siguiente (`findByPreviousVersion`) si hubo cambios posteriores. Si esa revisión falta o su snapshot no corresponde, lanza una excepción por inconsistencia real. Sin cambios de esquema.

**Pruebas** (`MovementCorrectionRepositoryTest`):
- `retryOfAnEarlierCorrectionIsComparedAgainstTheStateItProducedNotTheCurrentOne`: corrección A, corrección B posterior, reintento idéntico de A → `Saved` sin escrituras; el mismo ID con otro importe, otra fecha, otra descripción, sin descripción, **con el contenido actual de B**, con otro motivo, como anulación o con otra versión esperada → `Conflict` sin escrituras (movimiento, revisiones y metadatos intactos).
- `retryOfACorrectionStillComparesCorrectlyAfterTheMovementWasAnnulled`: el mismo caso con una anulación posterior.
- `aMissingFollowingRevisionIsAnInconsistencyNotASilentAnswer`: si falta la revisión siguiente, falla con `IllegalStateException` y no escribe nada.

### 3. `observeChanges` combinaba dos lecturas no consistentes

**Defecto:** `combine(observeById, revisiones)` no garantiza que ambos lados vengan del mismo instante: una emisión podía traer el movimiento en una versión y las revisiones de otra.

**Corrección:** `MovementDao.observeWithRevisions` devuelve el movimiento y sus revisiones en **una sola lectura transaccional observable** (`@Transaction` con `@Relation`; POJO `MovementWithRevisions`, no es una tabla). `observeChanges` y el detalle del movimiento (`observeMovementDetail`, que reemplaza a `observeRevisionCount`) se apoyan en ella. No se eliminó ninguna validación: la continuidad de versiones y el estado de cada snapshot se siguen comprobando y, si hay una inconsistencia real, el flujo falla con un error que la pantalla muestra.

**Prueba** (`anActiveChangesObserverOnlySeesConsistentSetsWhileCorrectionsAreMade`): mantiene un observador activo en un hilo mientras otro hilo hace 40 correcciones; comprueba que el observador no falla nunca, que hay varias emisiones, que cada emisión es exactamente las *n* primeras revisiones (del más reciente al más antiguo, en el orden esperado), que están encadenadas y que la última coincide con el estado real. Con la combinación anterior esta misma prueba falla con `La versión del movimiento no coincide con sus revisiones`.

### Incidente de la pantalla en blanco (abierto)

**Qué se vio.** Dos veces, durante guiones automatizados por ADB, la pantalla de la app quedó en blanco (fondo claro con la barra de estado) y el árbol de accesibilidad mostraba el `ComposeView` invisible y con límites 0×0. Reabrir la app (`force-stop` y `start`) la recuperó.

**Evidencia de los logs** (`logcat -b all` del teléfono, incidentes de las 19:15 y 19:17):
- Una sola instancia de `MainActivity` por proceso: sin `wm_destroy_activity`, `wm_relaunch_activity` ni `wm_restart_activity` entre el arranque y mi `force-stop`. **No hubo recreación de la actividad ni cambio de configuración.**
- Sin `FATAL`, sin ANR, sin excepciones de la app; el proceso seguía vivo. Solo «Davey!» y «Skipped frames» propios del arranque en frío.
- No se pudo fijar el instante en que apareció el blanco. Una primera lectura del log (un `AssistStructure` con «2 views») se descartó como indicio: en las ejecuciones posteriores ese recuento corresponde a pantallas sin campos de texto, no a una pantalla vacía.
- Los dos incidentes sucedieron poco después de un `uiautomator dump`, pero eso no basta para atribuirlos a él: es lo que se hacía constantemente.

**Revisión de navegación y estado.** `PagaYaNavHost` tiene `clients` como destino inicial y todos los destinos dibujan siempre algo (carga, error, no encontrado o contenido); ningún ViewModel deja una pantalla sin contenido. No se encontró un camino de código que componga una raíz vacía o de tamaño cero.

**Intentos de reproducción** (con la versión final, sin tocar datos salvo crear clientes sintéticos «Rev Uno» a «Rev Cinco»):
- Tres ejecuciones sin `uiautomator`, solo con toques por coordenadas y capturas de pantalla: crear cliente → cuenta → tocar el centro del texto del historial vacío → escribir `60000`; capturas tras guardar, tras escribir y 20 s después. Tamaños de captura 78–128 KB; una pantalla en blanco pesa unos 19 KB. **Sin blanco.**
- Dos ejecuciones con cuatro volcados de `uiautomator` seguidos tras la navegación, un toque, texto y otro volcado. **Sin blanco.**
- Antes: tres repeticiones manuales del recorrido original, sin blanco; 60 s de inactividad en la lista, sin blanco; y el uso normal posterior (decenas de pantallas) sin ningún caso más.

**Estado: abierto, sin causa conocida.** No se atribuye a `uiautomator` ni a la app: no hay evidencia suficiente. Si vuelve a ocurrir (en el uso de la usuaria o en pruebas), capturar de inmediato: `adb logcat -b all -d`, `adb shell dumpsys activity top`, `adb shell dumpsys window windows` y una captura de pantalla, y anotar la última acción. Hipótesis sin comprobar: una raíz de Compose de tamaño cero por insets del teclado en edge-to-edge tras ocultar el teclado, o una ventana que perdió visibilidad.

### Resultados de la revisión correctiva

| Comprobación | Resultado |
| --- | --- |
| Compilación sin caché ni daemon (`assembleDebug assembleRelease assembleDebugAndroidTest testDebugUnitTest lintDebug --rerun-tasks --no-build-cache --no-daemon`) | **BUILD SUCCESSFUL**, 132/132 tareas |
| Pruebas JVM | **54/54** |
| Lint | **0 errores**, 7 avisos (los mismos de versiones más nuevas) |
| Pruebas instrumentadas en el teléfono (3 ejecuciones seguidas) | **123/123** cada vez (116 anteriores + 4 de repositorio y 3 de ViewModel nuevas) |
| APK release | Sin clases de prueba (0 coincidencias); no instalado |
| Esquema Room | Sin cambios (`app/schemas` sin modificaciones) |
| Verificación manual tras el refactor | En «Rev Uno»: fiado de $10.000 → Corregir a $12.000 con motivo → «Confirmar corrección» (`Valor: $10.000 → $12.000`, nuevo saldo $12.000) → guardado, «Editado» y «Ver cambios» |

La app «Paga Ya! Dev» quedó actualizada con `install -r` (datos conservados); la suite instrumentada se ejecutó con `install -r` y `am instrument`, y solo se desinstaló `.dev.test`.

## Estado del teléfono al cerrar la iteración

- Instalada: `com.example.pagaya.dev` («Paga Ya! Dev», 0.1.0-dev), última versión debug, actualizada con `install -r` (datos conservados).
- No instalados: `com.example.pagaya` (release) ni `com.example.pagaya.dev.test`.

## Pendientes

- Reactivar movimientos anulados (fuera de alcance; HU-08.7 lo resuelve hoy registrando de nuevo).
- Archivar/reactivar clientes y vista «Archivados» (HU-02/HU-06): el repositorio ya bloquea cambios en archivados, falta la interfaz.
- Respaldo, exportación, restauración y Drive (HU-09). La restauración deberá validar también las revisiones (continuidad, snapshot y saldo; casos T-23 a T-26).
- Accesibilidad (T-21) y medición de tiempos con la usuaria.
- Mostrar el saldo resultante y agrupar por día en el historial (HU-07.1), y un menú «⋮» con Respaldo y Archivados (docs/06).
- Pruebas de interfaz Compose automáticas (no hay dependencias de prueba de UI); la interfaz nueva se comprobó a mano.
- Todos los casos críticos de `docs/10` deben pasar antes de usar la libreta real.

## Pasos para probar en el teléfono

1. Abrir **Paga Ya! Dev** y crear un cliente de prueba. Registrar un fiado de `60000` y un abono de `40000`.
2. Tocar la fila del fiado: se ve su detalle completo. Pulsar **Corregir**, poner `30000`, escribir un motivo y **Revisar cambios**: debe avisar que el abono de $40.000 quedaría sin deuda.
3. Poner `50000`, cambiar la nota y **Revisar cambios**: aparece «Confirmar corrección» con lo que cambia y el nuevo saldo. **Guardar corrección**: el detalle muestra «Editado».
4. Pulsar **Ver cambios**: valores anteriores y posteriores, motivo, fecha y «Registro original».
5. Intentar **Anular** el fiado con un motivo: se rechaza porque el abono depende de él.
6. Abrir el abono y **Anular** con un motivo: queda en el historial como «Anulado · no afecta el saldo» y el saldo vuelve a subir.
7. Cerrar la app a la fuerza y abrirla: todo debe seguir igual.
