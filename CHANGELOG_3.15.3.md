# SKC Facturas 3.15.3

## Mensajes y transferencias

- Se restaura el estado de entrega `RECIBIDO` que ya estaba contemplado por el modelo, pero no se asignaba en 3.15.2.
- Cuando el dispositivo destinatario recibe desde Supabase una `TRANSFERENCIA` con estado `ENVIADO`, la guarda como `RECIBIDO` y sincroniza ese acuse al servidor.
- La conversación distingue visualmente `Transferencia enviada` / `Transferencia recibida` y `Solicitud enviada` / `Solicitud recibida`.
- Si todavía llega un registro `ENVIADO` en el primer frame, el destinatario lo presenta como `RECIBIDO` mientras el motor sincroniza el acuse real.
- Se incluye `supabase/migration-3.15.3.sql`, equivalente al hotfix RLS usado en producción para permitir de forma segura que el destinatario actualice un mensaje existente mediante UPSERT.

## Notificaciones

- Nuevo canal Android `Mensajes y transferencias SKC` con sonido y vibración.
- Se notifican solicitudes de transferencia nuevas, transferencias recibidas, respuestas a solicitudes, confirmaciones, rechazos, solicitudes de proveedor y ediciones remotas.
- El estado `RECIBIDO` funciona como acuse de entrega y no genera vibración adicional.
- Las notificaciones se deduplican por revisión del mensaje para que el sondeo de 3 segundos no repita avisos.
- Al tocar una notificación se abre el módulo Mensajes.
- Se usa el mismo motor de sincronización tanto en foreground como en `SyncWorker`, por lo que las notificaciones también pueden generarse durante la sincronización en segundo plano.

## Alcance del tiempo real

- Con la app abierta, el poller existente consulta Supabase aproximadamente cada 3 segundos.
- Con la app en segundo plano, Android WorkManager conserva su intervalo periódico de 15 minutos. Esto no es push FCM; una notificación instantánea con la app cerrada requeriría incorporar un proveedor push (por ejemplo FCM) y credenciales del proyecto.
