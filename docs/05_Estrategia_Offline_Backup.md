# 05 — Estrategia offline, respaldo y recuperación

## Contrato de V1

La libreta opera completamente en SQLite local. El respaldo es una instantánea completa, no una sincronización. V1 incluye exportación manual a archivo, respaldo manual a Google Drive y restauración desde ambos. El backup automático se reserva para V1.1.

Estado al 2026-10-17: exportación y restauración **desde archivo** implementadas, incluida recuperación privada del estado anterior. El formato y límites de la iteración 08 no cambian. Google Drive sigue pendiente y HU-09 no está completa. El protocolo ejecutado, la exclusión `operation → writes → Room` y evidencia están en [20](20_Restauracion_Archivo.md).

Un teléfono sin Internet puede registrar, buscar, corregir y exportar a un destino local. Drive requiere red solo mientras se autoriza, sube, lista o descarga. No se crea una cola de movimientos financieros para enviar después.

## Alternativas analizadas

| Alternativa | Ventajas | Riesgos y costo de restaurar | Elección |
| --- | --- | --- | --- |
| A — Archivo SQLite | Conserva estructura, índices y datos; recuperación potencialmente rápida | Acopla backup a esquema/versión; copiar solo `.db` abierto puede omitir datos WAL; exige snapshot consistente y manejo cuidadoso de conexiones | No es el formato público V1 |
| B — JSON lógico | Independiente del esquema físico, inspeccionable, validable y portable | Hay que definir versión, relaciones, revisiones y un importador; no incluye índices, que se reconstruyen | **Elegida** |
| C — SQLite + JSON | Dos vías de recuperación y utilidad de soporte | Duplica generación, espacio, pruebas y selección de formatos para la usuaria | No justificada para V1 |

