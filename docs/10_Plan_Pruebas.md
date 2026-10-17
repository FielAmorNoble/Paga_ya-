# 10 — Plan de pruebas y criterios de entrega

**Estado: casos definidos, no ejecutados sobre una aplicación.** En esta iteración solo se revisa la coherencia documental. Durante implementación registrar fecha, versión, dispositivo, resultado y evidencia por caso.

## Estrategia

- Pruebas unitarias para parseo de COP, signos, orden cronológico, prefijos, límites y validadores de backup.
- Pruebas con Room real en Android para transacciones, restricciones, observación y migraciones; no dar por válida SQLite solo por mocks.
- Pruebas instrumentadas de los recorridos principales y pruebas manuales para instalación, Google real, accesibilidad y comprensión.
- Fallos inyectados en escritura, exportación, subida y restauración para comprobar rollback/idempotencia.
- Un dispositivo modesto real y emuladores de API mínima/reciente; compilación release para rendimiento. No usar datos de clientes en pruebas automatizadas.

No se exige un porcentaje de cobertura arbitrario. Se exige cubrir todos los puntos donde puede cambiar deuda o perderse una copia.

## Dataset base sintético

María: fiado 1 oct 2026 por $60.000, abono 3 oct por $15.000, abono 5 oct por $20.000 y pago completo el 5 oct por $25.000. Resultado $0. Antes del último pago: $25.000. Claudia: sin movimientos. Ana: fiado $83.000. El reloj de prueba es posterior o igual a las fechas usadas; usar reloj fijo, no depender del día en que se ejecuta la suite.

Añadir cliente archivado sin deuda, movimiento anulado, movimiento editado dos veces y nombres con acentos/homónimos. Dataset de carga separado: 1.000 clientes, 20.000 movimientos y 5.000 revisiones con secuencias válidas.

## Casos mínimos

