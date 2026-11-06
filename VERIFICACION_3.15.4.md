# Verificación 3.15.4

## Flujo funcional esperado

Ejemplo Dalton / Tito:

1. Dalton pulsa `Solicitar transferencia`, solicita $100 y guarda.
2. La solicitud se sincroniza inmediatamente; Tito recibe la notificación al siguiente pull disponible.
3. Tito abre la solicitud y pulsa `Revisar y aprobar`.
4. Tito puede enviar $100 u otro monto, adjuntar evidencia y escribir un comentario/motivo opcional.
5. Se crea una transferencia vinculada con Tito como remitente y Dalton como beneficiario. La solicitud queda `RESPONDIDA`.
6. Dalton recibe una notificación `Solicitud aprobada`. Si el monto cambió, el aviso muestra solicitado vs. aprobado.
7. Dalton pulsa `Confirmar recepción`.
8. La transferencia pasa a `CONFIRMADO`; la solicitud original pasa a `COMPLETADA`.
9. Tito recibe la notificación de confirmación.

## Compatibilidad Supabase

Se conserva `supabase/migration-3.15.3.sql` / hotfix RLS del 2026-10-06. No se requiere una nueva migración para 3.15.4. El destinatario necesita permiso de UPSERT sobre un mensaje existente para confirmar o rechazar sin poder falsificar participantes.

## Build

Se intentó `./gradlew :app:compileDebugKotlin --offline`. El wrapper intenta descargar Gradle 9.3.1 y el entorno no tiene acceso de red, por lo que no fue posible completar el build Android aquí. Se verificaron estáticamente las firmas/call-sites modificadas y no quedan referencias al acuse automático `queueIncomingTransferReceipt`.
