# 20 — Restauración desde archivo y recuperación del estado anterior

Iteración 09, 2026-10-17. Checkout local `develop`, base `ca141b3` (iteración 08 confirmada). Cambios sin commit para revisión. No cambia el esquema Room v1, el formato del ZIP, las versiones del stack ni las reglas financieras. HU-09 sigue parcial: Google Drive está pendiente.

**Nota, 2026-10-17 (tarde):** restaurar desde una copia de Drive elegida en el mismo selector `OpenDocument` quedó comprobado en QA Restauración con contenido idéntico (SHA-256 de `data.json`), sin cambios de código. Ver [22](22_Validacion_Release_Drive.md).

## Flujo implementado

En Respaldo aparece «Restaurar desde archivo», incluso con la libreta vacía. `OpenDocument` abre el selector Android; cancelarlo vuelve sin error. Se lee el URI una vez hacia un archivo privado en `noBackupFilesDir/restauracion_tmp`, con `LimitedInputStream`: máximo real de 25 MiB. No se confía en extensión, nombre, tamaño declarado ni permiso permanente.

`BackupValidator.readValidated` conserva el único lector y contrato de [19](19_Respaldo_Archivo.md): ZIP estricto, CRC/tamaños/índice local y central, checksum, versiones, UTF-8/JSON, límites por campo, relaciones, historial de revisiones y saldos. Las fechas se comprueban contra hoy en America/Bogota; un futuro exige revisar el reloj, sin cambiar fechas. Continúan los límites de 50 MiB para datos, 64 KiB para manifiesto y 100.000 filas por lista.

Las filas validadas se importan en una base Room temporal de nombre propio. Se regenera `nombre_busqueda` con `ClientRules.searchKey`. SQLite aplica las mismas restricciones y claves foráneas; luego se comprueban `integrity_check`, `foreign_key_check`, conteos, metadatos/siguiente orden y nuevamente historial, revisiones y saldos usando las reglas del validador. La base temporal se cierra: nunca se convierte en operativa ni se intercambian archivos SQLite abiertos.

El parser sigue conservando listas completas durante la validación inicial; streaming de lectura no equivale a memoria constante. Después de importar, esas listas dejan de estar referenciadas. La comprobación del staging trabaja por cliente y la copia final por cursores/fila; la cuenta mayor aún puede consumir memoria proporcional. El preview guarda resumen y huellas, sin otra representación completa de las filas.

La vista previa muestra captura, clientes/movimientos/revisiones, pendiente recalculado y comparación actual. Dice «Esta copia reemplazará las cuentas de este teléfono». Una copia vacía es válida y lleva advertencia explícita cuando sustituye datos. Cancelar no modifica la libreta. No se mantiene una transacción mientras se decide.

La confirmación corresponde al archivo privado y a la base cerrada que se validaron, fijados por SHA-256; no vuelve a consultar al proveedor. Antes de aplicar se comprueban ambas huellas, ausencia de WAL pendiente y nuevamente el staging, incluida la fecha actual. Alterarlos invalida la operación.

## Exclusión y solicitudes antiguas

Una instancia `LedgerCoordination` pertenece a la instancia Room operativa compartida. Orden obligatorio:

1. `operation`: respaldo/restauración, con `tryLock` en acciones de usuario.
2. `writes`: todas las escrituras de clientes/movimientos y la captura consistente.
3. Transacción Room.

El negocio solo toma `writes`; nunca adquiere `operation` dentro de una transacción o del bloqueo de escritura. La preparación y la exportación mantienen `operation` durante su fase; una captura esperando el selector también bloquea restaurar mediante su registro `ARCHIVO/PREPARANDO`. La captura de respaldo libera `writes` al terminar de leer el snapshot consistente. Restaurar lo mantiene durante la comprobación final, protección previa y reemplazo.

La copia de recuperación llama a `createRecoveryLocked`, que exige ambos mutex ya adquiridos y **no** llama a `prepare` ni vuelve a adquirir un mutex no reentrante. Una restauración no puede comenzar con otro respaldo en curso. Las lecturas UI pueden continuar; no entra ninguna escritura de negocio durante la aplicación.

