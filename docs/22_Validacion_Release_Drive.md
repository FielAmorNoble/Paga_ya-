# 22 — Validación del APK oficial, actualización y Drive mediante el selector

> Evidencia histórica de rc.1/rc.2. La entrega posterior rc.3 incorpora las aclaraciones de respaldo y el archivo seguro de APK firmados descritos en [sus notas](releases/v0.1.0-rc.3.md).

Fecha: 2026-10-17. Checkout `develop`, HEAD `8ea8b73`, con los cambios pendientes de la iteración de firma ([21](21_Firma_Distribucion.md)) conservados. Sin commits, push, merge, etiquetas ni Release.

Objetivo: comprobar con datos sintéticos el APK oficial firmado, una actualización que conserve los datos y el respaldo/restauración usando Google Drive desde el selector de archivos de Android. No se implementó OAuth, cuenta de servicio, Google Cloud ni integración directa con Drive. No cambió código de la app; solo `versionCode`/`versionNameSuffix` en `app/build.gradle.kts`.

## Guía sencilla

### Instalar Paga Ya! por primera vez

1. Descargar en el teléfono el archivo `app-release.apk` de la publicación oficial (cuando exista) y comprobar, si se puede, que su SHA-256 coincide con el publicado.
2. Abrirlo. Android pedirá permitir instalar apps desde ese origen (navegador o administrador de archivos): aceptarlo solo para esta instalación.
3. Abrir «Paga Ya!». La primera vez aparece la libreta vacía con «Crear primer cliente».

### Actualizar a una versión nueva

1. **Antes**, guardar una copia: Clientes → ⋮ → Respaldo → «Guardar copia en archivo».
2. Descargar el APK nuevo de la publicación oficial e instalarlo **encima** del anterior. Android dirá «¿Quieres actualizar esta app?».
3. **Nunca desinstalar** para actualizar: desinstalar borra las cuentas del teléfono.
4. Abrir la app y revisar dos o tres clientes conocidos.

Si Android dice que el paquete no es compatible o que hay un conflicto, no desinstalar: es otra firma o una versión más antigua. Pedir ayuda al mantenedor.

### Guardar una copia en Google Drive

1. Clientes → ⋮ → Respaldo → «Guardar copia en archivo».
2. En el selector: ☰ → **Drive** → la cuenta del negocio → una carpeta privada (por ejemplo `Respaldo_Paga_ya`). No compartir esa carpeta.
3. Tocar **GUARDAR** sin cambiar el nombre. **No tocar un archivo existente**: lo reemplazaría y Drive conservaría el nombre viejo.
4. Esperar «Copia guardada y comprobada».
5. Con Internet, abrir Drive desde el computador u otro dispositivo y comprobar que el archivo nuevo aparece en la carpeta. **Solo entonces la copia está fuera del teléfono.**

Sin Internet, Drive puede aceptar la copia y subirla después: el mensaje de la app aparece igual. Volver a mirar Drive cuando haya conexión. Hacer la copia al cerrar cada jornada con movimientos y de vez en cuando guardar otra en el computador.

### Recuperar las cuentas en otro teléfono o tras reinstalar

1. Instalar Paga Ya! (ver arriba). Necesita Internet para abrir una copia de Drive.
2. Clientes → ⋮ → Respaldo → «Restaurar desde archivo» → ☰ → Drive → la misma cuenta → la carpeta → la copia más reciente.
3. Revisar la vista previa: fecha de la copia, número de clientes y movimientos y total pendiente.
4. «Restaurar cuentas». Revisar dos o tres clientes conocidos.
5. Si se eligió una copia equivocada: Respaldo → «Recuperar datos anteriores».
6. Usar desde ese momento un solo teléfono para registrar.

Cancelar el selector o la vista previa no cambia nada.

## Versiones utilizadas

| Compilación | applicationId | versionCode | versionName | SHA-256 del APK | Uso |
| --- | --- | ---: | --- | --- | --- |
| Instalación inicial | `com.fielamornoble.pagaya` | 1 | `0.1.0-rc.1` | `58C3C01DE0911043007531C099287A37F40808962EF044CFD4717313C5D408B8` | Compilada en la iteración 21; instalada aquí |
| Actualización | `com.fielamornoble.pagaya` | 2 | `0.1.0-rc.2` | `A0E7DEB4E0C04C7CE8A438A5ABA92281964DC9F3E495D74BA6BA660B729B6247` | Compilada e instalada aquí; **versión actual del proyecto** |

Ambas firmadas con la misma clave: `apksigner verify --verbose --print-certs` → Verifies, esquema v2, un firmante RSA 3072, certificado SHA-256 `72:1E:8B:BB:…:F5:B2:CC:44` igual al de [21](21_Firma_Distribucion.md). `aapt2 dump badging`: «Paga Ya!», minSdk 26, targetSdk 36, sin `application-debuggable`. Ningún versionCode se ha publicado; el 1 y el 2 ya están instalados en el teléfono de pruebas, así que una compilación distinta de este rc.2 (por ejemplo `0.1.0` final) debe usar versionCode 3 o mayor.

