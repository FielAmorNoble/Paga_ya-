# 16 — Movimientos, saldo e historial básico

Fecha: 2026-10-12. Iteración 05, en `develop`. La iteración 06 añadió detalle, corrección y anulación de movimientos: ver [17](17_Correcciones_Movimientos.md); lo que aquí figura como pendiente de HU-08 y de «Anulado» ya está implementado allí. Incluye el ajuste de UX «Información del cliente» (sección propia más abajo).

**Objetivo:** abrir un cliente, registrar un fiado, registrar abonos y liquidar la deuda, viendo de inmediato el saldo y el historial, sin Internet y con persistencia al cerrar la app.

**Esta no es una versión para llevar cuentas reales.** Faltan corrección y anulación de movimientos, archivar clientes y respaldo. Usar solo datos ficticios.

## Base guardada antes de esta iteración

Los cambios pendientes eran exclusivamente de la iteración 04 (clientes) y se guardaron en el commit local `b17c17b` («feat: gestion basica de clientes (iteracion 04)») sobre `develop`. Se comprobó antes que no incluyera `local.properties`, bases de datos ni credenciales. No hubo push ni merge. Los cambios de esta iteración quedan **sin commit**, pendientes de revisión.

## Alcance implementado

| Función | Estado |
| --- | --- |
| Registrar fiado y abono con valor, fecha editable y nota opcional | Hecho |
| Pagar saldo completo con confirmación del importe | Hecho |
| Saldo pendiente destacado y derivado de los movimientos | Hecho |
| Historial con fecha, tipo, importe con signo, nota y saldo tras cada movimiento | Hecho |
| «Debe: $…» y «Pagado ✓» en la lista de clientes; «Sin movimientos» cuando no hay movimientos vigentes | Hecho (automático, ya venía de la consulta agregada) |
| Idempotencia por formulario, protección contra doble toque, rollback sin cambios parciales | Hecho |
| Esquema Room, dependencias | **Sin cambios** (versión 1, sin migración) |

### Archivos

| Archivo | Contenido |
| --- | --- |
| `domain/MovementRules.kt` | `BusinessCalendar` (hoy en America/Bogota), lectura estricta del importe, validación de fecha y descripción |
| `domain/LedgerRules.kt` | Saldo, orden `(fecha, orden_registro)`, prefijos no negativos, motivos de rechazo, historial con saldo por fila |
| `data/MovementRepository.kt` | Único punto de escritura de movimientos; historial observable |
| `data/local/Daos.kt` | Consultas nuevas: movimientos vigentes por cliente (lista y `Flow`) y consumo de `siguiente_orden_registro` |
| `ui/clients/ClientDetailScreen.kt`, `ClientDetailViewModel.kt` | Cuenta: saldo, pago completo, historial, barra «+ Fiado» / «+ Abono» |
| `ui/clients/MovementFormScreen.kt`, `MovementFormViewModel.kt` | Formulario de fiado y abono |
| `ui/PagaYaNavHost.kt`, `ui/common/Common.kt`, `res/values/strings.xml` | Ruta `clients/{id}/movements/{FIADO\|ABONO}`, formatos y textos |

## Reglas

- **FIADO** suma deuda y **ABONO** la reduce. La compra a crédito es un FIADO; el pago completo es un ABONO con origen `PAGO_TOTAL`. No hay otro tipo financiero.
- **Importe:** `Long` en pesos COP entre 1 y 999.999.999. Sin `Float` ni `Double`.
  - Se acepta solo `20000` o agrupación con puntos inequívoca (`20.000`, `$20.000`).
  - Se rechazan sin reinterpretar: vacío, cero y negativos («Escribe un valor mayor que cero»); `20,5`, `20.5`, `20.000,50`, `1,000` y puntos mal agrupados («pesos completos, sin centavos»); letras, exponentes, `+` y ceros a la izquierda («solo números»); y todo lo que supere el máximo, sin convertir antes a `Long` (no hay desbordamiento con textos enormes).