Dentro de la exclusión se comparan identidad, época y revisión con la vista previa (además de conteos y total). Un cambio devuelve una comparación actualizada y exige otra confirmación. Todos los ViewModels con escrituras reciben la época del destino de navegación y la conservan en `SavedStateHandle`. El repositorio verifica ese token dentro de la transacción protegida, antes de idempotencia o escritura. Una solicitud vieja en espera falla tras cambiar la época.

El observador raíz de época vuelve a Clientes y elimina la pila anterior. Las pantallas y formularios anteriores no pueden escribir en la nueva libreta; las consultas vuelven a emitir los datos actuales.

## Copia previa y atomicidad

«Sin datos» significa cero clientes, movimientos y revisiones: incluye archivados, anulados e historial. Si existe cualquier fila se captura el estado actual, se escribe y valida una copia privada `RECUPERACION`, se sincroniza su archivo y se registra como verificada. Si falla lectura, tamaño, espacio, escritura o validación, se aborta sin reemplazar cuentas.

Las copias viven en `noBackupFilesDir/recuperacion`, con nombres UUID únicos. No se sobrescriben. La metadata solo cambia su localizador activo al confirmar correctamente la transacción. Así se conserva la anterior incluso si la operación que intenta sustituirla falla. La limpieza de temporales nunca elimina ZIP verificados ni archivos externos. Se conservan también copias privadas extra de operaciones anteriores o fallidas: **no hay rotación automática en esta iteración**; pueden ocupar espacio. No cuentan como exportación externa ni protegen frente a pérdida del teléfono.

Antes del reemplazo se escribe un identificador en `AtomicFile application.intent`, con `fsync`. Una única transacción operativa:

1. Elimina revisiones, movimientos y clientes en orden de dependencias.
2. Inserta las filas del staging conservando IDs y datos portables.
3. Actualiza la fila existente de `libreta_metadata`: identidad/creación/revisión/siguiente orden de la copia, nueva época local, identificador/hora de restauración y localizador previo.

La fila de metadata no se elimina, no se desactivan restricciones y no hay fallback destructivo. No se importan registros de respaldo externos. Los registros locales históricos permanecen ligados a su época: no anuncian una exportación reciente de la libreta restaurada.

Un fallo antes del commit revierte filas y metadata. Puede quedar un registro operativo de intento o una protección privada extra; no es un cambio financiero ni éxito de restauración. Una excepción posterior al commit consulta `lastRestoreId` y reconoce el éxito. Tras reinicio, el identificador de intención y el marcador transaccional distinguen commit completado de operación interrumpida. El aviso persiste hasta reconocerlo; se limpian únicamente temporales propios. La aplicación confirmada no se cancela por abandonar la corrutina UI; una muerte de proceso sigue dependiendo de la transacción SQLite y el marcador persistente.

Restaurar dos veces sustituye filas y genera dos épocas, sin duplicarlas.

## Recuperar datos anteriores

El botón se ofrece solo si el localizador privado tiene nombre/directorio propios y el ZIP coincide con un registro `RECUPERACION/VERIFICADO` en identidad, revisión, captura y ubicación. Se valida de nuevo. La selección se copia a un temporal privado antes de generar otra protección: el archivo seleccionado no se pisa ni limpia.

Se usa exactamente `prepareSource` → base temporal → vista previa → `confirm`, sin un importador alternativo. Recuperar también protege el estado que reemplaza cuando tiene datos; permite volver al estado anterior a esa recuperación.

## Pruebas automáticas ejecutadas

Motorola moto g84 5G, Android 15/API 35. Solo bases aleatorias `test-*.db` y directorios `cache/test-restore-*`; ninguna prueba abre la `pagaya.db` de Dev. La suite usa clientes ficticios con Unicode, archivado, fiados, abonos, pago total, anulación y dos correcciones encadenadas.

