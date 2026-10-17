# 07 — Plan de implementación V1

## Objetivo y condiciones de trabajo

Construir la libreta local descrita en los documentos 00–06, con recuperación verificable. Este archivo es un plan ejecutable por un desarrollador individual; no afirma que se hayan implementado las tareas.

La documentación inicial cubre parte de fase 0. Las validaciones con teléfono, stack, usuaria y Google siguen pendientes. No fijar calendario definitivo sin esas comprobaciones. Trabajar en incrementos pequeños; cada fase entrega algo comprobable y actualiza los documentos afectados.

Dependencia principal: **0 → 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8**. Las pruebas se construyen con cada fase; fase 7 reúne integración, recuperación y aceptación. **Actualización 2026-10-17:** Drive se usa mediante el selector Android ([21](21_Firma_Distribucion.md)); la fase 6 se reduce a validar ese uso con el APK firmado, hecho en [22](22_Validacion_Release_Drive.md). No hay experimento OAuth.

| Hito | Resultado | Condición |
| --- | --- | --- |
| A — Base local | Cliente, fiado, abono y saldo sobreviven al reinicio | Fases 1–3 |
| B — Libreta verificable | Historia, correcciones y exportación/restauración local | Fases 4–5; apta para pruebas, todavía no V1 final |
| C — Recuperación externa | Drive manual con verificación y restauración en instalación nueva | Fase 6 |
| D — V1 entregable | Casos críticos y piloto aprobados, APK firmado y manual | Fases 7–8 |

## Fase 0 — Diseño y preparación

**Entrada:** necesidad y documentación inicial. **Estado actual:** documentos preparados; validación de campo pendiente.

Tareas:

1. Revisar supuestos con la vendedora: único teléfono, fecha de corte del cuaderno, importes máximos reales, compras a crédito equivalentes a fiados y rechazo de abonos superiores al saldo.
2. Registrar modelo/Android/RAM/espacio y disponibilidad de Google Play services. Acordar equipo objetivo de rendimiento y si se permite instalación de APK firmado.
3. Confirmar experiencia del mantenedor. Mantener Kotlin salvo evidencia que cambie la comparación de arquitectura.
4. Elegir versiones estables compatibles del toolchain; documentar JDK, Android Studio, SDK, AGP, Gradle, Kotlin, Compose, Room y procesamiento KSP. Resolver API mínima propuesta 26 contra dispositivo y dependencias.
5. Definir paquete definitivo y custodia de firma (hecho: [21](21_Firma_Distribucion.md)). Probar instalación con la firma de distribución y guardar/abrir un archivo sintético en Drive con el selector (hecho: [22](22_Validacion_Release_Drive.md)). No usar datos de clientes.
6. ~~Revisar configuración OAuth~~ No aplica: sin OAuth en V1.
7. Recorrer wireframes: buscar, fiar, abonar, pagar, corregir y recuperar. Medir comprensión; acordar los textos.
8. Cerrar entidades, restricciones, orden de movimientos, auditoría, versión lógica de backup y reemplazo transaccional.
9. Mantener estructura simple de un módulo, convenciones de código y política de migraciones; anotar decisiones nuevas en documento 09.

**Entregables:** matriz de versiones, ficha del dispositivo, wireframes ajustados, resultado de viabilidad Drive/instalación y decisiones cerradas. El pequeño experimento técnico se realizará en la siguiente iteración, no en esta fase documental.

**Salida:** camino viable para instalar, operar localmente y recuperar con Drive; ninguna decisión de tipo de movimiento o moneda queda implícita. Si el experimento de Drive falla, documentar la causa y resolverla; no llamar V1 completa a una entrega que omita el requisito.

## Fase 1 — Base de aplicación

**Depende de:** fase 0.

Tareas:

1. Generar proyecto Android con una Activity y módulo `app`. Versionar wrapper/catálogo, recursos en español y configuración de build; excluir claves y `local.properties`.
2. Crear navegación Clientes → Cuenta → Movimiento; rutas por IDs. Añadir destinos secundarios de cliente y respaldo, inicialmente con estados vacíos honestos.
3. Crear componentes base para botones, valor monetario, estado de error/carga y filas accesibles. No construir un sistema visual genérico.
4. Configurar Room persistente en directorio privado: clientes, movimientos, revisiones, metadatos y registros operativos de backup.
5. Activar FK, índices y restricciones. Exportar esquema, incluir pruebas de creación y reservar política explícita de migración.
6. Crear repositorio, validadores de dinero/fechas/historia y contenedor de dependencias manual. Escrituras exclusivamente por repositorio, corrutinas fuera del hilo UI.
7. Implementar cierre/recuperación de errores y configuración de respaldo del sistema; revisar exclusiones de Auto Backup y transferencias.
8. Separar fixtures sintéticos de la compilación de uso real; nunca precargar clientes de ejemplo en producción.

