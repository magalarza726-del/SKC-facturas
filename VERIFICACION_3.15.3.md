# Verificación 3.15.3

## Flujo esperado

1. Tito abre el chat de Dalton y pulsa `Enviar transferencia`.
2. Tito ve `Transferencia enviada` a la derecha con estado inicial `ENVIADO`.
3. Dalton sincroniza/recibe el mensaje. Dalton ve `Transferencia recibida` a la izquierda y el registro pasa a `RECIBIDO`.
4. El acuse `RECIBIDO` se sincroniza a Supabase; Tito también puede verlo como estado de entrega.
5. Dalton confirma o rechaza.
6. Tito recibe una notificación local de confirmación/rechazo cuando su SyncEngine descarga ese cambio.

## Notificaciones

- Solicitud nueva -> destinatario.
- Transferencia nueva -> destinatario.
- Transferencia respuesta a solicitud -> solicitante.
- Confirmación/rechazo -> remitente de la transferencia.
- Solicitud de proveedor -> ADMIN destinatario.
- Edición remota -> destinatario.
- No se notifica el propio eco de sincronización.
- No se repite la misma revisión en cada poll.

## Backend

Ejecutar `supabase/migration-3.15.3.sql` si todavía no se aplicó el hotfix de transferencias entregado el 2026-10-06. Es idempotente.

## Compilación

El proyecto mantiene Gradle wrapper 9.3.1. Si el entorno de compilación no tiene esa distribución en caché y no dispone de red, `gradlew` no puede iniciar; esto es independiente de los cambios Kotlin.