- **Fecha:** inicial = hoy en America/Bogota (no la zona del teléfono); editable entre 1900-01-01 y hoy. El selector solo habilita ese rango y el repositorio lo vuelve a validar.
- **Descripción:** opcional; se recorta; vacía = NULL; hasta 1.000 caracteres Unicode (igual que la base).
- **Cliente:** solo activos reciben movimientos (hoy todos lo son: archivar no existe todavía; el repositorio ya devuelve `ClientArchived`).
- **Historial:** se ordena por `(fecha, orden_registro)`; ningún saldo intermedio puede ser negativo, incluso con movimientos retroactivos. La suma usa `addExact`: un desbordamiento se rechaza, nunca se envuelve.
- **Mensajes de rechazo:**

| Situación | Mensaje | Dónde se muestra |
| --- | --- | --- |
| Abono sin deuda | «Este cliente no tiene saldo pendiente…» | Bajo el valor |
| Abono mayor que el saldo | «El abono supera lo que debe: $25.000» | Bajo el valor |
| El total alcanza, pero en esa fecha no había deuda | «En esa fecha aún no había deuda suficiente…» | Bajo la fecha |
| El abono nuevo dejaría sin cubrir a un abono posterior | «Con esa fecha, un abono posterior quedaría sin deuda que lo cubra…» | Bajo la fecha |
| Desbordamiento | «La cuenta llegaría a un valor demasiado grande» | General |

## Transacción de alta

`MovementRepository.register` y `payFullBalance` ejecutan, dentro de **una** transacción Room:

1. **Idempotencia:** si ya existe un movimiento con el ID de solicitud, compara cliente, tipo, origen, importe, fecha y descripción. Iguales → devuelve el mismo movimiento (sin tocar contadores); distintos → `Conflict`.
2. Lee el cliente (existe y está activo) y sus movimientos vigentes.
3. Lee `siguiente_orden_registro` como orden del alta.
4. (Pago completo) relee el saldo y lo compara con el confirmado.
5. Valida el historial candidato con ese orden.
6. Inserta el movimiento, consume `siguiente_orden_registro` y suma 1 a `revision_datos`.

Un rechazo devuelve el resultado sin escribir nada. Cualquier excepción deshace todo.

- **Orden consumido solo tras validar.** El enunciado lista «asignación e incremento» antes de validar; aquí se *lee* el orden primero, se valida con él y se *incrementa* al insertar. Así un intento rechazado no deja huecos en la numeración ni sube metadatos. El orden sigue siendo único, creciente y nunca reutilizado.
- **UUID por formulario:** el formulario genera el ID de solicitud una vez (guardado en `SavedStateHandle`, sobrevive a la recreación del proceso) y lo reutiliza en cada reintento. Un formulario nuevo genera otro ID, así que dos abonos idénticos registrados a propósito sí coexisten. No se deduplica por cliente/valor/día.
- **Doble toque:** el ViewModel marca «guardando» de forma síncrona antes de lanzar la corrutina y el botón se deshabilita; además el repositorio es idempotente aunque dos llamadas lleguen a la vez.
- Limitación conocida: si un guardado se confirma pero la app falla antes de informarlo y la usuaria cambia el valor antes de reintentar, el mismo ID con otro contenido da `Conflict` («Este formulario ya guardó otro movimiento…») en vez de duplicar. Es el comportamiento seguro.

## Pagar saldo completo

