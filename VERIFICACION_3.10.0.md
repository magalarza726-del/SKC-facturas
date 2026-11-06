# Verificación 3.10.0

## Comprobaciones realizadas

- `versionName = 3.10.0` y `versionCode = 31000`.
- La pantalla de administración de espacio solo se alcanza desde `Base de datos`, que ya exige rol `ADMIN`.
- No se añadieron `service_role`, Secret keys ni credenciales SQL al APK.
- Los tipos permitidos por la RPC de limpieza son `transactions`, `messages`, `reminders` y `audit`.
- `users`, `catalogs`, `appConfig` y `ledger` quedan fuera de la allowlist de borrado.
- Supabase Storage y R2 implementan listado remoto para detectar huérfanos.
- Los ZIP de respaldo siguen disponibles antes de limpiezas masivas de documentos.
- La app mantiene tombstones para evitar resurrección desde dispositivos offline y permite purgarlos solo después de un mínimo de 30 días; la interfaz usa 90 días.

## Compilación

Se ejecutaron comprobaciones estáticas del código modificado y validación de XML/ZIP. La compilación Gradle completa depende de disponer de Android SDK/Gradle en un entorno con acceso a las dependencias.