| ID | Acción / condición | Resultado esperado | Nivel / criticidad |
| --- | --- | --- | --- |
| T-01 | Crear cliente con nombre y opcionales vacíos | ID y creación automática, saldo 0, «Sin movimientos» | Room + UI / alta |
| T-02 | Nombre vacío, espacios, demasiado largo; teléfono opcional | Rechazar inválidos sin fila parcial; no exigir teléfono | Unidad + UI / alta |
| T-03 | Buscar maria, MARÍA, homónimos, `%` y `_` literales | Resultados correctos sin inyección ni coincidencias comodín accidentales | Room / alta |
| T-04 | Editar, archivar y reactivar; archivar con deuda | Conservar historia; rechazar archivo con saldo positivo | Room + UI / alta |
| T-05 | Registrar fiado $60.000 | Saldo +$60.000; COMPRA no genera otro tipo/fórmula | Unidad + Room / crítica |
| T-06 | Dos abonos $15.000 y $20.000 | Saldos $45.000 y $25.000; ✓ y signo correctos | Unidad + UI / crítica |
| T-07 | Valores 0, −1, 20,5, 20.000,50, texto, exponente y límite excedido | Rechazar; aceptar 20000 y agrupación COP válida inequívoca | Unidad / crítica |
| T-08 | Abono mayor al saldo o sin deuda | No cambia BD; mensaje con deuda actual | Unidad + Room / crítica |
| T-09 | Pago completo y saldo cambiado tras mostrar confirmación | Saldo 0 si coincide; si cambió, reconfirmar sin cargo distinto implícito | Room + UI / crítica |
| T-10 | Doble toque, reintento mismo UUID, mismo UUID distinto contenido | Una sola operación; conflicto explícito para contenido diferente | Room / crítica |
| T-11 | Fechas pasadas/futuras, mismo día, cambio de reloj y abono antes de deuda | Orden estable, rechazo de futuro/prefijo negativo; no alterar fechas automáticamente | Unidad + Room / crítica |
| T-12 | Editar importe/fecha con motivo; reducir deuda bajo abonos ya hechos | Guardar revisión válida o rechazar todo si algún prefijo queda negativo | Room / crítica |
| T-13 | Anular abono, anular fiado válido e inválido | Saldo recalculado, fila visible y revisión; rollback en caso inválido | Room + UI / crítica |
| T-14 | Dos ediciones con misma versión; dos revisiones posteriores | Segunda escritura obsoleta rechazada; cadena continua sin revisiones perdidas | Room / crítica |
| T-15 | Editar importe de pago completo | Mostrar Abono; revisión conserva origen/valor anterior | Room + UI / alta |
| T-16 | Historial reordenado, muchas operaciones y límites de enteros | Agregado y acumulado coinciden; overflow rechazado | Unidad + Room / crítica |
| T-17 | Cerrar, force-stop y reiniciar tras éxito | Datos confirmados persisten, sin duplicados ni reinicialización | Dispositivo / crítica |
| T-18 | Cortar escritura entre revisión y actualización; simular disco lleno | Estado anterior completo o nuevo completo; formulario no anuncia éxito falso | Instrumentada / crítica |
| T-19 | Modo avión: crear/buscar/fiar/abonar/editar/exportar/restaurar local | Todos los recorridos locales funcionan sin Google ni red | Dispositivo / crítica |
| T-20 | Actualizar release con datos y migrar; migración que falla | Se conserva historia; fallo nunca recrea BD vacía | Migración + instalación / crítica |
| T-21 | Fuente 200 %, TalkBack, teclado, pantalla pequeña y valores largos | Acciones accesibles, lectura correcta, sin importes cortados | Manual + UI / alta |
| T-22 | Diez movimientos de usuaria y dataset de carga en release | Cumplir metas documentadas o registrar/solucionar causa | Manual + medición / alta |
| T-23 | Exportar y restaurar dataset completo | Mismos datos de negocio, IDs, revisiones, activos/anulados y saldos; metadata operativa local reinicializada | Integración / crítica |
| T-24 | Alterar byte, truncar ZIP, hash incorrecto | Rechazo antes de cambiar BD actual | Unidad + integración / crítica |
| T-25 | Backup futuro, COP distinto, tipos desconocidos, claves duplicadas o campos obligatorios ausentes | Error explícito de incompatibilidad/validez, sin importación parcial | Unidad / crítica |
| T-26 | FK huérfana, IDs/órdenes duplicados, versión de auditoría rota, saldo negativo | Rechazo semántico aunque checksum sea correcto | Integración / crítica |
| T-27 | ZIP enorme/bomba, rutas ../, entradas extra/repetidas y JSON demasiado profundo | Lectura acotada y rechazo, sin extraer rutas ni agotar memoria | Unidad + dispositivo / crítica |
| T-28 | Cancelar selector, permiso revocado o fallo de releer exportación | No marcar exportado ni sustituir último éxito | Dispositivo / alta |
| T-29 | Restaurar con datos existentes y fallo en copia previa o commit | Datos anteriores intactos; copia previa requerida para reemplazo | Instrumentada / crítica |
| T-30 | Terminar proceso antes/después del commit de restauración | Al reabrir, libreta íntegra anterior o nueva; marcador consistente | Instrumentada / crítica |
| T-31 | Restaurar copia equivocada, recuperar anterior, importar dos veces | Recuperación posible y sin duplicación; no borrar protección antes de crear nueva válida | Integración / crítica |
| T-32 | Drive en el selector: guardar en carpeta privada, cancelar, confirmar en Drive web u otro dispositivo | Operación local disponible; éxito solo tras relectura; archivo visible en Drive | APK firmado + Google real / crítica. **Comprobado 2026-10-17** ([22](22_Validacion_Release_Drive.md)) |
| T-33 | Drive sin conexión al guardar y al abrir | No prometer subida; distinguir «comprobada» de «subida»; restaurar sin red puede fallar sin cambiar datos | Dispositivo + Google real / crítica. **Observado 2026-10-17:** Drive aceptó y reabrió desde caché; subida al reconectar ([22](22_Validacion_Release_Drive.md)) |
| T-34 | ~~Retención de siete copias~~ Fuera de V1. En su lugar: la app nunca borra ni sobrescribe por sí misma un archivo exportado; propone nombre nuevo | Archivos previos intactos salvo acción explícita de la usuaria en el selector | Dispositivo / alta |
| T-35 | Instalar release en entorno vacío y recuperar archivo/Drive | Mismos clientes, historia, revisiones y saldos | Dos instalaciones / crítica. Drive → QA Restauración **comprobado** 2026-10-17 con SHA-256 igual; teléfono final pendiente |
| T-36 | Datos cambian tras snapshot/vista previa; restaurar y alcanzar un número de revisión que ya tuvo otra historia | Aviso de cambios pendientes; comparar época y revisión, no solo número; preview vigente y exclusión al aplicar | Integración / crítica |
| T-37 | Inspeccionar permisos, logs, backup sistema, ZIP y árbol Git | Sin secretos/datos reales; exportación legible declarada; exclusiones de plataforma probadas | Revisión + dispositivo / crítica |
| T-38 | Libreta totalmente vacía, solo archivados/anulados o 0 bytes | Vacía válida con advertencia según contexto; archivo vacío rechazado | Integración + UI / alta |
| T-39 | Actualizar APK firmado con versionCode superior sobre instalación con datos | Mismos clientes, movimientos, revisiones y saldos; firstInstallTime conservado | Dispositivo / crítica. **Comprobado** rc.1 → rc.2 ([22](22_Validacion_Release_Drive.md)) |

