# SKC Facturas 3.12.3

## Corrección de sincronización de evidencias

- Se separa el estado de sincronización del registro en Supabase del estado de sus evidencias.
- Un fallo de Cloudflare R2 o Supabase Storage ya no convierte una factura/transferencia válida en `SYNC_ERROR` si el registro sí fue guardado correctamente en Supabase.
- El cuadro de sincronización sigue contando errores reales de datos y muestra por separado `Evidencias: N` cuando hubo problemas de archivos.
- Las evidencias cuyo archivo local ya no existe pasan a `unavailable` en vez de provocar reintentos infinitos.
- Las facturas `REVOCADA` ya no intentan volver a subir evidencias locales durante la sincronización de la revocación.
- Los errores históricos creados por versiones anteriores (`Storage HTTP 403`, `SignatureDoesNotMatch`, `No se encontró evidencia local`) se normalizan automáticamente al sincronizar.
- Si un error histórico conserva una copia local, la app intenta una vez más con la configuración de almacenamiento actual.
- Si no existe copia local, se conserva el registro y la referencia remota, pero la evidencia queda marcada como no disponible.

## Compatibilidad

- No requiere cambios SQL adicionales en Supabase.
- No modifica las migraciones de eliminación/limpieza existentes.
