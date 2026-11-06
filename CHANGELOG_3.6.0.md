# SKC Facturas 3.6.0

## Respaldos externos
- Conexión con Google Drive y OneDrive mediante el selector de carpetas de Android (Storage Access Framework), sin guardar contraseñas de Google/Microsoft en la app.
- Permiso persistente a la carpeta elegida y prueba real de escritura.
- Creación directa de respaldo portable ZIP en Drive/OneDrive.
- Se añadió soporte inicial para respaldo mediante API de VPS.
- Esta implementación fue corregida en 3.6.1 para eliminar cualquier IP o servidor preconfigurado.
- El token/API key del VPS se guarda como meta local y no se incluye en los respaldos exportados.

## Seguridad / operación
- Base de datos, Base compartida, Almacenamiento y Respaldos quedan detrás de un desbloqueo de desarrollador por sesión.
- El desbloqueo se pierde al cerrar sesión o al reiniciar el proceso de la app.
- Se mantiene compatibilidad con Supabase y R2 existentes.

## VPS
- La integración se realiza exclusivamente a través de una API configurable.
- No se requiere ni se asume una dirección fija del servidor.
