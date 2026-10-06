# 11 — Fuentes técnicas y alcance de la investigación

Consulta para esta documentación: **5 de octubre de 2026, America/Bogota**. Se utilizaron fuentes oficiales de los fabricantes/proyectos. Las páginas pueden cambiar; revisar requisitos, versiones, distribución y permisos al implementar. No copiar números de versiones de ejemplos como si fueran una matriz de compatibilidad validada.

Las fuentes respaldan capacidades y restricciones. La elección de stack, límites de importe, retención, UX y alcance son decisiones propias para este caso, no recomendaciones universales atribuibles a Google u otro proveedor.

| Fuente oficial | Uso en la decisión |
| --- | --- |
| [Room sobre SQLite](https://developer.android.com/training/data-storage/room) | Persistencia estructurada, consultas y capa de acceso Android |
| [Migraciones Room](https://developer.android.com/training/data-storage/room/migrating-db-versions) | Evolución del esquema y riesgo de fallback destructivo |
| [Recomendaciones de arquitectura Android](https://developer.android.com/topic/architecture/recommendations) | Separación de UI/datos, estado observable y repositorio |
| [Datos offline en PWA](https://web.dev/learn/pwa/offline-data) | Persistencia por origen y control del almacenamiento por navegador/usuario |
| [Plugins Capacitor](https://capacitorjs.com/docs/plugins) | Integración con capacidades nativas y mantenimiento de plugins |
| [Plataforma nativa en React Native](https://reactnative.dev/docs/native-platform) | Módulos y componentes nativos para APIs de plataforma |
| [SQLite en Flutter](https://docs.flutter.dev/cookbook/persistence/sqlite) | Persistencia mediante paquete sqflite |
| [SQLite Online Backup API](https://sqlite.org/backup.html) | Alternativa de snapshot físico seguro |
| [SQLite WAL](https://sqlite.org/wal.html) | Riesgo de copiar un archivo de base abierto sin su estado asociado |
| [Selector de documentos Android](https://developer.android.com/training/data-storage/shared/documents-files) | Exportación/importación elegida por la persona |
| [Respaldo del sistema Android](https://developer.android.com/identity/data/autobackup) | Configuración explícita y diferencias de transferencias según plataforma/fabricante |
| [Autorización Android](https://developer.android.com/identity/authorization) | Acceso Google opcional, separado de autenticación de la app |
| [AuthorizationClient](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationClient) | Autorizar, limpiar token y revocar permisos |
| [Scopes de Drive](https://developers.google.com/workspace/drive/api/guides/api-specific-auth) | Permiso appdata acotado y clasificación de sensibilidad |
| [appDataFolder](https://developers.google.com/workspace/drive/api/guides/appdata) | Espacio oculto, acceso por app y restricciones de sus archivos |
| [Drive about.get](https://developers.google.com/workspace/drive/api/reference/rest/v3/about/get) | Obtener identidad efectiva de cuenta con campos solicitados |
| [Subidas Drive](https://developers.google.com/workspace/drive/api/guides/manage-uploads) | Transporte y cargas reanudables |
| [Límites de uso Drive](https://developers.google.com/workspace/drive/api/guides/limits) | Cuotas y tratamiento de errores/reintentos |
| [Verificación de desarrolladores Android](https://developer.android.com/developer-verification) | Riesgo cambiante de distribución fuera de tiendas y requisitos regionales |

## Aspectos aún no demostrados

- Compilación conjunta de versiones concretas del stack: se hace en fase 0/1.
- Autorización y restauración con la cuenta y firma definitivas: se ensayan en fase 0/6.
- Tiempos, memoria, tamaño APK y compatibilidad con el teléfono de la vendedora: requieren medición real.
- Condiciones exactas de publicación/verificación y costos para el canal final: revisar antes de distribuir.
- Comprensión de los flujos y cumplimiento de diez segundos: requieren sesión con la usuaria.

No se han creado proyectos Google, solicitado permisos Drive, accedido a archivos personales, instalado SDKs ni producido un APK como parte de esta iteración documental.
