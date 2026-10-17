# 02 — Historias de usuario V1

Todas las historias son obligatorias para V1. Se conserva la numeración original. Los casos ejecutables se encuentran en el [plan de pruebas](10_Plan_Pruebas.md).

## HU-01 — Gestionar la cuenta de un cliente

**Como** vendedora, **quiero** consultar la cuenta individual de un cliente, **para** saber cuánto debe y reconocer sus compras y abonos.

1. Dado un cliente existente, al buscarlo por nombre y abrirlo, se muestra su nombre y saldo pendiente antes del historial.
2. Dados fiados por $60.000 y abonos por $15.000, se muestra «Debe: $45.000».
3. Cada movimiento muestra fecha, tipo, valor, nota si existe y saldo resultante recalculado.
4. Los abonos y pagos incluyen ✓, texto y un realce visual; no dependen exclusivamente del color.
5. Después de guardar un movimiento, la cuenta y el listado se actualizan a partir de SQLite, sin recarga manual.
6. Todo el flujo funciona sin Internet y sin una cuenta Google conectada.

## HU-02 — Crear y mantener un cliente

**Como** vendedora, **quiero** registrar y corregir los datos de un cliente, **para** identificar su cuenta.

1. Solo el nombre es obligatorio; se eliminan espacios de los extremos y un nombre vacío se rechaza.
2. Teléfono y nota son opcionales. La fecha de creación se asigna automáticamente.
3. Dos personas pueden tener el mismo nombre o teléfono; se advierte una posible coincidencia, pero no se impone unicidad.
4. Al guardar, se abre la cuenta recién creada con «Sin movimientos» y saldo $0.
5. Se pueden editar nombre, teléfono y nota conservando creación y actualizando modificación.
6. Archivar requiere saldo cero y confirmación; el cliente sigue accesible en «Archivados» y se puede reactivar.
7. No existe borrado físico de clientes desde la interfaz.

## HU-03 — Registrar compra o fiado

**Como** vendedora, **quiero** registrar una compra a crédito, **para** sumar la deuda a la cuenta.

1. «+ Fiado» abre un formulario con cliente ya elegido, fecha de hoy y foco en valor.
2. «Compra a crédito» y «Fiado» usan el mismo tipo interno FIADO; no obligan a escoger dos categorías equivalentes.
3. Importe obligatorio, positivo y entero; descripción opcional. El saldo no es editable.
4. Con saldo $10.000, registrar $25.000 produce $35.000.
5. Una fecha anterior válida inserta el movimiento en su lugar cronológico y recalcula los saldos posteriores.
6. Al fallar el guardado se conserva el formulario y no se muestra éxito.
7. Doble toque o reintento de la misma solicitud produce como máximo un movimiento.

## HU-04 — Registrar un abono

**Como** vendedora, **quiero** registrar un abono, **para** descontar lo que el cliente ya entregó.

1. «+ Abono» muestra el saldo disponible y solicita solo el valor; fecha y nota son secundarias.
2. Con deuda $60.000, abonar $20.000 produce $40.000 y la entrada «✓ Abono −$20.000».
3. Con deuda $40.000, ingresar $45.000 muestra «El abono supera lo que debe: $40.000» y no guarda.
4. No se puede abonar a una cuenta sin deuda.
5. Un abono fechado antes de la deuda que lo cubre se rechaza con explicación sobre la fecha, aunque el saldo actual alcance.
6. Si el abono coincide con la deuda, se permite y deja la cuenta «Pagado ✓»; no es obligatorio usar el atajo de pago completo.

## HU-05 — Registrar pago completo

**Como** vendedora, **quiero** registrar que el cliente terminó de pagar, **para** liquidar la cuenta sin digitar el saldo.

1. La acción aparece cuando el saldo es positivo.
2. Se muestra «Registrar pago de $X» con el importe calculado y fecha de hoy; requiere una confirmación.
3. Al confirmar se relee el saldo dentro de la transacción. Si difiere del valor mostrado, no se guarda hasta confirmar el nuevo importe.
4. Se registra un ABONO marcado como originado en el atajo de pago completo; el saldo queda en cero.
5. La entrada se muestra «✓ Pago». Una nueva compra posterior puede volver a generar deuda; el pago anterior conserva su significado histórico.
6. Si se edita el importe de ese pago, pasa a mostrarse «✓ Abono»; la revisión conserva que originalmente se registró como pago completo.

