# Formato portable SKC

La exportación `skc-portable-export` está diseñada para no depender de Supabase Storage ni Cloudflare R2.

## Identidad de archivos

Los registros conservan:

- `storageProvider`
- `storageKey`
- `storageStatus`
- `sha256`
- `sizeBytes`

No se utiliza una URL pública como identificador permanente.

## Extensión a nuevos proveedores

Para almacenamiento, implementar `StorageService` y registrar el adaptador en `StorageServiceFactory`.

Para un backend de datos futuro, implementar `RemoteDataService`. El adaptador actual es `SupabaseRemoteDataAdapter`.

Un proveedor como Azure Blob puede usar `storageKey` como nombre lógico del blob. Si un proveedor futuro necesita un identificador opaco adicional, puede añadirse a `StorageObjectMetadata.providerObjectId` sin cambiar la clave lógica de SKC.

## Exportación

El ZIP incluye JSON portable y, opcionalmente, los archivos físicos. `reports/export-report.json` registra cualquier archivo que no pudo copiarse para que una migración no se considere completa silenciosamente.
