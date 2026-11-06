# SKC Facturas 3.7.0

## Objetivo
Esta versión adapta la app a la operación real solicitada por SKC: poder limpiar facturas antiguas, ver la configuración técnica real sin exponer secretos de servidor, mantener respaldos externos simples y dejar el proyecto listo para cambiar de infraestructura mediante adaptadores/API.

## Eliminación real de facturas
- En `Base de datos > Facturas` cada registro tiene ahora **Eliminar**.
- La eliminación está disponible únicamente para un usuario `ADMIN` con el acceso de desarrollador desbloqueado.
- Se eliminan las evidencias físicas del proveedor configurado (Supabase Storage o Cloudflare R2).
- Se elimina la factura de SQLite y se reemplaza el registro remoto por una **tombstone mínima**. Esto libera el espacio importante de archivos y evita que un teléfono que estuvo offline vuelva a crear una factura ya eliminada.
- El movimiento del libro contable se conserva para no alterar saldos históricos.
- Antes de confirmar, la app muestra el espacio estimado a liberar y avisa si no existe un respaldo externo registrado.

## Migración Supabase obligatoria
Para instalaciones existentes se debe ejecutar una vez:

`supabase/migration-3.7.0.sql`

La migración:
- crea la RPC administrativa `skc_delete_invoice`;
- impide que una tombstone sea sobrescrita por un cliente antiguo;
- permite que un ADMIN elimine evidencias antiguas del bucket `skc-evidence` sin incluir una `service_role` en el APK.

## Parámetros reales de base de datos
En `Configuración > Base compartida`, tras ingresar la clave de desarrollador, existe **Ver parámetros reales**. La vista muestra únicamente valores que la aplicación realmente usa:
- proveedor activo;
- base SQLite local y tabla local;
- Project URL;
- REST endpoint;
- Auth endpoint;
- tabla remota `public.skc_events`;
- Publishable key;
- correo/UID autenticado;
- modo y frecuencia de sincronización;
- última sincronización;
- proveedor/destino de almacenamiento;
- URL de API externa/VPS si fue configurada.

La app no inventa ni solicita host, usuario o contraseña directos de PostgreSQL porque el cliente Android trabaja por API. Tampoco contiene `service_role`.

## Acceso de desarrollador
- `Base de datos`, `Base compartida`, `Almacenamiento` y `Respaldos` permanecen protegidos durante la sesión.
- La clave solicitada por SKC se valida mediante SHA-256; el texto de la clave no se guarda literalmente en el código.
- Al cerrar sesión, el acceso de desarrollador vuelve a bloquearse.
- Al desbloquear una pestaña protegida desde Configuración, la app entra automáticamente a la pestaña solicitada; ya no es necesario tocarla por segunda vez.

## Google Drive / OneDrive
- Continúa la conexión mediante el selector nativo de carpetas de Android (`OpenDocumentTree`).
- No se requieren API keys de Google o Microsoft.
- El permiso persistente permite volver a respaldar en la carpeta elegida en futuras aperturas de la app.
- Se puede probar la carpeta, crear el ZIP y desconectarla desde una interfaz simple.

## VPS / infraestructura futura
- No existe IP, puerto ni VPS codificado en la app.
- Solo se conserva un campo opcional **URL base de la API** para respaldos vía servidor.
- Cambiar de VPS no requiere recompilar la aplicación.
- `RemoteDataService` continúa aislando la lógica remota para poder implementar otro backend más adelante sin reescribir las pantallas.

## Versión
- `versionCode`: 30700
- `versionName`: 3.7.0