Compilación rc.2:

```powershell
.\scripts\Build-Release.ps1 -KeystorePath '<ruta privada>\pagaya-release.jks' -PasswordFilePath '<ruta privada>\CONTRASENA-SOLO-LOCAL.txt'
```

BUILD SUCCESSFUL (1 min 33 s), `lintRelease` sin errores. El log no contiene la contraseña; las rutas privadas no se copian al repositorio.

## Entorno

- Teléfono de pruebas: Motorola moto g84 5G, Android 15 (API 35). **No es el teléfono final** (Redmi Note 14 Pro, Android 16).
- Paquetes al inicio: `.dev`, `.qa`, `.qa.restore`; la app oficial **no estaba instalada**, así que no había una libreta oficial que proteger.
- Herramientas: `adb` y `build-tools/36.0.0` del SDK de doc 19; interacción por `adb shell input`/`uiautomator dump`; acciones con cuenta Google y modo avión hechas por el propietario en el teléfono.
- Huellas SHA-256 de `pagaya.db`/`pagaya.db-wal` de **Dev** y **QA** idénticas al inicio y al final, sin leer sus filas (Dev `9c08c6cd…`/`245491c4…`; QA `4a197086…`/`538a4d20…`).

## Resultados

Datos usados, todos creados desde la interfaz de la app oficial: «Prueba Sintetica Uno» (nota «Cliente ficticio de prueba»; fiado $50.000 «Fiado de prueba»; abono $15.000 corregido a $20.000 con motivo «Prueba de correccion») y «Prueba Sintetica Dos» (fiado $12.000 y pago completo). Sin teléfonos ni otros textos.

| # | Comprobación | Resultado |
| --- | --- | --- |
| 1 | `adb install -r` de rc.1 | Success; versionCode 1; `firstInstallTime` 11:11:23 |
| 2 | Abrir | `MainActivity` en primer plano, título «Paga Ya!», libreta vacía; sin entradas en `logcat -b crash` |
| 3 | Clientes, fiado, abono, corrección, pago completo, historial | Saldos $50.000 → $35.000 → $30.000 (corrección) y $12.000 → $0 «Pagado ✓»; historial con saldo después de cada movimiento y «Editado» |
| 4 | `am force-stop` y volver a abrir | Lista y saldos intactos |
| 5 | Exportar desde rc.1 a Descargas | «Copia guardada y comprobada». En el teléfono: `unzip -p … data.json \| sha256sum` = `e03be4b8147050ba38c2f2d5b4c7712dac8893cdb92d510aee10888707e15eeb`, manifiesto revisión 7, 2 clientes / 4 movimientos / 1 revisión. Los textos del archivo son solo los sintéticos anteriores |
| 6 | `adb install -r` de rc.2 (actualización, sin desinstalar) | Success; versionCode 2, `firstInstallTime` sin cambios (11:11:23), `lastUpdateTime` 11:18:39; datos visibles; «La copia incluye todos los cambios» |
| 7 | Exportar desde rc.2 a Descargas | **Mismo SHA-256 de `data.json`** (`e03be4b8…`), misma `libreta_id`, revisión 7 y conteos: clientes, movimientos, revisiones, IDs y metadatos conservados exactamente |
| 8 | Exportar a Drive (propietario: ☰ → Drive → cuenta → `Respaldo_Paga_ya` → GUARDAR), con red | «Copia guardada y comprobada» 11:25. El propietario vio el archivo y su tamaño en **Drive web** |
| 9 | QA Restauración: «Restaurar desde archivo» y Atrás en el selector | Sin mensaje de error ni de éxito; libreta sin cambios |
| 10 | QA Restauración: abrir la copia de Drive (elegida por el propietario), con red | Vista previa: captura 11:19, 2/4/1, $30.000, frente a la libreta sintética anterior 4/7/3, $52.000. Confirmada: «Cuentas restauradas»; clientes, abono «Editado» y saldos iguales |
| 11 | Exportar desde QA Restauración a Descargas | **Mismo SHA-256 de `data.json`** (`e03be4b8…`) que la app oficial: contenido de la copia de Drive idéntico. `version_app` = `0.1.0-dev` (la variante QA hereda el sufijo de debug) |
| 12 | Modo avión (propietario; `airplane_mode_on=1`, sin red activa) en la app oficial | Fiado $5.000 registrado; tras `force-stop` y abrir: $35.000. Sin errores |
| 13 | Guardar en Drive sin conexión | La app mostró **«Copia guardada y comprobada»** (11:31, revisión 8) para `backup_2026-10-17_113053_b2040e02-…`. La relectura vino de la caché de Drive |
| 14 | Abrir copia de Drive sin conexión en QA Restauración | El selector listó la carpeta y abrió la copia de 11:31 desde la caché local (vista previa 2/5/1, $35.000). Se canceló: QA Restauración sin cambios ($30.000) |
| 15 | Desactivar modo avión | El propietario confirmó en **Drive web** que `backup_2026-10-17_113053_b2040e02…` aparece con su tamaño: la subida terminó después |

