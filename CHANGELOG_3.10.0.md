# Changelog 3.10.0

## Nuevo: administración de espacio para ADMIN

- Nueva pestaña `Base de datos → Espacio`.
- Reporte real de filas y tamaño de `public.skc_events` en Supabase.
- Inventario remoto de Supabase Storage y Cloudflare R2.
- Detección y eliminación de archivos huérfanos.
- Eliminación de transferencias con respaldo previo en Drive.
- Limpieza por antigüedad de facturas, transferencias/solicitudes finalizadas, recordatorios finalizados y auditoría.
- Purga de tombstones antiguos con periodo mínimo de seguridad.
- Usuarios, catálogos, configuración y libro contable quedan protegidos contra limpieza accidental.

## Backend

- Nueva migración `supabase/migration-3.10.0.sql`.
- RPC ADMIN genérico de eliminación segura mediante tombstones.
- RPC de reporte real de espacio en Supabase.
- RPC de purga de tombstones.
- Política de lectura de Storage ampliada para que ADMIN pueda inventariar objetos y localizar huérfanos.
- Edge Function de R2 ampliada con acción `list` exclusiva para ADMIN.

## Compatibilidad

- Los RPC `skc_can_delete_invoice` y `skc_delete_invoice` se mantienen como wrappers compatibles.
- La eliminación de facturas sigue preservando movimientos contables.