## HU-06 — Consultar clientes

**Como** vendedora, **quiero** ver y buscar mis clientes, **para** encontrar una cuenta rápidamente.

1. Lista alfabética de activos con saldo e identificación suficiente para distinguir homónimos.
2. «maria» encuentra «María», y mayúsculas/minúsculas no cambian el resultado.
3. Un cliente con saldo positivo muestra «Debe: $X»; con cero e historia vigente, «Pagado ✓»; sin historia vigente, «Sin movimientos».
4. Hay estados claros para libreta vacía, búsqueda sin resultados y error de lectura.
5. No se muestra un falso $0 mientras se carga un saldo desconocido.
6. «Archivados» es una vista secundaria; no se mezclan clientes ocultos con los activos por defecto.

## HU-07 — Consultar historial

**Como** vendedora, **quiero** consultar los movimientos de una cuenta, **para** explicar cómo se llegó al saldo.

1. El historial se presenta del más reciente al más antiguo, agrupado por día. El cálculo se realiza en sentido ascendente.
2. Empates en el día se resuelven por orden de registro estable, no por el texto de la descripción ni por el tipo.
3. Cada fila muestra fecha, tipo, importe y saldo después de ese movimiento según los registros vigentes.
4. «Editado» permite consultar valores anteriores, motivo y fecha de cambio.
5. Un movimiento anulado permanece identificado como «Anulado · no afecta el saldo» y conserva su importe original como referencia.
6. No se atribuye un abono a un producto específico: los pagos disminuyen la cuenta total.

## HU-08 — Corregir movimientos con trazabilidad

**Como** vendedora, **quiero** corregir errores, **para** mantener cuentas exactas sin perder lo que se registró antes.

1. Desde el detalle de un movimiento vigente se pueden editar valor, fecha y descripción.
2. Se requiere un motivo breve; antes de confirmar se muestran valores anteriores, nuevos y efecto sobre el saldo.
3. La versión anterior y la corrección se guardan en una transacción; nunca queda una sin la otra.
4. Las correcciones que producen un saldo negativo en cualquier punto del historial se bloquean.
5. Anular exige motivo y confirmación contextual; conserva el registro y una revisión.
6. Cliente, tipo, ID, orden original y fecha de creación no se editan.
7. Una anulación accidental se resuelve registrando nuevamente la operación con nota de referencia; no se borra la anulación anterior.
8. Si dos pantallas intentan editar la misma versión, la segunda debe recargar y revisar el cambio antes de guardar.

## HU-09 — Respaldar y recuperar las cuentas

**Como** vendedora, **quiero** guardar y recuperar una copia de mis cuentas, **para** no perder la libreta al cambiar de teléfono.

1. «Guardar copia en archivo» funciona sin Internet al elegir almacenamiento local.
2. La misma acción permite elegir una carpeta de Google Drive en el selector Android, sin permisos propios de la app; cancelar no afecta las cuentas. (Actualizado 2026-10-17: Drive mediante el selector.)
3. La aplicación muestra la última copia verificada y si hay cambios posteriores sin respaldar. No distingue local de Drive; la copia en la nube se confirma en Drive.
4. Las copias incluyen clientes archivados, movimientos anulados y revisiones, no solo los saldos visibles.
5. La nueva copia se valida antes de anunciar éxito. Sin retención automática en V1: la app no borra copias.
6. Restaurar permite elegir un archivo local o de Drive en el selector, conocer fecha y cantidades, y confirmar que reemplazará la libreta actual.
7. Un archivo corrupto, incompatible o inconsistente se rechaza antes de cambiar datos vigentes.
8. Antes de sustituir una libreta existente se conserva una copia de recuperación local; si falla esa protección, se cancela la restauración.
9. Una instalación nueva recupera los mismos datos desde un archivo local o desde Drive de la misma cuenta, elegido en el selector.
10. Perder la red o cerrar la app durante una exportación no impide continuar el trabajo local ni convierte un intento fallido en éxito. Sin red, Drive puede aceptar el archivo y subirlo después: la usuaria lo confirma en Drive.
