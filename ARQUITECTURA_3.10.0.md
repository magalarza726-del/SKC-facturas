# Arquitectura 3.10.0

## Objetivo

Mantener Supabase como backend entregable actual, pero evitar que la lógica de la app dependa de un único proveedor de archivos o respaldo.

```text
SKC Facturas
   │
   ├─ Datos compartidos
   │    └─ RemoteDataService
   │         └─ SupabaseRemoteDataAdapter   [actual]
   │
   ├─ Evidencias
   │    └─ StorageService
   │         ├─ Supabase Storage            [actual]
   │         └─ Cloudflare R2               [actual]
   │
   └─ Respaldo
        ├─ Google Drive                     [actual]
        └─ ZIP local                        [actual]
```

## Administración de espacio

El módulo ADMIN separa tres tareas:

1. **Borrar registros funcionales:** facturas, mensajes/transferencias, recordatorios finalizados y auditoría antigua.
2. **Liberar almacenamiento:** eliminar las evidencias asociadas y archivos huérfanos en Supabase Storage/R2.
3. **Purgar tombstones:** eliminar definitivamente marcas de borrado antiguas después del periodo de seguridad.

Los movimientos del libro contable no se eliminan al borrar documentos, porque son la fuente de los saldos históricos.

## Preparación para VPS/escritorio

La futura VPS debe exponer una API HTTPS e implementar un nuevo adaptador de datos/almacenamiento. La aplicación móvil no debe conectarse directamente a SQL Server/PostgreSQL. La misma separación puede reutilizarse en la futura versión de escritorio.