1. «Pagar saldo completo» (solo con deuda) abre un diálogo: «Registrar pago de $25.000 de {cliente}» y «Confirmar pago». El ID de solicitud y la fecha (hoy) se fijan al abrirlo y se reutilizan en reintentos.
2. Al confirmar, el repositorio **vuelve a leer el saldo en la transacción**. Si no coincide con el confirmado, no guarda: el diálogo cambia a «El saldo cambió. Ahora {cliente} debe $X… No se guardó ningún pago» (o «ya no tiene saldo pendiente»). El importe confirmado nunca se reajusta; la usuaria vuelve a pulsar y confirma el nuevo.
3. Si el saldo supera 999.999.999, no se ofrece confirmar: «El saldo de $X supera el máximo de $999.999.999 por movimiento. Regístralo en varios abonos.»
4. Un reintento de un pago que ya se guardó se reconoce por el ID antes de releer el saldo (que ya es 0), así que no falla ni se duplica.

## Interfaz

- **Cuenta:** encabezado fijo con «Volver», el nombre, el botón ⓘ «Información del cliente» y «Editar»; tarjeta «Saldo pendiente» con el importe grande (`$0` y «Pagado ✓» con historia y saldo cero; «Sin movimientos» sin historia), «Pagar saldo completo» (con deuda) e historial. Los datos del cliente ya no van al final de la lista: se ven en el diálogo del botón ⓘ (ver «Ajuste de UX»). Barra inferior con «+ Fiado» y «+ Abono» de 56 dp, respetando la barra de navegación. Aviso «Fiado guardado», «Abono guardado» o «Pago registrado» (Snackbar).
- **«+ Abono» desactivado sin saldo,** con el texto «No hay saldo pendiente» (doc 06).
- **Formulario:** cliente y su estado de cuenta arriba, tipo en el título y en el botón («Guardar fiado» / «Guardar abono»), valor con teclado numérico y foco inicial, vista previa «Vas a registrar: $20.000», fecha con selector, nota opcional, errores por campo sin perder lo escrito, «Guardando…» con el botón deshabilitado, y «¿Descartar los cambios?» al salir con datos (botón del sistema o «Volver»).
- **Historial:** más reciente primero; fecha corta («5 oct 2026»), tipo, importe, nota (2 líneas en la lista) y «Saldo después». Fiado: `+$60.000`. Abono: `✓ Abono` y `−$15.000` en verde con fondo suave. Pago completo: `✓ Pago completo`. El abono se distingue por ✓, texto, signo y fondo, no solo por el color. Cada fila es un elemento accesible con descripción hablada («Abono de 15.000 pesos, 12 de octubre de 2026. Saldo después: 45.000 pesos.»).
- Todos los textos están en `strings.xml`. No hay botones sin implementar: no aparecen «Corregir», «Anular», «Ver cambios» ni respaldo.

## Ajuste de UX: información del cliente

Los datos del cliente aparecían después del historial y costaba encontrarlos cuando había muchos movimientos. Solo cambió la interfaz de la cuenta (`ClientDetailScreen.kt`, `strings.xml` y el icono local `ic_info.xml`); no cambian Room, las reglas financieras, el saldo, las acciones financieras ni las dependencias.

- **Encabezado fijo:** `[←] Nombre ......  [ⓘ] [Editar]`. El botón ⓘ mide 48 × 48 dp (medido en el teléfono: 120 px a 400 dpi), tiene la descripción accesible «Información del cliente» y está siempre a la vista, aunque el historial esté desplazado. «Editar» se conserva. El nombre ocupa el espacio restante, hasta 2 líneas con puntos suspensivos, así que los botones no se superponen con nombres largos.
- **Diálogo «Información del cliente»** (ⓘ, y «Cerrar»):
  - **Nombre** completo.
  - **Teléfono**, o «Sin teléfono».
  - **Nota del cliente** completa, o «Sin nota». La etiqueta «Nota del cliente» la distingue de las notas de movimientos.
  - «Cliente desde dd/mm/aaaa».
  - El contenido se desplaza dentro del diálogo cuando es largo. «Volver» (botón del sistema), tocar fuera o «Cerrar» lo cierran y dejan la cuenta tal cual: el diálogo no recompone la lista, así que el historial conserva su posición. El estado abierto/cerrado sobrevive a la rotación.
  - Lee el cliente observado en vivo: tras editarlo, la información refleja los cambios.
