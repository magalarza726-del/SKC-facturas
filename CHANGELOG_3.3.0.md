# 3.3.0

- Corrige la verificación de evidencias optimizadas: tamaño y SHA-256 se recalculan sobre el archivo final inmediatamente antes de subir.
- R2 verifica tamaño y checksum; si el HEAD no coincide, realiza una comprobación de descarga antes de declarar error.
- La Edge Function R2 guarda `sha256` como metadata del objeto.
- Los intentos fallidos de R2 limpian el objeto remoto cuando la verificación no es válida.
- Añade `Reintentar evidencias fallidas` cuando existe una copia local recuperable.
- Añade descargas por streaming en `StorageService`.
- Añade `storageObjectId` opcional para proveedores futuros que utilicen identificadores opacos.
- Añade `RemoteDataService` con adaptador Supabase para desacoplar la sincronización del proveedor de datos.
- Añade exportación portable ZIP de datos y exportación completa de datos + archivos.
- La exportación elimina rutas locales dependientes del teléfono, conserva proveedor/clave/checksum y genera reporte de archivos fallidos.
- Incluye la plantilla Excel activa dentro de la exportación completa.
- Mantiene únicamente Supabase Storage y Cloudflare R2 como proveedores activos de almacenamiento.