**Entregables:** app instalable vacía, navegación, persistencia mínima verificable y compilación reproducible.

**Pruebas de salida:** DB vacía abre correctamente sin red; navegación/back y rotación no fallan; restricciones básicas se aplican; force-stop no recrea la BD; no aparecen datos ficticios en release. Aún no afirmar que HU-01 esté completa.

## Fase 2 — Clientes

**Depende de:** base local.

Tareas:

1. Formulario con nombre obligatorio, teléfono y nota; creación/modificación automáticas y validación de longitudes.
2. Creación transaccional con ID estable y actualización de revisión de libreta.
3. Listado por nombre con saldo agregado y estados de carga/vacío/error.
4. Búsqueda normalizada por mayúsculas/acentos y manejo literal de caracteres de `LIKE`; conservar consulta al volver.
5. Cuenta de cliente con identificación y saldo derivado, inicialmente sin movimientos.
6. Editar datos; advertir homónimos sin bloquearlos.
7. Archivar solo si saldo cero, mostrar archivados y permitir reactivar. Sin eliminación física.

**Entregables:** HU-02 y HU-06, más base de HU-01.

**Pruebas de salida:** crear, editar, buscar «maria»/«María», homónimos, archivar/reactivar y persistir tras reinicio. Verificar nombres largos y texto ampliado. Archivar cliente con deuda se prueba de nuevo al integrar fase 3.

## Fase 3 — Movimientos y saldo

**Depende de:** clientes y repositorio.

Tareas:

1. Entrada estricta de COP entero; límites, formato y detección de desbordamiento, incluyendo texto pegado.
2. «Registrar fiado/compra a crédito»: un único tipo FIADO, fecha automática editable y nota opcional. No crear una pantalla COMPRA redundante.
3. «Registrar abono»: saldo disponible, rechazo de exceso, fecha y nota.
4. «Pagar saldo completo»: confirmación del importe, relectura transaccional y origen PAGO_TOTAL.
5. Implementar orden estable y validador de todos los prefijos del historial para fechas anteriores y modificaciones.
6. Implementar edición de valor/fecha/nota con motivo y revisión; control de versión. Impedir cambio de cliente/tipo.
7. Implementar anulación lógica con preview del saldo y auditoría; sin recuperación silenciosa ni borrado físico.
8. Implementar UUID de solicitud, bloqueo del doble toque y reintento idempotente. Preservar formulario si falla.
9. Propagar cambios mediante consultas observables; comparar agregados de lista con acumulados de detalle.

**Entregables:** HU-03, HU-04, HU-05 y HU-08 completas en reglas y formularios.

**Pruebas de salida:** secuencia $60.000 − $15.000 − $20.000 − $25.000 = $0; abono excesivo/negativo/cero/decimal; doble toque; pagos con saldo cambiado; edición inválida; fallo entre auditoría y actualización; fechas con prefijos negativos. No seguir a uso real si la cuenta puede quedar inconsistente.

## Fase 4 — Historial y validación de UX

**Depende de:** movimientos íntegros.

Tareas:

1. Presentar cronología descendente agrupada por día, con desempate estable, signos e importe legible.
2. Mostrar saldo resultante recalculado en movimientos vigentes y saldo actual destacado.
3. Resaltar ✓ Abono y ✓ Pago con texto y realce; separar claramente anulaciones.
4. Mostrar notas, marca Editado y detalle de versiones con motivos; sin convertir el historial en una tabla contable compleja.
5. Mantener posición de lista y estados de formulario al navegar. Usar lista perezosa y carga por bloques si las mediciones lo exigen, sin añadir infraestructura de paginación anticipada.
6. Probar cuenta sin movimientos, pagada, con deuda, solo anulaciones y notas largas.
7. Sesión con la usuaria: diez registros habituales, comprensión de saldo/signos y búsqueda. Ajustar errores observados en una ronda concreta.

**Entregables:** HU-01 y HU-07 completas, recorrido principal medido.

**Salida:** no se cortan importes ni acciones con fuente grande; la usuaria distingue abonos; los tiempos objetivos se miden en equipo real y se registra la evidencia. Aún no trasladar la única libreta real a una aplicación sin recuperación externa.

## Fase 5 — Backup local y restauración

