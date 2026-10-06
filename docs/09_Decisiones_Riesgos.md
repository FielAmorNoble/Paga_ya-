# 09 — Decisiones, supuestos pendientes y riesgos

Estado general: decisiones propuestas para iniciar desarrollo, sustentadas en el alcance del 5 de octubre de 2026. No representan validaciones de hardware, autorización OAuth o usabilidad ya realizadas.

Se usa un registro único de decisiones para evitar diez archivos ADR diminutos. Si una decisión cambia, anotar fecha, motivo y documentos afectados; no dejar dos alternativas contradictorias como vigentes.

## Registro de decisiones

| ID | Decisión | Motivo, alternativa descartada y consecuencia |
| --- | --- | --- |
| DT-01 | Kotlin + Compose + Room | Ajuste a único Android, persistencia y APIs directas; se renuncia a compartir UI con web/iOS. Revisar si hay experiencia previa determinante. |
| DT-02 | App instalada, no PWA | Reducir dependencia de almacenamiento/origen y APIs del navegador para la única libreta operativa; requiere APK/firma y distribución Android. |
| DT-03 | SQLite privado mediante Room | Datos estructurados, transacciones y migraciones; se evita JSON como base operativa y almacenamiento en caché. |
| DT-04 | Saldo derivado | Una única fórmula reproducible desde movimientos vigentes; se evita saldo manual/caché persistente que pueda divergir. |
| DT-05 | Corrección con revisión y anulación lógica | Conservar valores anteriores e intención del cambio; más que dos timestamps, menos que un sistema completo de eventos. |
| DT-06 | Backup lógico JSON + manifiesto dentro de ZIP | Portabilidad e integridad sobre bytes definidos; se evita copiar `.db` abierto y mantener dos formatos en V1. |
| DT-07 | Drive manual en V1, appDataFolder | Copia externa sencilla y permiso acotado; espacio oculto exige recuperación desde app y exportación independiente. |
| DT-08 | Restauración completa validada y transaccional | Evitar duplicados y mezclas; preview y copia previa cubren errores humanos. No hay fusión ni sincronización. |
| DT-09 | Un módulo y paquetes por responsabilidad | Proyecto mantenible por una persona; evita módulos, interfaces y herramientas sin beneficio inmediato. |
| DT-10 | MVVM pequeño + repositorio + reglas puras | La UI no escribe SQL ni define otra fórmula; transacciones y transporte permanecen comprobables. |
| DT-11 | FIADO/ABONO como tipos, origen de pago completo | Compra a crédito y fiado son equivalentes; PAGO es un atajo de abono, no otro efecto financiero. |
| DT-12 | COP entero y sin saldos a favor | Ajuste a operación descrita, elimina redondeo y ambigüedad; anticipos requerirían un cambio explícito. |
| DT-13 | Prefijos cronológicos no negativos | Una corrección no puede asignar pagos a deuda futura; puede exigir corregir primero abonos posteriores. |
| DT-14 | Siete backups regulares verificados + recuperación previa separada | Mitiga errores recientes y evita sobrescribir la única copia; no es archivo histórico ilimitado. |
| DT-15 | No cifrado propio en V1 | Evitar claves irrecuperables y complejidad prematura; el archivo exportado es legible y necesita destino privado. |

Detalles y referencias: [arquitectura](04_Arquitectura.md), [modelo](03_Modelo_Datos.md), [backup](05_Estrategia_Offline_Backup.md) y [fuentes](11_Fuentes_Tecnicas.md).

## Validaciones pendientes antes del desarrollo

| ID | Dato desconocido | Supuesto de trabajo | Responsable / momento |
| --- | --- | --- | --- |
| VP-01 | Modelo/Android/espacio del teléfono | API 26+, 2 GB RAM, servicios Google disponibles | Desarrollador y usuaria, fase 0 |
| VP-02 | Experiencia del mantenedor | No hay preferencia tecnológica declarada | Desarrollador, antes de crear proyecto |
| VP-03 | Importe máximo y significado de ventas de contado | Máximo preventivo por movimiento y contado fuera de libreta | Usuaria, fase 0 |
| VP-04 | Cuenta Google y custodia de acceso | Cuenta de la persona responsable del negocio | Propietario, fase 0/6 |
| VP-05 | Identidad de paquete y firma | Definitivas antes de registrar OAuth release | Desarrollador/propietario, fase 0 |
| VP-06 | Requisitos vigentes OAuth/distribución | Verificar con firma y cuenta destinataria, sin prometer exenciones | Desarrollador, fase 0/8 |
| VP-07 | Exactitud de deuda inicial | Conciliación contra libreta con fecha de corte | Usuaria, fase 8 |
| VP-08 | Custodia de exportaciones y frecuencia | Copia al cierre de jornada con cambios | Usuaria, piloto y entrega |