- **«Copiar teléfono»:** aparece solo si hay teléfono, debajo del número. Copia solo el número guardado (por ejemplo `300 000 0001`), sin etiquetas, con el portapapeles estándar; no pide ningún permiso (ni contactos ni llamadas). En Android 13+ el sistema ya muestra su propia vista previa del portapapeles, así que la app no añade otro aviso; en Android 12 o anterior muestra «Teléfono copiado» dentro del diálogo (región de anuncio accesible).
- **Botón debajo del número, no al lado:** con texto al 200 % un `Row` partía el número en columnas de dos dígitos; se corrigió poniendo el botón debajo.
- **Retirado:** la sección de datos al final del historial, que duplicaba la información.

### Verificación del ajuste en el teléfono

Cliente sintético «Cliente Sintetico De Prueba Con Un Nombre Extremadamente Largo Para Revisar El Encabezado Fijo De la cuenta» (nombre de 107 caracteres; nota de unos mil), con 12 fiados, y «Prueba UX Sin Datos».

| Comprobación | Resultado |
| --- | --- |
| ⓘ con el historial desplazado | Abre el diálogo con el historial desplazado detrás |
| Teléfono y nota presentes | Se muestran completos; la nota larga se desplaza dentro del diálogo |
| Teléfono y nota ausentes | «Sin teléfono» y «Sin nota»; no aparece «Copiar teléfono» |
| Nombre largo | Encabezado: 2 líneas con puntos suspensivos, ⓘ y «Editar» visibles; el diálogo muestra el nombre completo |
| Texto grande (escala de fuente 2,0) | El encabezado y el diálogo siguen usables; el diálogo se desplaza hasta «Cerrar». Se encontró el defecto del número partido y se corrigió |
| Copiar y pegar | Vista previa del portapapeles del sistema (Android 15); al pegar en el buscador de clientes aparece exactamente `300 000 0001` |
| Cerrar sin perder la posición | Con «Cerrar» y con «Volver», la fila superior del historial es la misma que antes de abrir |
| Editar y reabrir ⓘ | «Prueba UX Sin Datos»: tras agregar el teléfono `311 111 2222` y la nota «Nota editada», el diálogo muestra los datos nuevos |

Capturas con datos sintéticos (barra de estado recortada): `docs/capturas/16/07` a `10`. La escala de fuente del teléfono se subió a 2,0 solo para esta prueba y se restauró a su valor original (0,85).

**No probado en este ajuste:** el aviso «Teléfono copiado» de Android 12 o anterior (el teléfono tiene Android 15), TalkBack (la descripción accesible existe pero no se escuchó), pantallas pequeñas y el teléfono de la usuaria.

**Datos de prueba que quedaron en la app Dev** (no se restauró ninguna copia, por indicación): «Cliente Sintetico De Prueba Con Un Nombre Extremadamente Largo…» (12 fiados, deuda $90.000) y «Prueba UX Sin Datos». Los clientes «Dairo» y «Sandra» no se modificaron. La app aún no permite archivar ni borrar clientes; para limpiar todo está «Borrar datos» en Ajustes (elimina también a «Dairo» y «Sandra»).

## Decisiones

- **Sin cambios de esquema ni de dependencias.** `movimiento`, `libreta_metadata` y los triggers ya cubrían todo. El selector de fecha es el `DatePicker` de Material 3, ya incluido.
- **Dominio y `MovementType`:** `domain/` reutiliza los enums `MovementType` y `MovementOrigin` de `data/local` (Kotlin puro, sin Android) en lugar de duplicarlos.
- **Saldo en dos consultas:** el saldo de la cuenta y de la lista viene de la consulta agregada (docs/03); el saldo por fila, del cálculo en Kotlin sobre el historial. Ambas observan la misma tabla y se combinan en el ViewModel.
- **Solo movimientos vigentes en el historial.** Los anulados se mostrarán cuando exista la anulación.
- **Fecha del pago completo = hoy.** Corregirla es parte de la edición (HU-08), pendiente.
- **Aviso tras guardar:** el formulario lo deja en el `SavedStateHandle` de la entrada de navegación de la cuenta; el pago completo lo emite su ViewModel. «+ Fiado»/«+ Abono» usan `launchSingleTop` para no abrir dos formularios con un doble toque.
- **Dato nuevo sobre las pruebas en el teléfono:** `connectedDebugAndroidTest` desinstala `com.example.pagaya.dev` al terminar y con ello borra sus datos. Ver «Cómo se ejecutaron las pruebas instrumentales».

