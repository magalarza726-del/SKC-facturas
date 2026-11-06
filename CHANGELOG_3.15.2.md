# SKC Facturas 3.15.2

- Verificada la propagación de `appConfig` y `catalogs`: guardado ADMIN + sincronización inmediata + pull de dispositivos conectados cada ~3 s.
- El editor de formatos muestra y permite cambiar `Obligatorio/Opcional` directamente en cada campo, además del control dentro del editor.
- Las solicitudes de alta de proveedor llegan a cada ADMIN como mensaje con el texto `USUARIO ha creado el proveedor: NOMBRE, con RUC: (...) y correo: (...).`
- Los mensajes borrados pasan a eliminación física en Supabase mediante `skc_delete_message_physical`; ya no se conserva tombstone ni se crea una fila de auditoría por ese borrado conversacional.
- `skc_admin_delete_entity` y `skc_delete_invoice` pasan a DELETE físico para que la limpieza administrativa libere espacio real; 3.15.2 expone además un marcador de capacidad que evita usar funciones antiguas por accidente.
- La migración elimina tombstones históricas de movimientos eliminables (`transactions`, `messages`, `reminders`, `audit`, `ledger`).
- Corregido R2 `403 SignatureDoesNotMatch`: la Edge Function deja de duplicar metadatos `x-amz-meta-sha256` y firma de forma explícita `Content-Type`.
- Protección para evidencias: si una función R2 antigua sigue produciendo 403 de firma, Android usa Supabase Storage como fallback para no perder la foto.
- Evidencias históricas fallidas por `SignatureDoesNotMatch` se vuelven a poner en cola automáticamente si aún existe el archivo local.

- La eliminación múltiple del Historial ya no se bloquea solo porque una foto o movimiento permanezca con estado local pendiente; intenta sincronizar y luego ejecuta la limpieza física servidor/local por cada selección.
- El borrado permanente de mensajes, facturas y asientos no crea una fila de auditoría por cada elemento eliminado, evitando reemplazar lo borrado por otra fila de base de datos.
