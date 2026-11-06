# Arquitectura 3.9.0

## Entrega actual

```text
Android
  ├─ Base principal: Supabase
  ├─ Evidencias: Cloudflare R2 o Supabase Storage
  └─ Respaldo: Google Drive y/o ZIP local
```

Supabase gestiona autenticación y datos compartidos. Las evidencias usan `StorageService`, por lo que el proveedor de archivos puede cambiar sin modificar el modelo de factura. Los datos compartidos usan `RemoteDataService`; en 3.9.0 el adaptador registrado es Supabase.

## Evolución prevista

```text
App Android / futura app escritorio
        │
        ├─ RemoteDataService
        │      ├─ SupabaseRemoteDataAdapter   [3.9.0]
        │      └─ VpsApiRemoteDataAdapter     [futuro]
        │
        ├─ StorageService
        │      ├─ Cloudflare R2               [3.9.0]
        │      └─ Supabase Storage            [3.9.0]
        │
        └─ Backup
               ├─ Google Drive                [3.9.0]
               ├─ ZIP local                   [3.9.0]
               ├─ OneDrive                    [futuro]
               └─ VPS/API                     [futuro]
```

Una futura VPS debe exponerse mediante API HTTPS. La app no debe contener usuarios/contraseñas de SQL Server, PostgreSQL ni credenciales administrativas del servidor.

## JSON portátil

El archivo `skc-app-settings` sirve para trasladar parámetros entre instalaciones Android y, a futuro, compartir el mismo perfil de configuración con la aplicación de escritorio. No transporta datos operativos ni archivos.