## Invariantes útiles para pruebas generativas

Generar secuencias válidas de fiados/abonos y comprobar: ningún prefijo negativo; saldo final igual a agregado SQL; orden independiente del orden físico de lectura; editar/revertir mediante corrección mantiene la trazabilidad; exportar/restaurar no cambia el estado de negocio. Usar semilla fija para reproducir fallos. Estas pruebas complementan ejemplos, no justifican añadir un framework costoso si bastan generadores pequeños.

## Trazabilidad de cobertura

| Historia | Requerimientos principales | Casos |
| --- | --- | --- |
| HU-01 | RF-03, RF-04, RF-05, RF-07 | T-05, T-06, T-16, T-17, T-19 |
| HU-02 | RF-01, RF-10 | T-01, T-02, T-04 |
| HU-03 | RF-04 | T-05, T-07, T-10, T-11 |
| HU-04 | RF-05 | T-06, T-07, T-08, T-11 |
| HU-05 | RF-06 | T-09, T-15 |
| HU-06 | RF-02, RF-03 | T-01, T-03, T-04, T-22 |
| HU-07 | RF-07 | T-13, T-16, T-21 |
| HU-08 | RF-08, RF-09 | T-12 a T-15, T-18 |
| HU-09 | RF-11 a RF-15 | T-19, T-23 a T-38 |
| Transversal | RF-16, RNF-01 a RNF-08 | T-17 a T-22, T-28, T-32, T-33, T-37 |

## Evidencia y bloqueo de salida

Evidencia de iteración 09 (2026-10-17) en [20 — Restauración desde archivo](20_Restauracion_Archivo.md): 23 casos de servicio y 4 de pantalla nuevos, regresión JVM/instrumentada y ensayo manual en dos instalaciones aisladas. Incluye archivos inválidos, reemplazo/rollback, copia vacía, repetición, épocas obsoletas, exclusión, protección previa, recuperación y cortes deterministas antes/después del commit. No equivale a muerte real del proceso ni completa los casos de Drive de HU-09; mínimos de API/dispositivo y rendimiento de restauración siguen pendientes.

Cada ejecución registra: versión/commit, equipo/API, tipo de build, datos/semilla, pasos, resultado esperado/observado y evidencia sin datos personales. Un caso no aplicable requiere razón; no equivale a aprobado. En V1 inicial, migraciones se prueban con esquemas de prueba y al menos actualización que conserve datos; cada cambio real de esquema añade su ruta obligatoria.

Todos los casos críticos deben aprobarse antes de operar la única libreta real. No aceptar pérdida de datos, diferencia de saldo, restauración sin validar, eliminación remota indebida ni bloqueo offline como “limitación conocida”. Los objetivos de usabilidad/rendimiento se evalúan con la vendedora y el equipo acordado; cualquier ajuste de meta requiere explicación documentada.