## Pruebas y resultados

| Conjunto | Casos | Resultado |
| --- | --- | --- |
| JVM `MovementRulesTest` | 10 | **10/10** |
| JVM `LedgerRulesTest` | 12 | **12/12** |
| JVM `ClientRulesTest` (anterior) | 8 | **8/8** |
| Instrumentada `MovementRepositoryTest` (Room real en archivo) | 30 | **30/30** |
| Instrumentada `MovementViewModelsTest` | 8 | **8/8** |
| Instrumentadas anteriores (`ClientRepositoryTest` 12, `ConstraintsTest` 12, `DatabaseSetupTest` 6, `TransactionTest` 3) | 33 | **33/33** |

Total: **30/30 JVM** y **71/71 instrumentadas**, ejecutadas en el Motorola moto g84 5G (Android 15) el 2026-10-12. Tras el ajuste de UX se repitieron la compilación sin caché, las pruebas JVM, Lint y las 71 instrumentadas: mismos resultados (el ajuste es solo de interfaz y no añadió pruebas automáticas; se verificó en el teléfono).

Cobertura de lo pedido:

| Requisito | Dónde se comprueba |
| --- | --- |
| Fiado 60.000 → 60.000; abono 15.000 → 45.000; abono 20.000 → 25.000; pago completo → 0 | `referenceJourneyFiadoAbonosAndFullPayment`, `referenceExampleFromTheDataModel` |
| Rechazo de sobreabono y de abono sin deuda, sin escribir | `abonoAboveTheBalance…`, `abonoWithoutDebt…` |
| Fecha futura, anterior a 1900, 0, negativos, decimales, texto, exponente, límite | `invalidAmountsAndDates…`, `MovementRulesTest` |
| Retroactivo con saldo intermedio negativo y saldo final positivo | `retroactiveAbonoThatCreatesANegativeIntermediateBalance…`, `retroactiveAbonoThatLeavesALaterAbonoUncovered…` |
| Orden estable el mismo día | `sameDayEntriesKeepRegistrationOrder`, `abonoRegisteredBeforeItsDebtTheSameDay…`, `registrationOrderIsGlobal…` |
| Reintento idempotente; mismo ID con otro contenido | `retryWithTheSameRequestId…`, `theSameRequestIdWithDifferentContent…` |
| Doble toque | `doubleTapOnSaveCreatesASingleMovement`, `fullPaymentDoubleTapCreatesASinglePayment` (ViewModel) y `doubleTapRunningConcurrently…` (4 llamadas simultáneas, repositorio) |
| Dos abonos iguales a propósito | `twoIntentionalIdenticalAbonosAreBothRecorded`, `twoFormsWithTheSameValuesRecordTwoMovements` |
| Saldo cambiado entre confirmación y guardado | `fullPaymentIsRejectedWhenTheBalanceChanged…`, `fullPaymentWhoseBalanceChangedAfterConfirmation…` (ViewModel) |
| Pago completo sobre el máximo | `fullPaymentAboveThePerMovementLimit…` |
| Rollback sin cambios parciales | `aFailureWhileUpdatingTheMetadataRollsBack…` (trigger de prueba que falla en el último paso: se deshacen movimiento y orden), `aFailureWhileInsertingTheMovement…` |
| Aislamiento entre cuentas | `accountsOfDifferentClientsAreIsolated` |
| Persistencia tras reabrir la base | `committedMovementsSurviveClosingAndReopening…` |
| Lista con «Debe» / «Pagado ✓» / «Sin movimientos» | `clientListReflectsBalancesAutomatically` |
| Invariantes (50 secuencias con semilla fija) | `generatedValidSequencesKeepEveryInvariant` |

