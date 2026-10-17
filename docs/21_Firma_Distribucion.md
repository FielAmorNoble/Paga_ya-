# 21 — Firma y preparación de distribución

Fecha: 2026-10-17. Decisión del propietario: APK oficial distribuido inicialmente mediante GitHub Releases, con clave propia custodiada fuera del repositorio. Este documento actualiza la identidad provisional y la ausencia de firma descritas en los documentos 12 y 14.

## Identidad y variantes

- Release: `com.fielamornoble.pagaya`, nombre visible «Paga Ya!». Candidatos probados: `0.1.0-rc.1` (versionCode 1) y `0.1.0-rc.2` (versionCode 2, versión actual del proyecto). No es todavía la publicación V1. Ninguno se ha publicado; la primera Release debe usar un versionCode mayor o igual que 2 y nunca reutilizar uno ya instalado en un teléfono.
- Debug: `com.example.pagaya.dev`; QA: `com.example.pagaya.qa`; QA Restauración: `com.example.pagaya.qa.restore`. Se conservan para no cambiar las instalaciones ni los datos actuales.
- Namespace interno y paquetes Kotlin: `com.example.pagaya`. Es independiente de la identidad de instalación; no es necesario renombrar código ni esquemas Room para distribuir.
- La identidad release se fija mediante `androidComponents.onVariants`. No cambiarla después de publicar.
- Dev y release no comparten datos: la transición se hará por respaldo y restauración, sin desinstalar Dev antes de comprobar el resultado.

## Clave y custodia

Clave propia RSA de 3072 bits en almacén JKS, alias `pagaya-release`, certificado SHA256withRSA válido hasta el 17 de octubre de 2056. Huella pública SHA-256:

```text
72:1E:8B:BB:18:28:7E:B0:35:5C:72:D1:D2:C4:BC:1D:89:CD:9C:6D:18:D4:67:79:69:54:1D:59:F5:B2:CC:44
```

La clave está fuera del repositorio en la carpeta privada elegida por el propietario. Su copia local se verificó por SHA-256. El propietario informó que realizó el respaldo externo; no se ha comprobado descargándolo de Drive.

La contraseña aleatoria permanece temporalmente en un archivo local con permisos restringidos. Pendiente guardarla en un gestor y disponer de recuperación independiente del computador. No subirla junto con la clave ni incluirla en comandos, logs, conversaciones o Git. Perder el almacén o su contraseña impide firmar actualizaciones con esta identidad.

## Compilación

Sin credenciales, `assembleRelease` produce un APK **sin firma**, útil para que cualquiera compile el repositorio. Nunca se sustituye la firma oficial por una debug.

Para generar el APK firmado, usar PowerShell y proporcionar rutas locales (no la contraseña):

```powershell
.\scripts\Build-Release.ps1 -KeystorePath 'C:\ruta-privada\pagaya-release.jks' -PasswordFilePath 'C:\ruta-privada\CONTRASENA-SOLO-LOCAL.txt' -AndroidSdkPath 'C:\ruta-del-sdk'
```

El script configura temporalmente `PAGAYA_RELEASE_STORE_FILE` y `PAGAYA_RELEASE_PASSWORD_FILE`, ejecuta `assembleRelease lintRelease` y restaura el entorno al salir. Desactiva daemon persistente, configuration cache y build cache. La contraseña se lee en memoria durante la configuración Gradle; no activar configuration cache para compilaciones firmadas ni publicar logs de depuración/build scans con credenciales cargadas.

Salida: `app/build/outputs/apk/release/app-release.apk`. El script verifica firma y certificado oficial y conserva una copia en `artifacts/releases/<version>/`, ignorada por Git, junto con SHA-256 y metadatos del commit. Rechaza sobrescribir una entrega existente. Conservar las claves fuera del directorio del proyecto. No ejecutar código de terceros o cambios no revisados con las credenciales de firma cargadas.

## Verificación y publicación

Antes de publicar la versión estable (el piloto rc.3 está autorizado por el propietario sin pasar a main):