| Verificación | Resultado |
| --- | --- |
| `testDebugUnitTest` | 105 pruebas, 0 fallos, 0 errores, 0 omitidas |
| `am instrument -w -e class com.example.pagaya.data.backup.RestoreServiceTest ...` | 23 correctas, 11,293 s |
| `am instrument -w com.example.pagaya.dev.test/androidx.test.runner.AndroidJUnitRunner` | Runner: `OK (197 tests)`, 33,795 s: 195 correctas y 2 auxiliares opt-in sin parámetros omitidos por supuesto (perfil de respaldo y compatibilidad histórica) |
| `am instrument -w -e class com.example.pagaya.ui.backup.RestoreViewModelTest ...` (añadidas después de la suite completa) | 4 correctas, 0,805 s: selector cancelado sin error/éxito, archivo inválido, advertencia de copia vacía y nueva confirmación tras cambiar cuentas, preview caducado tras recreación |
| `testDebugUnitTest assembleDebug assembleQa assembleQaRestore assembleDebugAndroidTest lintDebug --no-daemon --console=plain` | BUILD SUCCESSFUL, 159 tareas, 1 min 17 s; 105 JVM correctas |
| Compilación final `assembleDebug assembleQa assembleQaRestore assembleDebugAndroidTest lintDebug --no-daemon --console=plain` tras textos/pruebas UI | BUILD SUCCESSFUL, 153 tareas (29 ejecutadas), 1 min 44 s. Lint: 0 errores y 7 avisos existentes de versiones/target. Se retiraron los 2 avisos nuevos de plurales usando rótulos de conteos |

Los 23 casos nuevos cubren: exportación a otra base vacía y conservación exacta de filas/IDs/metadatos portables; búsqueda regenerada; reemplazo y recuperación por el mismo protocolo; copia vacía con advertencia y deshacer; repetición sin duplicados; nueva época y estado sin respaldo externo heredado; archivo inválido, límite de bytes real y lectura fallida sin cambios; fechas futuras; proveedor alterado después de preview sin relectura; ZIP y staging privados alterados; cambio de revisión o época exige revisar; respaldo pendiente/en escritura impide restaurar; escritura concurrente antigua espera y es rechazada; fallo de protección previa y conservación de copia anterior; rollback en seis puntos del reemplazo; errores reales de restricciones SQLite en filas/metadata; cortes simulados antes/después del commit; excepción después del commit; formulario y comandos de época antigua; limpieza restringida; archivados cuentan como datos y protección corrupta no se ofrece.

Los cortes usan un `Error` determinista y un servicio nuevo sobre la base persistente. **Simulan interrupción; no son muerte real del proceso, corte eléctrico ni ensayo de durabilidad del hardware.** Las excepciones deterministas y de SQLite comprueban rollback y comunicación de resultado. No se usa capturar `OutOfMemoryError`.

Logs locales ignorados: `app/build/iteration09-validation.log`, `iteration09-final-validation.log`, `iteration09-restore-tests.log`, `iteration09-all-instrumented.log`, `iteration09-restore-ui-tests.log`. No contienen filas personales. La pasada completa precedió a las cuatro pruebas UI adicionales; no se presenta como una nueva ejecución completa de 201 casos.

## Ensayo manual entre dos instalaciones aisladas

Antes: `.dev` y `.qa` instaladas; `.qa.restore` ausente; variante normal ausente. Se actualizaron Dev/QA con `install -r` y se instaló QA Restauración nueva. Dev no se abrió ni se exportó/restauró; QA conservó su libreta sintética. No se usaron `connectedDebugAndroidTest`, `pm clear` ni desinstalación de Dev/QA.