Comprobación de que las pruebas detectan fallos: se cambió temporalmente la regla de saldo negativo en `LedgerRules` y fallaron 5 pruebas JVM; se revirtió.

### Compilación y Lint

```
.\gradlew.bat assembleDebug assembleRelease assembleDebugAndroidTest testDebugUnitTest lintDebug --rerun-tasks --no-build-cache --no-daemon --console=plain
```

**BUILD SUCCESSFUL**, 132/132 tareas ejecutadas. Lint: **0 errores y 7 avisos**, los mismos de la iteración anterior (versiones más nuevas disponibles). El APK release (`com.example.pagaya`) no contiene clases de prueba (0 coincidencias) y **no se instaló** en el teléfono.

### Cómo se ejecutaron las pruebas instrumentales

La app «Paga Ya! Dev» del teléfono tenía dos clientes ficticios creados por la usuaria («Sandra» y «Dairo»). `connectedDebugAndroidTest` los habría borrado al desinstalar la app. Para no perderlos:

```
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w com.example.pagaya.dev.test/androidx.test.runner.AndroidJUnitRunner
adb uninstall com.example.pagaya.dev.test
```

`install -r` actualiza conservando los datos y las pruebas usan archivos de base propios (`test-<uuid>.db`), nunca `pagaya.db`. Antes de empezar se guardó una copia de la base fuera del repositorio.

## Recorrido manual en el teléfono

Con `com.example.pagaya.dev`, manejado por ADB (`input`, `uiautomator dump`, `screencap`); el propietario desbloqueó el teléfono. Cliente de prueba «Prueba Cinco», creado por la app.

| Paso | Resultado |
| --- | --- |
| Cuenta nueva | «Saldo pendiente — Sin movimientos»; «+ Abono» desactivado y «No hay saldo pendiente» |
| Fiado con valor `20,5` | «Escribe el valor en pesos completos, sin centavos…»; lo escrito se conserva |
| Fiado de `60.000`, fecha 1 oct (selector: los días futuros aparecen deshabilitados), nota «Pedido de revista» | Vuelve a la cuenta: `$60.000`, fila `+$60.000`, «Saldo después: $60.000» |
| Abono 15.000 y abono 20.000 | Saldos `$45.000` y `$25.000`; filas `✓ Abono −$15.000` / `−$20.000` |
| Abono de 25.001 y «Atrás» | «El abono supera lo que debe: $25.000»; diálogo «¿Descartar los cambios?» → «Descartar» vuelve sin cambios |
| «Pagar saldo completo» | «Registrar pago de $25.000 de Prueba Cinco.» → «Confirmar pago» → `$0`, «Pagado ✓», fila `✓ Pago completo −$25.000` |
| Lista de clientes | «Prueba Cinco — Pagado ✓»; «Dairo» y «Sandra» siguen «Sin movimientos» |
| `am force-stop` y reabrir (pid distinto) | La lista y la cuenta conservan todo |
| Fiado de 83.000 y abono de 10.000 con fecha 1 oct | «Con esa fecha, un abono posterior quedaría sin deuda que lo cubra…»; la lista muestra «Debe: $83.000» |

Capturas (barra de estado recortada) en `docs/capturas/16/`.

Al terminar la iteración 05 se **restauró la copia de la base** tomada al inicio, de modo que la app Dev quedó como la encontró la usuaria: solo «Sandra» y «Dairo», sin movimientos. «Prueba Cinco» y sus movimientos no existen en el teléfono. (La usuaria registró después sus propios movimientos de prueba; el ajuste de UX no restauró ninguna copia.)

