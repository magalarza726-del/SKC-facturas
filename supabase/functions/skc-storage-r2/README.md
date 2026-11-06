# skc-storage-r2

Edge Function que mantiene las credenciales permanentes de Cloudflare R2 fuera de Android y entrega URLs prefirmadas de corta duración.

Secretos requeridos:

- `R2_ACCOUNT_ID`
- `R2_ACCESS_KEY_ID`
- `R2_SECRET_ACCESS_KEY`
- `R2_BUCKET`

Opcionales:

- `R2_ENDPOINT`
- `R2_ALLOWED_PREFIX` (por defecto `skc`)

La función usa `SUPABASE_URL` y `SUPABASE_ANON_KEY`, disponibles en el entorno de Supabase Edge Functions.


## 3.15.2

Las subidas prefirmadas PUT firman solo `Content-Type`. No devuelvas `x-amz-meta-*` como headers del cliente si esos valores fueron incluidos/hoisted por el presigner; R2 puede responder `SignatureDoesNotMatch`. Despliega este `index.ts` después de actualizar la app.
