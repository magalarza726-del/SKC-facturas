# Arquitectura 3.12.0

```text
Android
├── SQLite local/offline
├── Datos compartidos activos
│   └── Supabase
├── Conectores principales configurables
│   ├── Supabase
│   ├── VPS/API
│   ├── PostgreSQL vía API
│   ├── MySQL vía API
│   ├── MariaDB vía API
│   ├── SQL Server vía API
│   └── REST genérico
├── Evidencias
│   ├── Cloudflare R2
│   └── Supabase Storage
└── Respaldos opcionales y múltiples
    ├── Google Drive
    ├── OneDrive
    ├── VPS/API
    └── ZIP local
```

`primaryDataProviders` y `backupProviders` son listas. Los conectores SQL no usan credenciales SQL dentro del APK: se configuran contra un bridge/API HTTPS. En 3.12.0 Supabase continúa siendo el adaptador productivo de sincronización para no romper el entregable actual mientras se desarrolla el contrato de los demás backends.

Los formatos de captura se guardan dentro de `AppConfig` y se sincronizan como configuración compartida. Esto permite reutilizar la misma definición en una futura versión de escritorio.
