# Cloudflare R2 para SKC 3.15.2

## Requisitos

Bucket privado R2, recomendado `skc-files`, y credenciales S3 limitadas a `Object Read & Write` únicamente sobre ese bucket.

Secretos en Supabase Edge Functions:

- `R2_ACCOUNT_ID`: solo el Account ID, no la URL completa.
- `R2_ACCESS_KEY_ID`
- `R2_SECRET_ACCESS_KEY`
- `R2_BUCKET`: `skc-files`
- `R2_ALLOWED_PREFIX`: `skc`

No almacenes estas credenciales en Android.

## Desplegar la función

La función está en:

```text
supabase/functions/skc-storage-r2/index.ts
```

Puedes reemplazar el código de la función existente `skc-storage-r2` desde el editor de Supabase y volver a desplegarla.

**Importante en 3.15.2:** la URL PUT ya no firma `x-amz-meta-sha256`. AWS SDK v3 puede incorporar esos metadatos dentro de la propia URL prefirmada; reenviarlos además como cabecera desde Android provoca `403 SignatureDoesNotMatch` en R2. La función 3.15.2 firma únicamente `Content-Type` y la app verifica SHA-256 descargando el objeto cuando R2 no devuelve checksum.

Si la función valida el JWT internamente con `supabase.auth.getUser()`, mantén desactivada la verificación legacy del gateway tal como quedó configurada durante la instalación de R2.

## Prueba

En la app:

```text
Configuración
→ Almacenamiento
→ Cloudflare R2
→ Guardar almacenamiento
→ Probar conexión
```

Resultado esperado:

```text
Cloudflare R2 conectado
Bucket: skc-files
Lectura: OK
Escritura: OK
```

Luego activa `Optimizar imágenes antes de subir` y prueba una imagen nueva. La app recalcula tamaño y SHA-256 del archivo final inmediatamente antes de subirlo y verifica el objeto remoto.

Como protección adicional, 3.15.2 detecta específicamente `403/SignatureDoesNotMatch`: si todavía está desplegada una función R2 antigua, la evidencia se intenta guardar en Supabase Storage en lugar de dejar la foto perdida en estado `failed`.

## Compatibilidad

Cambiar el proveedor predeterminado no mueve ni rompe archivos anteriores. Cada evidencia conserva `storageProvider` y `storageKey`.

La exportación portable de la app puede copiar tanto los metadatos como los archivos físicos a un ZIP neutral para una futura migración a Azure Blob, S3 u otro proveedor.

## 3.10.0 · Administración de espacio

La versión 3.10.0 añade la acción `list` exclusiva para usuarios `ADMIN`. Sirve para inventariar el prefijo SKC del bucket y detectar archivos huérfanos desde la app.

Si ya tenías `skc-storage-r2` desplegada, vuelve a desplegar el archivo:

`supabase/functions/skc-storage-r2/index.ts`

No cambia los secretos requeridos de R2.
