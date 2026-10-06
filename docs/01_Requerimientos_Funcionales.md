# 01 — Requerimientos funcionales y de calidad

Estado: especificación V1. Las reglas de dinero y fechas se detallan en el [modelo](03_Modelo_Datos.md); el contrato de respaldo se define en [offline y backup](05_Estrategia_Offline_Backup.md).

## Funciones

| ID | Requerimiento obligatorio | Evidencia de aceptación |
| --- | --- | --- |
| RF-01 | Crear y editar cliente: nombre obligatorio, teléfono/nota opcionales, creación automática | Guardado válido; nombre vacío rechazado |
| RF-02 | Listar clientes activos y buscar por nombre, sin distinguir mayúsculas o acentos | «maria» encuentra «María» sin conexión |
| RF-03 | Mostrar saldo de cada cliente y abrir su cuenta | Saldo derivado e inmediato; cero muestra «Pagado ✓» si hay historia vigente |
| RF-04 | Registrar fiado o compra a crédito con importe, fecha y descripción opcional | Suma al saldo; se conserva el registro confirmado |
| RF-05 | Registrar abono con importe, fecha y nota opcional | Resta al saldo; marca ✓ y texto «Abono» |
| RF-06 | Pagar saldo completo desde la cuenta | Importe calculado y confirmado; saldo cero al guardar |
| RF-07 | Mostrar historial por fecha y saldo resultante recalculado | Orden determinista y distinción visual de entradas |
| RF-08 | Corregir un movimiento, guardar motivo y conservar su versión anterior | Revisión y movimiento se escriben juntos |
| RF-09 | Anular un movimiento con confirmación y motivo, sin borrarlo físicamente | Visible como anulado; deja de afectar saldo |
| RF-10 | Archivar/reactivar cliente sin eliminarlo | Solo archivar con saldo cero; acceso a archivados |
| RF-11 | Generar y exportar respaldo completo sin Internet a un destino local | Archivo cerrado y validado; fecha de exportación separada de Drive |
| RF-12 | Conectar Drive voluntariamente, subir respaldo y listar copias recuperables | Fallar o cancelar no altera cuentas locales |
| RF-13 | Mostrar fecha/hora del último respaldo verificado y su destino | No confundir intento fallido, copia local y copia remota |
| RF-14 | Restaurar archivo local o copia Drive validada | Vista previa, confirmación y reemplazo atómico |
| RF-15 | Conservar hasta siete copias regulares válidas por libreta y destino administrado | Primero verificar nueva copia; luego depurar las más antiguas elegibles |
| RF-16 | Informar errores en lenguaje cotidiano y permitir reintento seguro | Ningún éxito antes del commit ni de la validación remota |

«CRUD de clientes» significa crear, consultar, editar y archivar/reactivar. No significa permitir borrar una cuenta con historia financiera.

## Reglas de negocio

| ID | Regla |
| --- | --- |
| RN-01 | Saldo = suma de fiados vigentes − suma de abonos vigentes. Cero es un estado calculado. |
| RN-02 | Solo importes enteros positivos en COP. Cero, negativos, fracciones y entradas ambiguas se rechazan. |
| RN-03 | Cada importe admite de 1 a 999.999.999 COP; es un límite preventivo propuesto, revisable con evidencia. |
| RN-04 | No se permite un saldo negativo ni en el resultado actual ni en los prefijos del historial por fecha. |
| RN-05 | Un abono debe caber tanto en el saldo actual como en la secuencia afectada por su fecha. |
| RN-06 | Pagar saldo completo lee y valida el saldo dentro de la misma transacción de guardado. Si cambió desde la confirmación, se muestra el nuevo valor y se pide confirmar otra vez. |
| RN-07 | Una operación tiene ID estable durante un guardado/reintento. Doble toque no crea dos movimientos. |
| RN-08 | Las correcciones y anulaciones recalculan el historial; se rechazan si producen algún prefijo negativo. |
| RN-09 | El cliente de un movimiento y su tipo no cambian al editar. Corregir asignación/tipo mediante anulación y nuevo registro, sujetos a validación. |
| RN-10 | No se archiva un cliente con deuda. La anulación no desaparece del historial; no se reactiva un movimiento anulado en V1. |
| RN-11 | La fecha de negocio puede retroceder hasta 1900-01-01 y no superar el día actual en Bogotá. La fecha de registro es automática. |
| RN-12 | La restauración reemplaza todo; no fusiona, no duplica y no compara teléfonos para sincronizar. |
| RN-13 | Reintentar Drive reutiliza el mismo identificador de backup. Nunca sobrescribe la última copia válida. |
| RN-14 | No crear movimientos de ajuste automáticos para ocultar inconsistencias. Pedir revisar los registros implicados. |

Ejemplo de RN-08: si hubo un fiado de $60.000 y un abono de $40.000, no es válido reducir el fiado a $30.000. La pantalla explica qué abono quedaría sin deuda que cubrir. La usuaria puede revisar y corregir primero ese abono; la aplicación no inventa la corrección.

## Requerimientos no funcionales

| ID | Compromiso | Criterio medible o comprobable |
| --- | --- | --- |
| RNF-01 Offline | CRUD, saldo, historial y backup a archivo local funcionan sin red | Flujo completo en modo avión después de instalar |
| RNF-02 Rendimiento | Sin consultas ni archivos en el hilo principal | En equipo de referencia: guardado p95 < 500 ms, búsqueda/detalle p95 < 300 ms y arranque frío p95 < 3 s |
| RNF-03 Persistencia | SQLite en almacenamiento privado persistente | Cierre, force-stop y reinicio conservan commits |
| RNF-04 Integridad | Transacciones, restricciones y migraciones no destructivas | Fallo inyectado deja estado anterior o nuevo completo |
| RNF-05 Usabilidad | Botones grandes, foco en valor, campos secundarios opcionales | Prueba de 10 segundos de visión; TalkBack y texto ampliado |
| RNF-06 Mantenibilidad | Un módulo, responsabilidades claras, versiones fijadas | Compilación reproducible y pruebas documentadas en equipo limpio |
| RNF-07 Costos | Sin servidor contratado para operar | Revisar costos de distribución y cuotas de Drive antes de entregar; no prometer gratuidad perpetua |
| RNF-08 Privacidad | Datos locales y respaldo externo voluntario | Sin analítica ni logs con nombres, teléfonos, montos o tokens |

Carga de referencia inicial: 1.000 clientes, 20.000 movimientos y 5.000 revisiones. Equipo objetivo provisional: Android 8 o superior y 2 GB de RAM; confirmar en fase 0 y ajustar dependencias/minSdk al teléfono real. Los tiempos son metas de proyecto por medir en compilación release, no garantías del stack. Para backup y restauración, meta inicial < 30 segundos locales con esa carga; Drive depende de la red y siempre muestra progreso/cancelación segura.

## Límites de aceptación

- «Guardado» implica transacción finalizada; el formulario se conserva si falla.
- «Respaldado en Drive» implica subida y descarga de verificación correctas.
- «Copia exportada» implica destino escrito y releído; un selector de archivos puede apuntar a un proveedor cloud que requiera conexión. Solo un destino realmente local garantiza exportación offline.
- «Restaurado» implica datos íntegros, referencias válidas y saldos recalculados.
- «Pagado ✓» describe una cuenta con saldo cero e historia vigente, no cada compra individual. Una cuenta sin movimientos vigentes muestra «Sin movimientos».
- La aplicación no detecta si un dato escrito por la usuaria corresponde a la realidad; conserva su trazabilidad.