## Hallazgos

1. **«Comprobada» no significa «subida».** Sin conexión, Drive acepta el archivo, lo relee desde su caché y la app muestra el mismo éxito que con red (paso 13). La subida ocurrió al recuperar la conexión (paso 15). La pantalla ya avisa que una carpeta en la nube «puede necesitar conexión», pero no dice que hay que confirmar en Drive. La guía lo indica; un texto específico en la app es una mejora pendiente (BF-01c, doc 08) que requiere decisión.
2. **Sobrescritura en Drive.** La copia del paso 8 se guardó tocando un archivo existente en la carpeta: el resultado conserva el nombre `backup_2026-10-16_213705_22f4422b-…` (una copia QA sintética del 16 oct) con el contenido nuevo (1,30 kB). La restauración no se vio afectada porque usa el manifiesto, no el nombre, pero la copia anterior solo puede recuperarse desde el historial de versiones de Drive. La guía pide guardar siempre con el nombre nuevo.
3. **El selector recuerda la última carpeta** (Drive) también en otras variantes; para guardar en Descargas hubo que cambiarla. Es comportamiento del sistema.
4. Abrir sin conexión funcionó porque el archivo estaba en caché en el mismo teléfono. No se probó abrir sin red una copia que nunca estuvo en el teléfono; no se promete que funcione.

## Estado final

- Teléfono: `.dev`, `.qa`, `.qa.restore` y **`com.fielamornoble.pagaya` rc.2** instalados. Ningún paquete desinstalado ni datos borrados.
- App oficial: libreta sintética de prueba (2 clientes, 5 movimientos, 1 revisión, revisión 8). Antes de usarla con cuentas reales habrá que decidir cómo dejarla vacía (p. ej. desinstalarla con autorización explícita **antes** de cargar datos reales, o instalar en el teléfono final, donde no existe).
- QA Restauración: contiene ahora la libreta sintética oficial (revisión 7); su libreta sintética anterior (4/7/3) se conserva en su copia privada «Recuperar datos anteriores».
- Descargas: tres ZIP sintéticos nuevos (11:15, 11:19, 11:28). Drive `Respaldo_Paga_ya`: el archivo sobrescrito de 11:25 y la copia de 11:31, ambos sintéticos. No se descargaron ZIP al computador ni se crearon fixtures.
- Proyecto en versionCode 2 / `0.1.0-rc.2`.

## Verificaciones del repositorio

- `.\gradlew.bat testDebugUnitTest assembleDebug assembleQa assembleQaRestore assembleRelease lintDebug --no-daemon --no-configuration-cache --console=plain`, sin credenciales: **BUILD SUCCESSFUL** (1 min 25 s, 174 tareas). JVM **105 pruebas, 0 fallos, 0 errores, 0 omitidas**. Lint: 0 errores, 7 avisos de versiones ya existentes.
- `aapt2 dump badging` tras esa compilación: `.dev`, `.qa`, `.qa.restore` con versionCode 2 y `0.1.0-dev`; release `com.fielamornoble.pagaya` `0.1.0-rc.2` **unsigned**.
- Esa compilación sin credenciales **reemplazó** el `app-release.apk` firmado de rc.2 en `app/build/outputs/apk/release/`: el APK firmado con SHA-256 `A0E7DEB4…6247` solo existe instalado en el teléfono de pruebas. Para publicar hay que volver a compilar con `Build-Release.ps1` y verificar firma/huella y el SHA-256 nuevo.
- `git diff --check`: sin errores. Sin correos, rutas privadas, claves ni contraseñas en el repositorio.
- No se ejecutaron pruebas instrumentadas: no cambió código, Room ni UI.

## Pendientes antes de publicar

1. **Teléfono final** (Redmi Note 14 Pro, Android 16): instalar el APK firmado, recorrer la guía (incluida instalación desde fuera de Play y avisos del sistema), actualizar con versionCode superior conservando datos sintéticos, guardar en Drive con la cuenta del negocio y restaurar en una instalación de prueba. Comprobar Google Play services/Drive en ese teléfono.
2. **Licencia del repositorio:** decisión del propietario (ninguna asignada).
3. **Recuperación de la clave de firma:** guardar la contraseña en un gestor y comprobar, descargándolo, que el respaldo externo del almacén es legible y su SHA-256 coincide; tener una vía de recuperación independiente del computador.
4. Decidir si se ajustan los textos de Respaldo para destinos en la nube (hallazgo 1) y si se advierte contra sobrescribir (hallazgo 2).
5. Decidir qué hacer con la libreta sintética de la app oficial en el Motorola y con los archivos sintéticos de Descargas/Drive.
6. API 26 y el respaldo del sistema desactivado (T-37) siguen sin comprobar.
7. Revisión de la documentación/tutorial con capturas reales, aprobación de `develop` → `main`, etiqueta y GitHub Release con APK firmado, SHA-256 y notas (doc 21).
