# 08 — Backlog futuro y roadmap

Este documento contiene posibilidades, no compromisos. La numeración orienta conversaciones; no asigna fechas ni obliga a implementar todo. La prioridad se valida con uso real después de una V1 estable.

## Roadmap propuesto

| Versión | Enfoque | Posibles entregables | Condición para abordarla |
| --- | --- | --- | --- |
| V1 | Libreta fiable por cliente | Clientes, fiados/abonos/pago completo, saldo, historial, correcciones, SQLite, backup local y Drive manual mediante el selector Android, restauración | Alcance obligatorio descrito en documentos 00–07 |
| V1.1 | Reducir el esfuerzo de proteger datos | Integración directa con Drive, backup Drive automático, retención automática, recuperación más guiada, mejoras observadas de UX | Restauración manual probada y evidencia de olvidos de respaldo |
| V1.2 | Consultar o sacar información con más comodidad | Exportar CSV/Excel, filtros de fechas/tipo y resumen mensual sencillo | Preguntas recurrentes que el historial no resuelva |
| V2 | Apoyar más operaciones del negocio | Productos, ventas y controles adicionales seleccionados | Descubrimiento específico que justifique superar la libreta de cuentas |

## Candidatos priorizables

| ID | Idea | Beneficio esperado | Dependencias / precaución |
| --- | --- | --- | --- |
| BF-01 | Copia automática Drive | Menos dependencia del hábito diario | Requiere integración directa (OAuth, Google Cloud, firma registrada), WorkManager, permisos, batería, red, deduplicación y retención; no garantizar hora exacta. Fuera de V1 por decisión del 2026-10-17 |
| BF-01b | Retención automática de copias | Menos copias acumuladas | La app no puede borrar con seguridad archivos elegidos en el selector; requiere destino administrado. Fuera de V1 |
| BF-01c | Mensajes específicos para destinos en la nube | Evitar que «comprobada» se entienda como «subida» | Textos generales implementados en rc.3: subida pendiente y nombre nuevo; sin detección del proveedor ni integración directa. Revisar comprensión durante el piloto |
| BF-02 | Restauración guiada | Menos dudas al cambiar de teléfono | Identificación de cuenta y vista previa ya fiables; no inventar fusión |
| BF-03 | UX basada en piloto | Menos toques/errores | Medir acciones reales; errores financieros se corrigen en V1, no se posponen |
| BF-04 | CSV/Excel | Llevar información fuera de la app | Definir columnas, moneda y privacidad; un reporte no sustituye el backup con revisiones |
| BF-05 | Resumen mensual | Entender fiados y abonos del período | Distinguir fecha de negocio y registro; no presentar como contabilidad fiscal |
| BF-06 | Filtros de historial | Encontrar una operación concreta | Conservar claridad de saldo global vs resultados filtrados |
| BF-07 | Cifrado portable del backup | Proteger archivos copiados fuera del teléfono | Contraseña y recuperación comprensibles; no depender solo de la llave del dispositivo |
| BF-08 | Venta con abono inicial en un paso | Registrar una operación común con menos toques | Confirmar demanda; dos movimientos asociados y transacción indivisible |
| BF-09 | Saldo a favor / anticipos | Atender clientes que pagan de más | Cambia reglas centrales, mensajes y modelo; no habilitar solo quitando una validación |
| BF-10 | Devolución o descuento | Explicar reducción de deuda sin dinero recibido | Nuevo significado financiero; no simularlo como abono |
| BF-11 | Importación asistida | Migrar una libreta grande | Formato y conciliación previa; detectar duplicados y fechas sin inventar datos |
| BF-12 | Productos y ventas | Mejor contexto por compra | Descubrimiento independiente; no implica inventario automáticamente |
| BF-13 | Segunda plataforma | Usar iPhone o web si aparece necesidad | Revisar stack/portabilidad y esfuerzo real de soporte |

## Funciones sin prioridad asignada

Inventario, proveedores, facturación electrónica, nómina, bancos, pasarelas, multiusuario, roles, ERP, CRM, BI, IA, WhatsApp y notificaciones de cobro. Permanecen fuera de V1 y no se consideran automáticamente parte de V2.

Sincronizar varios teléfonos requeriría identidad de dispositivos, resolución de conflictos y una estrategia de autoridad; **subir backups a Drive no resuelve ese problema**. No introducirla como una mejora menor.

## Regla para promover una idea

Registrar un problema observado, frecuencia, personas afectadas, beneficio comprobable, costo de mantenimiento y efecto sobre integridad/privacidad. Resolver primero errores y recuperación. Toda ampliación que modifique los movimientos o el formato exige actualizar modelo, historias y pruebas antes de programar.