### No probado

- Reinicio del teléfono (no se reinició).
- TalkBack, fuente al 200 % y pantallas pequeñas (T-21); las descripciones accesibles existen pero no se escucharon.
- Doble toque físico en el botón Guardar (cubierto por pruebas de ViewModel y repositorio, no por toques reales).
- Modo avión explícito (la app no usa red; no se midió).
- Teléfono de la usuaria (Redmi Note 14 Pro), API 26, lista de 1.000 clientes y 20.000 movimientos, y rendimiento en release (T-22).
- Recreación del proceso con el formulario a medias: el estado está en `SavedStateHandle` y hay una prueba de ViewModel que lo restaura, pero no se forzó la muerte del proceso en el dispositivo.

## Estado del teléfono al cerrar la iteración

- Instalada: `com.example.pagaya.dev` («Paga Ya! Dev», 0.1.0-dev), última versión debug, actualizada con `install -r` (datos conservados): «Dairo», «Sandra» y los dos clientes sintéticos del ajuste de UX.
- No instalados: `com.example.pagaya` (release) ni `com.example.pagaya.dev.test`.

## Pendientes

- **HU-08:** editar y anular movimientos, con motivo y revisiones (T-12 a T-15, T-18); mostrar movimientos anulados y «Editado». El orden y el saldo ya contemplan solo los vigentes.
- **HU-02/HU-06:** archivar y reactivar clientes, vista «Archivados»; el repositorio ya rechaza movimientos en clientes archivados, falta la interfaz.
- Detalle de un movimiento (tocar una fila); hoy las filas no son interactivas.
- Respaldo, exportación y Drive (HU-09).
- Accesibilidad (T-21) y medición de tiempos con la usuaria (el recorrido de diez segundos).
- Pruebas automáticas de interfaz Compose (no hay dependencias de prueba de UI); el ajuste de información del cliente se comprobó solo a mano.
- Menú «⋮» del doc 06 (Respaldo, Archivados): no se muestra mientras no exista.
- Todos los casos críticos de `docs/10` deben pasar antes de usar la libreta real.

## Pasos para probar en el teléfono

1. Abrir **Paga Ya! Dev** (ya instalada; no la app normal «Paga Ya!»). Aparecen los clientes ficticios existentes.
2. Crear un cliente nuevo («Nuevo cliente»), por ejemplo «Cliente de prueba». Se abre su cuenta: «Sin movimientos».
3. Pulsar **+ Fiado**, escribir `60000`, dejar la fecha o cambiarla con «Cambiar», agregar una nota y **Guardar fiado**. Debe verse `$60.000` y la fila `+$60.000`.
4. Pulsar **+ Abono** y guardar `15000`, luego `20000`. Los saldos deben ser `$45.000` y `$25.000`, con ✓ y signo menos.
5. Probar errores: abono de `99999` («supera lo que debe»), `20,5`, `0`, y salir con «Atrás» tras escribir algo («¿Descartar los cambios?»). Lo escrito no se pierde mientras se corrige.
6. Pulsar **Pagar saldo completo**, revisar el importe y **Confirmar pago**: saldo `$0`, «Pagado ✓», y «+ Abono» desactivado.
7. Volver a la lista: el cliente muestra «Pagado ✓» (o «Debe: $…» si registraste otro fiado).
8. Pulsar ⓘ en el encabezado: se ven nombre, teléfono, nota y fecha de alta; «Copiar teléfono» copia el número. Con un historial largo, ⓘ sigue accesible.
9. Cerrar la app a la fuerza (Ajustes → Apps → Paga Ya! Dev → Forzar detención) y abrirla: los datos deben seguir.

Para limpiar los datos de prueba sin tocar nada más: Ajustes → Apps → «Paga Ya! Dev» → Almacenamiento → Borrar datos. Eso elimina **todos** los clientes y movimientos de la app Dev, incluidos «Sandra» y «Dairo».