Los supuestos permiten cerrar el análisis sin bloquearlo. Una validación pendiente no equivale a aceptación del usuario ni a una prueba superada.

## Registro de riesgos

Evaluación cualitativa inicial; revisar al terminar cada fase relevante.

| ID | Riesgo / impacto | Medida proporcionada | Residuo y prueba |
| --- | --- | --- | --- |
| R-01 | Registrar un importe o cliente equivocado / deuda errónea | Nombre visible, formato COP, revisión antes de corrección y trazabilidad | La app no conoce el hecho real; conciliación con usuaria |
| R-02 | Cero, negativos, fracciones, desbordamiento / cálculo incorrecto | Parseo estricto, Long, límites y validación transaccional | T-07, T-08, T-16 |
| R-03 | Abono excesivo o retroactivo / saldo inválido | Validar saldo actual y todos los prefijos | T-08, T-11, T-12 |
| R-04 | Doble toque o reintento / duplicado | ID estable, unicidad y botón ocupado | No inferir duplicados por importe; T-10 |
| R-05 | Edición o anulación accidental / historia perdida | Confirmación contextual, motivo, versión anterior inmutable y borrado lógico | No es auditoría antifraude; T-12 a T-15 |
| R-06 | Cierre, disco lleno o migración defectuosa / pérdida de registros | Commit atómico, errores visibles, migración no destructiva y pruebas | Daño físico sigue posible; T-17 a T-20 |
| R-07 | Archivo truncado o manipulado / restauración dañina | Hash, límites, formato, relaciones, secuencias y BD temporal | Hash no verifica autor; T-24 a T-28 |
| R-08 | Restaurar copia antigua/equivocada / perder trabajo reciente | Preview, confirmación, copia previa y reemplazo transaccional | Solo recuperar si había copia; T-29 a T-31 |
| R-09 | Pérdida/robo del teléfono / pérdida total local | Copia externa Drive y exportación independiente; bloqueo del dispositivo | Se pierden cambios desde última captura; T-35 |
| R-10 | Cuenta Google inaccesible, permisos revocados o identidad app cambiada | Acceso del propietario, firma/proyecto conservados y archivo portable | Drive solo puede recuperarse si existe acceso; T-32 a T-35 |
| R-11 | Copias equivocadas desplazan una válida | Siete capturas, no sobrescribir, verificar antes de limpiar y copia previa protegida | Error antiguo puede superar retención; T-34 |
| R-12 | Exportación expone datos | Mensaje de privacidad, selector de destino, sin compartir automáticamente | ZIP no cifra; T-37 |
| R-13 | Backup Android no previsto / subida sin claridad | Configuración explícita de exclusiones y validación de fabricante | No confiar solo en allowBackup; T-37 |
| R-14 | Dos teléfonos editan / historias divergentes | Política de un dispositivo operativo y restauración por reemplazo | No se pueden fusionar versiones en V1; T-36 |
| R-15 | Dependencia o distribución cambian | Versiones fijadas, catálogo, firma respaldada y revisión periódica | Requiere mantenimiento; fase 8 y T-20 |
| R-16 | Proyecto crece antes de consolidarse | Alcance cerrado, hitos y backlog separado | Cada extra necesita beneficio probado |
| R-17 | Reloj incorrecto / fechas y orden confusos | Día de negocio explícito, orden estable, avisos y reloj inyectable | Hora de auditoría no certificada; T-11 |
| R-18 | La usuaria sigue olvidando registros o backups | Menos campos, acciones visibles y rutina de cierre | La app no puede respaldar movimientos no ingresados; piloto |

## Modelo de seguridad proporcionado

Proteger frente a errores cotidianos, pérdida del teléfono, fallos de escritura y archivos dañados. Reducir exposición con almacenamiento privado, autorización puntual y ausencia de telemetría. No se promete resistencia a dispositivo rooteado, una persona con acceso al teléfono desbloqueado, archivos modificados intencionalmente o pérdida de todas las credenciales/copias.

No hace falta agregar roles, servidor o firma criptográfica para resolver el caso descrito. Si aparece una necesidad de control entre varias personas o prueba legal de pagos, deberá analizarse como un cambio de producto; el historial local de V1 no certifica esos hechos.