**Depende de:** datos y revisiones estables.

Tareas:

1. Definir serializers DTO del contrato lógico versión 1, separados del esquema Room solo donde sea necesario para compatibilidad.
2. Crear snapshot transaccional que incluya inactivos/revisiones y conserve IDs, orden y revisión de libreta.
3. Generar ZIP con `data.json`, `manifest.json`, hash exacto y límites; no exportar SQLite abierto.
4. Desarrollar un validador reutilizable para copias recién creadas e importadas: contenedor, hash, tipos, conteos, FK, versiones y saldo.
5. Integrar guardar/abrir documentos Android y releer el archivo exportado. Diferenciar copia local confirmada de intento y destino cloud del selector.
6. ~~Implementar retención local de siete copias administradas~~ Fuera de V1 (2026-10-17). No borrar exportaciones del usuario.
7. Implementar importación a BD temporal, preview, bloqueo de escrituras, backup previo y reemplazo en una única transacción operativa.
8. Añadir marcador local de restauración y recorrido de recuperación del estado anterior después de reiniciar.
9. Crear fixtures sintéticos válidos/corruptos y pruebas de round-trip; no guardar archivos financieros reales en Git.

**Entregables:** archivo portable, importador y recuperación local; guía breve de exportación.

**Pruebas de salida:** igualdad semántica exportar/restaurar (datos, IDs, versiones, anulaciones y saldos), rechazo de ZIP/JSON inválido, claves duplicadas, backup futuro, sobredimensionado y referencias huérfanas; interrupción antes/después de commit; disco lleno; importar dos veces sin duplicar; restaurar en instalación vacía y revertir restauración equivocada.

Con la decisión del 2026-10-17, el selector de esta fase también cubre Drive.

## Fase 6 — Google Drive manual

**Actualización 2026-10-17:** el propietario decidió usar Drive como destino del selector Android. Las tareas 1–8 siguientes (configuración Google, autorización, cuenta efectiva, subida, listado, retención remota y errores HTTP) quedan **fuera de V1** como referencia de una integración directa futura. En V1 la fase se cumple con: exportar a una carpeta privada de Drive con el APK firmado, confirmar el archivo en Drive web u otro dispositivo, restaurarlo en otra instalación y comparar, y comprobar cancelación y comportamiento sin conexión. Evidencia: [22](22_Validacion_Release_Drive.md). Pendiente: repetirlo en el teléfono final con la cuenta de la usuaria.

**Depende de:** servicio de backup probado.

Tareas de la integración directa (fuera de V1):

1. Terminar configuración Google de producción: paquete/firma, API, consentimiento, scope appdata y documentación de acceso del propietario. Verificar requisitos vigentes sin contratar infraestructura innecesaria.
2. Implementar autorización al pedir respaldo; gestionar cancelación, permisos revocados y tokens vencidos. No bloquear inicio ni operaciones locales.
3. Obtener cuenta efectiva y mostrarla en Respaldo. Probar selección/cambio de cuenta con las APIs disponibles; no mezclar fechas/IDs de cuentas distintas.
4. Subir snapshot existente, identificar por backup_id/libreta_id y resolver respuesta perdida sin generar copias lógicas duplicadas.
5. Descargar y validar antes de confirmar éxito. Mostrar si hubo cambios posteriores a la captura.
6. Listar con paginación, elegir y descargar copias; reutilizar el restaurador de fase 5.
7. Aplicar retención remota de siete copias verificadas, únicamente de la libreta correspondiente; fallo de limpieza conserva excedentes.
8. Manejar offline, cuota, 401/403/429/5xx, cierre, cancelación y red intermitente con reintentos acotados.
9. Probar con APK release y cuenta de la usuaria de prueba, no solo debug o cuenta del desarrollador.

**Entregables:** HU-09 con Drive mediante el selector y recuperación externa.

**Salida V1:** restauración en una segunda instalación desde Drive con la misma cuenta; cancelar o fallar no cambia la última copia exitosa; la app no elimina copias.

**Por qué entra en V1:** perder un teléfono es el riesgo que motivó el requisito. Un archivo que la persona nunca saca del teléfono no lo resuelve. Separar el formato del transporte permite construir primero la base local sin postergar el respaldo externo final.

## Fase 7 — Pruebas, piloto y aceptación

**Depende de:** integración completa. Las pruebas unitarias y transaccionales ya se habrán ejecutado en fases anteriores.

