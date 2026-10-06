# Paga Ya!

Una libreta digital de cuentas por cliente para un pequeño emprendimiento familiar en Colombia.

**Estado: análisis y planificación inicial, 5 de octubre de 2026.** Todavía no hay aplicación, proyecto Gradle ni funcionalidades implementadas. Los criterios de prueba son compromisos para el desarrollo, no resultados de pruebas ejecutadas.

La V1 permitirá registrar fiados, abonos y pagos, consultar cuánto debe cada cliente y recuperar la información desde un respaldo. Funcionará sin Internet; Google Drive se utilizará únicamente para copias autorizadas.

## Decisiones de partida

- Android instalado, Kotlin, Jetpack Compose y Room sobre SQLite; un solo módulo y sin servidor propio.
- Dinero en pesos colombianos enteros; el saldo se obtiene de los movimientos.
- Compra a crédito y fiado representan el mismo movimiento. Un pago completo es un abono por el saldo vigente.
- Correcciones con versiones anteriores conservadas; anulación lógica, sin borrado financiero silencioso.
- Backup lógico en JSON, empaquetado con un manifiesto de integridad; exportación local y Google Drive manual dentro de V1.
- Restauración validada y transaccional de toda la libreta; sin mezcla ni sincronización entre teléfonos.

Estas decisiones son la propuesta técnica del proyecto. Sus supuestos, límites y condiciones de revisión están en los documentos siguientes.

## Documentación

| Documento | Contenido |
| --- | --- |
| [00 — Visión](docs/00_Vision_Proyecto.md) | Problema, alcance, supuestos y medidas de éxito |
| [01 — Requerimientos](docs/01_Requerimientos_Funcionales.md) | Funciones, reglas de negocio y requisitos de calidad |
| [02 — Historias de usuario](docs/02_Historias_Usuario.md) | HU-01 a HU-09 y criterios de aceptación |
| [03 — Modelo de datos](docs/03_Modelo_Datos.md) | Entidades, restricciones, cálculo y trazabilidad |
| [04 — Arquitectura](docs/04_Arquitectura.md) | Comparación tecnológica, stack y estructura futura |
| [05 — Offline y backup](docs/05_Estrategia_Offline_Backup.md) | Formato, Drive, retención y recuperación |
| [06 — UX y flujos](docs/06_UX_Flujos.md) | Wireframes, lenguaje, accesibilidad y errores |
| [07 — Plan de implementación V1](docs/07_Plan_Implementacion_V1.md) | Fases 0–8, tareas, dependencias y condiciones de entrega |
| [08 — Backlog futuro](docs/08_Backlog_Futuro.md) | Roadmap opcional y criterios de priorización |
| [09 — Decisiones y riesgos](docs/09_Decisiones_Riesgos.md) | Registro de decisiones, riesgos y validaciones pendientes |
| [10 — Plan de pruebas](docs/10_Plan_Pruebas.md) | Casos verificables y cobertura de historias |
| [11 — Fuentes técnicas](docs/11_Fuentes_Tecnicas.md) | Referencias oficiales y alcance de la investigación |

Se conservan los nueve documentos solicitados. Se agregan tres anexos para que decisiones, pruebas y fuentes puedan mantenerse sin sobrecargar el plan de implementación. Este índice es el punto de entrada; cada regla tiene un documento responsable.

## Cómo comenzar la siguiente iteración

1. Ejecutar las verificaciones de la fase 0: teléfono real, experiencia del desarrollador, instalación y acceso a Drive con firma de distribución.
2. Crear el proyecto Android siguiendo la estructura de arquitectura y fijar las versiones compatibles verificadas ese día.
3. Construir por fases y registrar evidencia de sus criterios de salida.

Los primeros datos de prueba deben ser ficticios. Nunca incorporar cuentas reales, respaldos, tokens o llaves de firma al repositorio. La licencia de distribución del código queda pendiente de decisión del propietario; no se asigna una licencia por defecto.