1. QA exportó desde la interfaz a **Descargas locales** `backup_2026-10-17_095719_c64645b1-01a0-4e35-bbb5-c35a60421adb.pagaya.zip`. Mostró «Copia guardada y comprobada». El selector recordó una carpeta de Drive y se cambió a Descargas sin confirmar ninguna operación en Drive.
2. QA Restauración empezó sin clientes; Respaldo era accesible. Seleccionó el archivo: preview 3 clientes/6 movimientos/3 revisiones, pendiente $45.000 frente a 0/$0. Confirmó, mostró éxito y volvió a Clientes. No heredó «respaldo reciente»: indicó cambios sin respaldar.
3. Cuenta A restaurada: fiado $60.000, fiado $25.000 anulado, abono $20.000 corregido dos veces y fiado $5.000; pendiente $45.000. La exportación desde esta segunda instalación a `backup_2026-10-17_100034_7a7eaf78-72e9-4656-94a1-219e61f803e2.pagaya.zip` fue verificada. `unzip -p ... data.json | sha256sum` dentro del teléfono dio en ambos archivos `04c85e39feb579927a239258bbc9fcdae2d084e5c3b9c0a60bd036dcb04379f7`: igualdad completa de datos, incluidos archivados, anulados, IDs e historial. No se descargaron ZIP al computador.
4. Solo en QA Restauración se creó un cliente ficticio adicional y fiado $7.000. Nueva comparación: 4/7/3 y $52.000 frente a la copia 3/6/3 y $45.000. Se confirmó restaurar otra vez el archivo original; el cliente adicional desapareció.
5. «Recuperar datos anteriores» mostró 4/7/3 y $52.000. Al confirmar volvió el cliente ficticio con su fiado $7.000; A conserva $45.000. El ensayo deja esta libreta sintética para revisión y conserva sus copias privadas de recuperación.

No se sobrescribieron archivos de la usuaria ni se exportaron cuentas de Dev. Los archivos sintéticos permanecen en Descargas. No se crearon fixtures con datos del teléfono.

Los APK finales se instalaron con `install -r` en `.dev`, `.qa` y `.qa.restore`. Las huellas iniciales y posteriores a pruebas/ensayo de Dev coincidieron, sin leer sus filas: base `9c08c6cdb6b8a73aec5b0853860ec862eca882203ea198b4aad79a6610725855`, WAL `983190f03aa1fd5e710247e1255be8e5dcdb388fe60ae15f53eaab308d8af66d`. La variante normal no se instaló ni se modificó. El APK de pruebas se retira al cierre; se conservan las tres apps y sus datos.

## Límites y pendientes reales

- Drive, sincronización y respaldos automáticos sin implementación. HU-09 no completa.
- API 26, Redmi Note 14 Pro/Android 16 y prueba con la usuaria final pendientes.
- No se ejecutó una muerte real durante commit ni un corte de energía. El ensayo manual sí usa instalaciones separadas reales; los fallos precisos se inyectan en pruebas.
- Las mediciones de [19](19_Respaldo_Archivo.md) son de exportación de iteración 08; no prueban tiempo o pico de esta restauración. No se midió el máximo de restauración ni se promete que toda combinación admisible de textos/filas sea sostenible. Validación inicial y cuenta mayor todavía usan memoria proporcional; staging y protección previa requieren espacio adicional. Si el espacio no alcanza antes del commit, se aborta.
- Retención de copias privadas extra conservadora, sin interfaz de limpieza/rotación; revisar consumo de espacio en una futura tarea autorizada. Los ZIP contienen datos legibles y solo sobreviven mientras existan los datos privados de esa instalación.

Archivos principales: `RestoreService.kt`, `BackupDatabaseTransfer.kt`, `LedgerCoordination.kt`, `RestoreViewModel.kt`, `RestoreSection.kt`, `LedgerSessionViewModel.kt`, coordinación en repositorios/BackupService y tokens de época en navegación/formularios. Pruebas nuevas: `RestoreServiceTest.kt` y `RestoreViewModelTest.kt`. Se añaden variante/recurso `qaRestore` y documentación; no cambia `app/schemas`, catálogo de versiones ni `local.properties` local ignorado.

Estado final: `develop`, HEAD `ca141b3`; 23 archivos versionados modificados y 11 entradas nuevas sin versionar (la carpeta `qaRestore` contiene un recurso). `git diff --check` sin errores; sin cambios de esquema ni herramientas. APK auxiliar `.dev.test` retirado; quedan `.dev`, `.qa` y `.qa.restore`.

Se detiene esta iteración para revisión, sin commit, push ni merge.