SQLite documenta su [Online Backup API](https://sqlite.org/backup.html) para obtener snapshots; también explica que el [WAL](https://sqlite.org/wal.html) forma parte del estado persistente mientras está activo. No copiar el archivo `.db` a ciegas, ni cerrar la app a la fuerza para intentar respaldarlo. Si más adelante se adopta A, tendrá un protocolo y pruebas propios.

## Formato elegido: JSON dentro de un único archivo

Nombre: `backup_2026-10-05_213045_<UUID>.pagaya.zip`. Hora del nombre en Bogotá para reconocerlo; el nombre no determina identidad, validez u orden. UUID completo evita colisiones. MIME de transporte `application/zip`.

El archivo tiene exactamente dos entradas en la raíz:

```text
backup_....pagaya.zip
├── manifest.json
└── data.json
```

Esto sigue siendo la alternativa B: datos lógicos en JSON, sin una copia binaria de SQLite. ZIP permite distribuir manifiesto y datos juntos con bibliotecas de plataforma. Se prefiere frente a un checksum incluido sobre un objeto JSON reserializado, cuyo orden/espacios podrían cambiar. El nombre es más largo que `backup_YYYY-MM-DD.json` para admitir varias copias diarias y metadatos de integridad sin archivos sueltos.

### Manifiesto, versión 1

| Clave | Tipo y significado |
| --- | --- |
| formato | String exacto `paga-ya-backup` |
| version_formato | Entero 1; contrato del contenedor |
| version_datos | Entero 1; contrato lógico de `data.json` |
| version_app | String de release que generó el archivo |
| backup_id | UUID de la captura; estable durante reintentos |
| libreta_id | UUID de la libreta exportada |
| capturado_en | Entero epoch UTC en milisegundos |
| zona_negocio | String `America/Bogota` |
| moneda | String `COP` |
| revision_datos | Entero del snapshot |
| clientes / movimientos / revisiones | Conteos enteros de todas las filas, incluidas inactivas |
| bytes_datos | Longitud exacta de `data.json` sin comprimir |
| sha256_datos | Hexadecimal minúsculo de 64 caracteres |

`data.json` es UTF-8 sin BOM y contiene `libreta` (id, creación, revisión y siguiente orden), `clientes`, `movimientos` y `revisiones`. Cada fila usa las claves y tipos del [modelo de datos](03_Modelo_Datos.md). `nombre_busqueda` se omite y se regenera; no es un dato autoritativo. Se incluye la historia anterior dentro de cada revisión.

No exportar tokens, preferencias de cuenta Google, rutas del teléfono, IDs de instalación, tabla operativa de backups ni secretos. No exportar saldos como autoridad; se recalculan. Usar enteros JSON sin exponentes, decimales, NaN o infinitos para cantidades/fechas. No permitir claves duplicadas.

El checksum se calcula sobre los **bytes exactos sin comprimir de data.json**, antes de empaquetar; el importador calcula sobre esos mismos bytes, no sobre un objeto vuelto a serializar. SHA-256 detecta corrupción accidental, no autentica al autor ni impide modificar archivo y checksum juntos. V1 no añade firma digital ni cifrado propio.

### Límites defensivos vigentes del formato 1

- Archivo comprimido: hasta 25 MiB; datos descomprimidos: hasta 50 MiB; manifiesto: hasta 64 KiB.
- Hasta 100.000 clientes, 100.000 movimientos y 100.000 revisiones; profundidad JSON máxima 20.
- Solo `manifest.json` y `data.json`, sin nombres repetidos, carpetas, rutas relativas, enlaces ni entradas extra; rechazar ZIP cifrado o métodos no soportados.
- Procesar con límites de lectura real, no confiar en tamaños declarados del ZIP. No extraer rutas provistas por el archivo al sistema de archivos.
- Cruzar directorio central, encabezados locales, descriptores y contenido real: nombres, posiciones, métodos, flags, CRC y tamaños comprimidos/descomprimidos deben coincidir; no admitir relleno ni entradas ocultas.
- Textos de datos limitados **durante la lectura**, en puntos de código Unicode: nombre 120, teléfono 30, nota/descripción 1.000, motivo 300; UUID 36, fecha 10, códigos 16, snapshot canónico 8.192. Este último es una cota defensiva que cubre los escapes del contrato, no una ampliación de los 1.000 caracteres de descripción. Claves JSON hasta 64; textos conocidos del manifiesto con su límite por campo (versión de app 100, SHA 64); textos genéricos de extensiones/versiones desconocidas hasta 1.024.
- Rechazar un formato futuro desconocido indicando que se necesita una versión compatible. Las migraciones de formato anteriores serán explícitas y probadas cuando existan.

Son límites del archivo, independientes de la carga objetivo de rendimiento; no garantizan que toda combinación máxima sea sostenible en cualquier teléfono. La captura mantiene entidades Room y la validación mantiene filas parseadas, índices y estados de dominio en memoria, aunque los JSON se lean/escriban por streaming y nunca se cargue todo el ZIP como texto. Ver mediciones, margen de seguridad y máximos no comprobados en [19](19_Respaldo_Archivo.md). Al exceder un límite se conserva la información actual; no truncar silenciosamente ni capturar `OutOfMemoryError` como estrategia.

Antes de cargar listas, `prepare` obtiene conteos y bytes UTF-8 variables en SQLite, **en la misma transacción** que la metadata, captura e inserción del intento. Rechaza filas excesivas o una cota inferior `bytes_texto + 100×clientes + 200×movimientos + 150×revisiones` mayor que 50 MiB. Los términos fijos son menores que las claves, puntuación, UUID y valores mínimos de cada fila; escapar texto solo aumenta su tamaño. No es una predicción de memoria ni del tamaño final: omite metadata, dígitos adicionales y escapes para evitar rechazos falsos de archivos admisibles. El tamaño real sigue comprobándose al validar. Un rechazo previo no crea un nuevo éxito ni modifica cuentas o la copia verificada anterior.

## Generación y exportación

1. Serializar una instantánea de lectura consistente de clientes, movimientos, revisiones y metadatos en una transacción Room. Incluir también archivados y anulados.
2. Escribir `data.json` en un archivo temporal privado, con hash y conteos; cerrar la transacción antes de cualquier operación de red.
3. Crear manifiesto y ZIP, cerrar y volver a abrir con el mismo validador usado para restaurar.
4. Si se exporta, usar `ACTION_CREATE_DOCUMENT` y copiar al destino elegido. Cerrar, reabrir y validar antes de marcar «Copia exportada»; si el proveedor no permite verificar, informar que no se confirmó y no actualizar el indicador de éxito.
5. Si se envía a Drive, seguir la verificación remota descrita abajo.
6. Registrar éxito, destino, backup_id, revisión y hora únicamente al terminar la verificación correspondiente.

Mientras se obtiene el snapshot puede mostrarse una espera breve. Después de capturarlo se permiten nuevos movimientos durante la subida: el backup protege la revisión capturada, no los cambios posteriores. Si la revisión o la época local actual difieren de las capturadas, mostrar «Hay cambios sin respaldar». La época es metadata de esta instalación, no parte del archivo portable.

Un cierre o falta de espacio elimina/deja identificados los temporales incompletos, no una copia válida. En el próximo inicio limpiar solo temporales conocidos; no borrar archivos de usuario. Las operaciones de backup se serializan entre sí para evitar carreras en retención. No iniciar restauración durante un backup en curso: permitir terminarlo o cancelarlo primero.

## Google Drive dentro de V1

### Autorización

Registrar un proyecto de Google, habilitar Drive API, configurar consentimiento y cliente Android con identificador de paquete y SHA-1 de la firma release. Esto es configuración de acceso, no un servidor contratado. Separar pruebas y producción; una copia creada con otra identidad de aplicación puede no ser visible para la definitiva.

Solicitar `https://www.googleapis.com/auth/drive.appdata` al pulsar «Conectar Google Drive». Está clasificado como no sensible; aun así hay configuración de consentimiento y requisitos de publicación que deben comprobarse para el uso real. No solicitar acceso completo a Drive. [Scopes oficiales](https://developers.google.com/workspace/drive/api/guides/api-specific-auth).

Usar `AuthorizationClient` para autorización de datos. No crear cuentas propias, contraseña de app, backend OAuth, secreto de cliente embebido ni flujo de refresh tokens mantenido por nosotros. Solicitar un token cuando haga falta y gestionar resolución/revocación. No registrar tokens en logs. La autorización es independiente de un inicio de sesión obligatorio en Paga Ya!. [Autorización Android](https://developer.android.com/identity/authorization).

La respuesta de autorización por sí sola no aporta identidad de cuenta. Consultar `about.get(fields=user(emailAddress,permissionId))` para mostrar la cuenta efectiva y asociar estados de backup; el método acepta el scope appdata. Si no se puede identificar/verificar la cuenta, no reutilizar el historial remoto de otra. [Referencia about.get](https://developers.google.com/workspace/drive/api/reference/rest/v3/about/get).

### Uso de appDataFolder

Usar `parents: ["appDataFolder"]` al crear y `spaces=appDataFolder` al listar. Es un espacio oculto accesible a la aplicación; no es una carpeta navegable en Mi unidad ni el backup automático del teléfono. Sus archivos no se comparten ni van a la papelera. El usuario puede eliminar los datos de la aplicación desde Drive; quitar la app de Mi unidad también puede eliminar ese espacio. Desinstalar el APK del teléfono no debe confundirse con esa acción en Drive. [Datos propios de aplicaciones](https://developers.google.com/workspace/drive/api/guides/appdata).

Ventaja para la usuaria: no organizar carpetas. Costo: la recuperación depende de Paga Ya! y de conservar su configuración Google. Por eso la exportación a archivo independiente también es obligatoria.

### Subida, identificación y verificación

- Cada captura crea un archivo nuevo. Guardar en `appProperties` identificadores de formato, libreta y backup; son índices de búsqueda, no prueba de integridad.
- Usar subida reanudable para archivos de tamaño variable; persistir sesión de forma privada si es necesaria. No exponer URLs de sesión ni autorizaciones.
- Descargar la copia recién subida a un temporal privado y validar contenido/checksum antes de marcar éxito. El estado remoto HTTP exitoso por sí solo no garantiza que el contenido sea restaurable.
- Si se pierde la respuesta, consultar por backup_id antes de repetir. Si aparece más de una copia por un reintento, validar, conservar una y contar el backup lógico una sola vez.
- Listar con paginación y campos mínimos; no asumir que la primera página contiene todas las copias ni confiar en orden del nombre. Descargar/validar la elegida antes de restaurar.
- Tokens vencidos: renovar autorización una vez y, si requiere interacción, volver al consentimiento. 403 requiere distinguir cuota, permiso o configuración; 429/5xx admiten reintento acotado con espera y cancelación. Nunca reintentar indefinidamente.

Los protocolos de subida y los límites deben seguir la [guía de subidas](https://developers.google.com/workspace/drive/api/guides/manage-uploads) y los [límites de Drive](https://developers.google.com/workspace/drive/api/guides/limits). No se contrata almacenamiento adicional desde la aplicación; si falta espacio se informa y se ofrece exportación local.

### Estados comprensibles

«Sin conectar», «Preparando copia», «Subiendo», «Verificando», «Última copia en Drive: fecha/hora», «Sin conexión», «No se pudo guardar la copia» y «Se necesita permiso de nuevo». Mostrar cuenta y fecha de captura además de fecha de verificación cuando difieran.

Cancelar o fallar mantiene la última copia verificada. Desconectar revoca acceso/limpia estado de cuenta de la app; no elimina automáticamente archivos remotos. Cambiar de cuenta obliga a volver a listar su espacio; no migrar copias entre cuentas de forma implícita.

## Política de retención y hábito

La recomendación operativa V1 es pulsar «Guardar copia» al terminar cada jornada con movimientos y antes de cambiar de teléfono. No se promete backup diario automático. El aviso discreto de cambios sin respaldar no bloquea ventas.

Mantener **las siete últimas capturas regulares verificadas por libreta** en cada destino administrado por la app. Siete copias no equivalen a siete días: varias pueden haberse hecho el mismo día. Crear y verificar primero; solo después retirar el excedente más antiguo. Ordenar por captura con desempate por backup_id, mostrando fechas anómalas en caso de reloj incorrecto.

La limpieza usa solo archivos reconocidos de esa aplicación/libreta. En Drive, el borrado en appDataFolder es permanente, por lo que debe validarse pertenencia, elegibilidad y existencia de siete capturas válidas más recientes. Si verificar/listar/borrar falla, conservar copias extra; el fallo de limpieza no invalida una nueva copia ya verificada. No contar temporales o archivos corruptos como copias válidas.

La app puede gestionar siete copias privadas locales, pero no borrar automáticamente archivos exportados a destinos elegidos por la usuaria. Mantener por separado la última copia de recuperación previa a una restauración; no participa en la rotación regular. Al crear otra, verificar la nueva antes de sustituir esa protección. Al menos una exportación independiente antes de cambio de dispositivo reduce dependencia de Drive.

Siete copias ayudan frente a errores recientes, pero no garantizan recuperar un error descubierto meses después. Una copia con datos equivocados puede ser estructuralmente válida. Antes de reemplazar una libreta con datos por una vacía, advertirlo expresamente; no tratar cero clientes como corrupción automática.

## Restauración segura

1. Elegir archivo o copia Drive. Descargar/copiar a almacenamiento temporal privado. No cambiar la BD operativa.
2. Validar límites, contenedor, formatos soportados, checksum, UTF-8, claves y tipos.
3. Validar IDs únicos, relaciones, tipos, moneda, fechas, valores, órdenes, siguiente orden y cadena de revisiones. El id/revisión de libreta debe coincidir entre manifiesto y datos.
4. Construir una BD temporal con el esquema actual. Importar datos, ejecutar comprobaciones de integridad/FK y recalcular todas las secuencias y saldos. Esta BD no se convierte en la operativa.
5. Mostrar vista previa: fecha, origen, número de clientes/movimientos, total pendiente calculado y comparación con la libreta actual. Los conteos del manifiesto deben coincidir con el contenido real.
6. Explicar «Esta copia reemplazará las cuentas de este teléfono» y pedir confirmación. Adquirir exclusión de escritura y revalidar que la libreta no cambió desde la vista previa; si cambió, mostrarla de nuevo.
7. Si existen datos, crear y verificar un backup de recuperación local del estado actual bajo esa exclusión. Si falla por espacio u otro error, abortar. Una instalación vacía no necesita esa copia previa.
8. En una única transacción de la BD operativa, quitar registros en orden de dependencias e insertar clientes, movimientos, revisiones y metadatos validados. Registrar identificador/hora de restauración, nueva época local y localizador de la copia previa en metadata local. El backup histórico de otra época no marca estos datos como recién respaldados, aunque coincida el contador de revisión. No intercambiar archivos `.db` abiertos ni manipular manualmente el WAL.
9. Si falla antes del commit, rollback conserva la libreta anterior. Si el proceso termina después del commit, al reiniciar se reconoce la restauración completada por su marcador y se muestra el nuevo estado íntegro.
10. Liberar bloqueo, recargar observadores y mostrar resultado con saldos. Borrar temporales conocidos; conservar la copia de recuperación. La restauración no sube ni borra copias en Drive automáticamente.

Todos los accesos de escritura pasan por el repositorio y respetan esta exclusión. El backup de recuperación y el marcador pertenecen a la instalación, no se importan como pruebas de éxito desde el archivo externo.

Si se restaura una copia equivocada, abrir «Recuperar datos anteriores» y aplicar el mismo validador/protocolo a la copia previa. Importar dos veces el mismo backup reemplaza el estado, no duplica filas.

Implementación de iteración 09: cada protección previa tiene nombre privado único e inmutable; el puntero activo cambia solo en el commit. Se conservan copias verificadas extra, incluso de intentos abortados, sin rotación automática. La limpieza solo retira temporales propios. Estas copias ocupan espacio y no cuentan como protección externa. No se mantiene transacción durante la vista previa; se comprueban identidad, época y revisión al confirmar y se exige otra revisión si cambiaron.

## Recuperación ante pérdida del teléfono

1. Instalar una release compatible de Paga Ya! con la misma identidad de aplicación para Drive.
2. Abrir «Recuperar cuentas» desde la libreta vacía.
3. Autorizar la misma cuenta Google y elegir una copia válida, o seleccionar un archivo exportado conservado fuera del teléfono.
4. Revisar fecha y conteos, restaurar y comprobar dos o tres cuentas conocidas.
5. Empezar a operar solo en el nuevo teléfono. Si aparece el anterior, no seguir registrando en ambos.

La recuperación cubre hasta la última captura verificada, no movimientos posteriores. Sin copia externa accesible, la app no puede garantizar recuperar un dispositivo perdido. Una copia privada guardada únicamente en ese mismo teléfono no cubre ese escenario. En la entrega debe hacerse un ensayo real de recuperación en otra instalación de prueba.

## Privacidad y evolución

El archivo exportado contiene nombres, teléfonos y movimientos legibles. Indicarlo en el flujo de exportación y recomendar guardarlo en una ubicación privada. No usar una clave de cifrado ligada exclusivamente al teléfono: haría irrecuperable un backup tras perderlo. Cifrado portable con contraseña requiere diseño de recuperación y queda como decisión futura.

V1.1 podrá ejecutar el mismo servicio con WorkManager, red disponible, revisión pendiente y política de reintento. La automatización necesitará tratar permisos revocados, batería y restricciones de segundo plano; no es sincronización y no garantiza ejecución a una hora exacta. No agregar WorkManager solo para reservar esta posibilidad.
