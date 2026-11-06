# SKC Facturas 3.11.0

## Conectividad

- Se añadió un conector alternativo configurable y comprobable por HTTPS.
- Proveedores visibles: VPS/API propia, PostgreSQL vía API, MySQL vía API, MariaDB vía API, Microsoft SQL Server vía API y REST genérico.
- El token del conector alternativo se guarda separado del JSON de configuración.
- Supabase se mantiene como backend productivo activo; no se simula un cambio de backend sin el contrato de sincronización correspondiente.

## Respaldo

- El respaldo ahora puede estar completamente desactivado (`Sin respaldo`).
- Google Drive continúa disponible mediante Storage Access Framework.
- OneDrive queda habilitado de forma real mediante el selector de carpetas de Android.
- VPS/API de respaldo queda visible y funcional con URL HTTPS, token opcional, prueba `/health` y carga `/backup`.
- ZIP local permanece disponible como exportación manual.
- El respaldo automático diario ahora funciona con Google Drive, OneDrive o VPS/API.
- La opción “incluir fotografías/PDF” se aplica a todos los respaldos externos.

## Limpieza ADMIN

- Borrar facturas, transferencias o limpiar en lote ya no exige Google Drive.
- Si se selecciona un respaldo remoto, se intenta completar antes de borrar y un fallo detiene la eliminación.
- Si se selecciona `Sin respaldo` o ZIP local, la eliminación puede continuar sin copia automática.

## Configuración portátil

- El JSON incluye la configuración no secreta del conector alternativo y la URL de VPS de respaldo.
- No exporta tokens ni permisos de carpetas Drive/OneDrive.

## Versión

- `versionCode`: 31100
- `versionName`: 3.11.0
