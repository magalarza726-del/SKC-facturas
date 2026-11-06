# Verificación 3.9.0

## Comprobaciones realizadas

- No existe hash ni flujo de clave de desarrollador en `app/src/main`.
- `Base de datos` continúa restringida por rol ADMIN, pero ya no solicita una segunda clave.
- El primer inicio permite importar JSON o configurar Supabase directamente.
- El JSON 3.9.0 excluye facturas, fotografías, sesión de autenticación, tokens VPS y URI/permisos de Google Drive.
- El selector de evidencias conserva los adaptadores reales Supabase Storage / Cloudflare R2.
- El respaldo manual a Google Drive usa `OpenDocumentTree` y permiso persistente de Android.
- El ZIP local usa `CreateDocument` y la exportación portable existente.
- El respaldo diario usa WorkManager, red conectada y evita duplicar más de un respaldo automático por fecha.
- La eliminación de facturas mantiene el respaldo individual obligatorio en Google Drive antes del borrado remoto.
- `android:usesCleartextTraffic` quedó en `false`.
- Se verificó que los Kotlin modificados no presentan errores de parseo con el compilador Kotlin local.
- Se verificó integridad del ZIP final con `unzip -t`.

## Limitación del entorno

No fue posible ejecutar el build Android completo porque Gradle Wrapper intenta descargar Gradle 9.3.1 desde `services.gradle.org` y este entorno no tiene resolución/acceso de red. El build final debe ejecutarse en Android Studio o GitHub Actions con JDK 17 y Android SDK 36.
