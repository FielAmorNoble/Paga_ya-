# 00 — Visión del proyecto

Fecha de referencia: 2026-10-05. Estado: propuesta técnica para iniciar V1.

## Propósito

Paga Ya! reemplaza la libreta de cuentas por cliente por una herramienta igual de sencilla, con cálculo automático, historial claro y recuperación de datos. Su usuaria principal es una vendedora de un emprendimiento familiar que necesita anotar una operación mientras atiende o conversa con un cliente.

El problema principal es olvidar o perder registros y tener que reconstruirlos desde conversaciones. La aplicación reduce el esfuerzo de registrar y consultar; no puede reconocer una operación que nunca se ingresó ni reconstruir automáticamente WhatsApp.

## Resultado deseado

Al abrir una cuenta se entiende cuánto debe el cliente. Registrar un fiado aumenta la deuda; registrar un abono la disminuye. Los pagos tienen una marca visual tan reconocible como el subrayado del cuaderno. Los datos confirmados permanecen después de cerrar o reiniciar la aplicación y pueden restaurarse en otro teléfono desde una copia válida.

## Alcance cerrado de V1

Clientes con nombre, teléfono y nota; búsqueda por nombre; cuenta individual; fiados/compras a crédito; abonos; pago completo; historial y saldo derivado; edición con trazabilidad; anulación controlada; SQLite local; exportación e importación de respaldo; respaldo y recuperación manuales con Google Drive; fecha de última copia verificada.

Drive manual forma parte de la entrega V1 **mediante el selector de archivos de Android** (decisión del propietario del 2026-10-17, [21](21_Firma_Distribucion.md)): la usuaria guarda y abre la copia en una carpeta privada de su Drive, y la cuenta y la subida las gestiona la app Drive. Sin OAuth propio, Google Cloud, cuenta de servicio ni appDataFolder. Integración directa con la API, respaldo automático y retención automática quedan fuera de V1. Drive no será requisito para abrir la aplicación ni registrar operaciones. Validación con APK firmado en [22](22_Validacion_Release_Drive.md).

## Fuera de V1

Inventario, catálogo estructurado, proveedores, facturación electrónica, nómina, bancos, pasarelas, usuarios empresariales, roles, ERP, CRM, BI, IA, WhatsApp, cobros automáticos, reportes contables avanzados y cartera empresarial. Tampoco se incluyen sincronización de dispositivos, cuentas a favor, anticipos, devoluciones como operación independiente ni un libro de caja del negocio.

Las ventas de contado sin deuda quedan fuera del registro de cuentas. «Ingreso» y «egreso» del cuaderno no se trasladan literalmente: en esta app un fiado aumenta lo que el cliente debe, aunque no entre dinero a caja. Para consultar movimientos se emplean «Fiado», «Abono», «Pago» y «Saldo».

## Supuestos y ambigüedades resueltas

| Tema | Supuesto adoptado | Consecuencia o validación |
| --- | --- | --- |
| Dispositivo | Un teléfono Android con almacenamiento interno y servicios de Google | Confirmar modelo, versión y espacio en fase 0; offline no depende de Google |
| Experiencia técnica | No se informó experiencia previa ni código reutilizable | Se elige stack por ajuste al problema; revisar si el único mantenedor domina otra alternativa |
| Operación | Una persona y una libreta activa en un solo teléfono | Otro teléfono se usa para reemplazo, no para registrar en paralelo |
| Moneda | COP sin fracciones, intereses ni conversión | Enteros en todos los cálculos |
| Compra y fiado | Ambos significan compra a crédito | Un solo tipo financiero, visible como «Fiado» |
| Pago completo | Es un abono cuyo importe se toma del saldo actual | Atajo, no una tercera fórmula |
| Exceso de abono | No se manejan saldos a favor | Se bloquea, nunca se recorta el valor silenciosamente |
| Fecha | Día del negocio en America/Bogota | Editable al pasado; no registrar operaciones futuras en V1 |
| Correcciones | Conservar el valor anterior y un motivo breve | Revisión histórica disponible en el detalle |
| Archivo de clientes | Ocultar clientes sin deuda conservando sus datos | No archivar si tienen saldo pendiente |
| Restauración | Reemplazo de toda la libreta con confirmación | No sumar archivos ni mezclar cuentas |
| Privacidad | Teléfono protegido por bloqueo del sistema | Cifrado adicional con contraseña se evalúa fuera de V1 |

No se necesita resolver estos supuestos para entregar esta documentación. Antes de desarrollar, validar los que afectan instalación, stack y operación real, sin convertirlos en un cuestionario de diseño.

## Incorporación de la libreta actual

No habrá importación masiva en V1. Elegir una fecha de corte con la vendedora. Crear cada cliente y, si existe deuda, registrar un fiado con descripción «Saldo inicial de la libreta al DD/MM/AAAA». Este es un movimiento consolidado, no una afirmación de que todos los productos se vendieron ese día. Confirmar el importe contra el cuaderno antes de usarlo.

Si se dispone de todo el historial, se puede transcribir en orden. No ingresar simultáneamente un saldo inicial y las operaciones que ya lo componen. Una compra con abono inmediato se registra por su valor total y después su abono; no se introduce un nuevo formulario de ventas mixtas.

## Éxito verificable

- Desde la cuenta abierta, registrar un movimiento habitual en menos de 10 segundos en al menos 8 de 10 intentos de la usuaria, después de una explicación breve. Buscar cliente y escribir notas extensas se mide aparte.
- La usuaria distingue fiado y abono sin explicación y puede decir el saldo correcto de tres cuentas de ejemplo.
- Ninguna operación guardada se pierde al cerrar o reiniciar durante las pruebas definidas.
- Exportar, reinstalar en un entorno de prueba y restaurar produce los mismos clientes, movimientos, revisiones y saldos.
- Se puede registrar, editar y consultar en modo avión sin bloqueos por conexión o inicio de sesión.

Estos son objetivos de aceptación, no mediciones realizadas. El [plan de pruebas](10_Plan_Pruebas.md) define cómo comprobarlos.

## Orden de prioridades

Integridad de datos, rapidez de registro, claridad del saldo, recuperación y facilidad de mantenimiento. Ante una función adicional que compita con ellas, mantener pequeño el alcance de V1 y registrarla en el [backlog](08_Backlog_Futuro.md).
