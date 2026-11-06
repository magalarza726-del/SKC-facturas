# SKC Facturas 3.15.4

## Flujo de solicitudes y transferencias

- Se restaura el flujo solicitado: solicitante -> solicitud -> aprobador -> transferencia de respuesta -> confirmación manual del solicitante.
- Se elimina el acuse automático `RECIBIDO` introducido en 3.15.3. Descargar un mensaje no equivale a confirmar que llegó el dinero.
- Una solicitud (`SOLICITUD_MONTO`) conserva al solicitante como `senderUserId` y al usuario que debe resolverla como `recipientUserId`.
- Al aprobar, el destinatario crea una `TRANSFERENCIA` vinculada mediante `sourceRequestId`, invirtiendo de forma correcta el sentido económico: aprobador/remitente -> solicitante/beneficiario.
- El aprobador puede indicar un monto diferente al solicitado, adjuntar evidencia y escribir un motivo/comentario opcional.
- El solicitante confirma manualmente la recepción con `Confirmar recepción`. Solo entonces la transferencia pasa a `CONFIRMADO` y la solicitud a `COMPLETADA`.
- Registros legacy con estado `RECIBIDO` se consideran todavía pendientes de confirmación manual en la interfaz.

## Notificaciones

- Solicitud nueva: el destinatario recibe una notificación con solicitante, monto y motivo.
- Aprobación: el solicitante recibe `Solicitud aprobada`, incluyendo monto solicitado y monto aprobado cuando son distintos.
- Confirmación: el aprobador recibe una notificación indicando que el solicitante confirmó la recepción.
- Rechazos, mensajes editados y solicitudes de proveedor continúan notificándose.
- Solicitar, enviar, aprobar y confirmar intentan `syncNow()` inmediatamente para reducir la latencia entre usuarios.

## Versión

- `versionCode = 31504`
- `versionName = 3.15.4`
