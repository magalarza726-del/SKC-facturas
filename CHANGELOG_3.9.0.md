# SKC Facturas 3.9.0

## Objetivo

Cerrar el entregable Android alrededor de un flujo simple y mantenible: **Supabase como base principal**, **Cloudflare R2 o Supabase Storage para evidencias**, y **Google Drive / ZIP local como respaldo**. Las integraciones VPS/API y OneDrive quedan preparadas a nivel de adaptadores, pero no se presentan como funciones terminadas.

## Cambios

- Eliminado por completo el desbloqueo por clave de desarrollador.
- Configuración de infraestructura concentrada en una sola pestaña: `Nube y respaldo`.
- Supabase continúa como backend de datos activo de esta versión.
- Evidencias seleccionables entre Cloudflare R2 y Supabase Storage.
- Google Drive como respaldo externo mediante el selector de carpetas de Android.
- Exportación manual de respaldo completo a ZIP local.
- Respaldo diario opcional a Google Drive, programado aproximadamente a las 23:00 con WorkManager.
- Importación y exportación de configuración mediante JSON desde el primer inicio o posteriormente.
- El JSON de configuración no contiene facturas, fotografías, sesión de usuario ni permisos de carpetas de Drive.
- El formato de configuración 3.9.0 conserva Supabase, parámetros de almacenamiento, formularios y preferencias de respaldo.
- Compatibilidad de importación con el formato `skc-app-configuration` de 3.8.x.
- La arquitectura de `RemoteDataServiceFactory` ahora recibe explícitamente el proveedor principal, dejando un punto único para agregar un adaptador VPS/API más adelante.
- Se mantiene el flujo de eliminación segura de facturas: sincronizar → respaldar factura en Drive → registrar borrado en Supabase → liberar evidencia de R2/Supabase Storage → conservar movimiento contable.
- Tráfico HTTP sin cifrar deshabilitado en Android. Una futura API/VPS deberá usar HTTPS.

## No incluido todavía

- Sincronización real con VPS/API propia.
- OneDrive como backend principal.
- Versión de escritorio.

Esos componentes quedan fuera del entregable 3.9.0 para evitar presentar integraciones incompletas como funcionales.