1. Comprobar firma con `apksigner verify --verbose --print-certs` y comparar la huella anterior.
2. Revisar con `aapt2 dump badging`: identificador definitivo, versión, SDK mínimo y ausencia de `application-debuggable`.
3. Instalar y abrir release con autorización; probar en el teléfono final y ensayar una actualización con versionCode superior conservando datos sintéticos. **Hecho en el Motorola de pruebas (rc.1 → rc.2); teléfono final pendiente** ([22](22_Validacion_Release_Drive.md)).
4. Validar exportación y restauración real mediante Drive en el selector Android; comprobar que la copia está disponible desde otro dispositivo. Releer desde el proveedor no demuestra subida remota terminada. **Hecho** ([22](22_Validacion_Release_Drive.md)).
5. Cerrar pendientes, decidir licencia del repositorio, revisar documentación/tutorial y aprobar el paso de develop a main.
6. Crear etiqueta de versión y GitHub Release con APK firmado, SHA-256, notas y tutorial. Nunca adjuntar claves, contraseñas ni datos de clientes. Las pruebas y documentación siguen en main.

Publicar una Release no instala actualizaciones automáticamente: el usuario descarga el nuevo APK y lo instala sobre el anterior. Cada nueva entrega debe aumentar versionCode.

## Cambio acordado para el respaldo

La V1 usará el selector de archivos Android también para Drive: la cuenta y subida las gestiona el proveedor instalado. No se configurarán Google Cloud, OAuth propio, cuenta de servicio ni appDataFolder. Integración directa, automatización y retención automática quedan fuera de V1. Esta decisión sustituye el plan anterior de integración directa; la armonización detallada de los documentos 00–11 y la prueba con Drive siguen pendientes. La firma no depende de esa integración. **Actualización:** documentos 00–11 armonizados y prueba con Drive realizada el 2026-10-17; ver [22](22_Validacion_Release_Drive.md).

## Evidencia de esta iteración

- `assembleDebug assembleQa assembleQaRestore assembleRelease lintRelease --no-daemon --no-configuration-cache`: BUILD SUCCESSFUL. Comprobación sin credenciales; release unsigned y las tres variantes de pruebas conservan sus applicationId.
- `scripts/Build-Release.ps1` con rutas privadas locales: BUILD SUCCESSFUL. Generó `app-release.apk`, candidato `0.1.0-rc.3`, versionCode 3.
- `apksigner verify --verbose --print-certs`: Verifies, firma APK v2 válida (compatible con minSdk 26), un firmante RSA 3072; huella exactamente igual a la clave custodiada.
- `aapt2 dump badging`: `com.fielamornoble.pagaya`, etiqueta «Paga Ya!», minSdk 26, targetSdk 36, sin `application-debuggable`. Application y Activity resuelven al namespace interno correcto; autoridades generadas usan el identificador oficial.
- Lint release: 0 errores, 7 avisos de versiones (`OldTargetApi`, dos `AndroidGradlePluginVersion`, tres `GradleDependency`, `NewerVersionAvailable`).
- SHA-256 del APK producido: `58C3C01DE0911043007531C099287A37F40808962EF044CFD4717313C5D408B8`. Corresponde únicamente a este artefacto, no a futuras compilaciones. Ese archivo ya no está en `app/build/outputs`: lo sustituyó rc.2 (SHA-256 `A0E7DEB4E0C04C7CE8A438A5ABA92281964DC9F3E495D74BA6BA660B729B6247`), y a este una compilación unsigned de verificación; ver [22](22_Validacion_Release_Drive.md).
- `git diff --check` sin errores. No se añadieron claves ni contraseñas al repositorio.
- No se ejecutaron pruebas de negocio ni instrumentadas: no cambió código de negocio, Room ni UI. No se instaló la app ni se tocaron datos del teléfono. Instalación, actualización conservando datos y verificación en Redmi siguen pendientes.
- No se hicieron commits, push, merge, etiquetas ni publicaciones.

Incidencias de preparación: Gradle necesitó ejecución fuera del sandbox para acceder a su caché local. La primera compilación señaló una referencia Kotlin DSL sin receptor; se corrigió a `it.applicationId` y las comprobaciones anteriores corresponden a esa configuración corregida.

## Piloto autorizado rc.3

El propietario autorizó una prerelease pública para que la usuaria final la descargue y evalúe. Se publica desde develop, sin merge a main ni declaración de V1 estable. Notas y guía en [v0.1.0-rc.3](releases/v0.1.0-rc.3.md). Cambios funcionales respecto a rc.2: solo textos de respaldo; no se modifican Room ni reglas financieras. Los pendientes de teléfono final, API 26 y recuperación externa de la clave se conservan para el cierre estable. La licencia del código sigue pendiente de decisión; no se asigna una automáticamente.
