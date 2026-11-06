# Verificación 3.15.2

## Configuración compartida

- `supabase-schema.sql`: la policy `skc role read` permite a autenticados leer `appConfig` y `catalogs`.
- Las policies de insert/update reservan `appConfig/catalogs` a ADMIN (salvo bootstrap sin ADMIN configurado).
- `AppViewModel.saveFormSettings` guarda localmente, ejecuta `repo.syncNow()` y recarga el snapshot.
- `AppViewModel.startPoller` sincroniza sesiones conectadas cada 3 segundos.
- `InvoiceScreen` recuerda su estado usando `cfg.formsUpdatedAt/cfg.updatedAt`, por lo que reacciona a una configuración nueva.
- `FormFieldConfig.required` se serializa/deserializa y `formsSignature` lo incluye en detección de cambios.

## Mensajes/proveedores

- Un usuario normal crea un `SOLICITUD_PROVEEDOR` por cada ADMIN activo.
- El nombre/RUC/correo estructurados continúan en `extraFields` para aprobación; el texto visible del mensaje usa la frase solicitada.
- Borrado de conversación usa RPC de borrado físico, no escribe estado `ELIMINADO` y no genera una fila de auditoría sustitutiva. La reconciliación `deleteWhereSyncedAbsent` retira el mensaje del otro dispositivo tras el siguiente pull.
- `skc_can_physical_cleanup_3152()` evita que una app 3.15.2 ejecute limpieza contra funciones SQL antiguas que todavía generaban tombstones.
- `skc_delete_invoice` se redefine como DELETE físico y la selección múltiple ya no exige `SYNCED` después del intento de sincronización; esto corrige el bloqueo “Hay transferencias pendientes de sincronizar” mostrado en Historial.

## Fotos / R2

- Eliminado `Metadata: { sha256 }` del `PutObjectCommand` prefirmado.
- `Content-Type` se fuerza en `signableHeaders` y Android utiliza exactamente el header devuelto por la Edge Function.
- Si HEAD no ofrece checksum, Android descarga una vez el objeto y compara SHA-256.
- Fallback específico R2 403/SignatureDoesNotMatch -> Supabase Storage.
- Reparación automática de adjuntos históricos fallidos por el mismo error cuando conservan `localPath`.

## Compilación

Se intentó `./gradlew compileDebugKotlin --offline`, pero el wrapper no tiene Gradle 9.3.1 almacenado y este entorno no puede resolver `services.gradle.org`. Por ello no fue posible ejecutar el build Android completo aquí.