1. Ejecutar la matriz de [casos mínimos](10_Plan_Pruebas.md) con resultados/evidencia por caso; no marcar aprobado a partir de inspección de código.
2. Correr pruebas de dominio y backup en JVM; Room, integridad, migraciones y UI en Android. Añadir fallos inyectados en puntos de commit y transferencia.
3. Comprobar Android mínimo y versión reciente, dispositivo modesto real, modo avión, cierre forzado, reboot, cambio de fecha/hora, poco espacio y aumento de texto.
4. Medir rendimiento release con carga sintética definida y tiempo de registro con la vendedora. No aumentar dependencias para una lentitud no observada.
5. Revisar permisos, logs, exclusiones de respaldo del sistema, datos exportados y Git para evitar secretos/datos reales.
6. Ensayo completo: crear libreta sintética → backup archivo y Drive → instalación vacía → recuperar → comparar cuentas, historia y revisiones.
7. Piloto controlado de varios días con el cuaderno disponible como referencia; acordar un solo lugar oficial de registro y conciliación diaria para evitar dobles entradas.
8. Resolver toda discrepancia financiera, pérdida de datos o fallo de recuperación antes de pasar a distribución. Una limitación estética menor puede documentarse; una deuda incorrecta no.

**Salida:** casos críticos aprobados, métricas registradas, recuperación ensayada y usuaria capaz de guardar/restaurar una copia. Si falla un criterio, volver a la fase correspondiente, no ocultarlo como backlog futuro.

## Fase 8 — Distribución y operación

**Depende de:** aceptación.

Tareas:

1. Construir APK release firmado; verificar paquete, versionCode ascendente, versionName y checksum del artefacto. No entregar debug.
2. Guardar keystore y contraseña fuera del repositorio, con una copia segura recuperable por el propietario. Sin proyecto Google ni OAuth que mantener.
3. Elegir canal: para el piloto familiar, instalación asistida del APK por archivo/USB; registrar la ruta concreta y requisitos del dispositivo. Play puede evaluarse después si facilita actualizaciones.
4. Verificar condiciones actuales de distribución/registro Android para el país y dispositivo. Desde 2026 hay cambios de verificación con despliegue regional y evolución prevista; no asumir que instalar APK siempre tendrá las mismas reglas o costos. [Verificación Android](https://developer.android.com/developer-verification).
5. Antes de instalar una actualización con datos, generar/verificar backup externo. Instalar encima conservando paquete y firma; nunca instruir desinstalar como actualización ordinaria.
6. Probar actualización desde la release previa con migración. Si la migración falla, conservar BD y mostrar recuperación; no hacer downgrade destructivo ni borrar datos para “arreglar”.
7. Entregar guía de una página: abrir cliente, fiar, abonar, pagar, corregir, respaldar, recuperar y contactar al mantenedor. Esa guía se redactará con capturas reales al existir la app.
8. Hacer juntos el primer backup en la carpeta privada de Drive (con el selector) y una exportación externa, y comprobar recuperación en equipo/instalación de prueba sin destruir el teléfono operativo.
9. Conciliar la libreta inicial por cliente y registrar fecha de corte. Acordar quién respalda al cierre de jornada y quién mantiene APK/firma/acceso a la cuenta Google de los respaldos.

**Entregables:** APK, notas de versión, guía de instalación/uso, evidencia de recuperación y custodia de distribución.

**Salida:** app en el teléfono de la usuaria, cuentas iniciales revisadas, copia fuera del dispositivo y responsabilidad de mantenimiento definida.

## Definición de terminado de V1

- HU-01 a HU-09 y RF-01 a RF-16 tienen evidencia satisfactoria, no solo pantallas creadas.
- No se calcula ni digita un saldo manual, ni se pierde una corrección o anulación sin rastro.
- Funciones principales operan offline; datos sobreviven cierre/reinicio y actualización probada.
- Archivo y Drive (mediante el selector) permiten recuperar una instalación nueva con integridad; rollback probado. Sin retención automática en V1.
- Errores de red/espacio conservan datos y muestran mensajes útiles.
- Instalación, versiones, credenciales de distribución y documentos están al día.
- No se agregaron inventario, multiusuario, pagos en línea ni sincronización para poder declarar terminado lo anterior.

## Mantenimiento posterior mínimo

Revisar dependencias y avisos relevantes trimestralmente, o antes si aparece una vulnerabilidad aplicable; agrupar actualizaciones y probar recuperación/migración antes de distribuir. Antes de cambiar formato de backup, conservar compatibilidad o publicar una ruta de conversión probada. Registrar qué versiones pueden leer cada formato y ensayar recuperación periódicamente, sin prometer soporte indefinido sin mantenedor.
