# Arquitectura 3.11.0

```text
                         SKC Facturas Android
                                 |
                  +--------------+--------------+
                  |                             |
          Datos productivos                 Evidencias
                  |                             |
              Supabase                R2 / Supabase Storage
                  |
          SQLite local/offline

Conector alternativo configurable (aún no activo para sync)
  - VPS/API
  - PostgreSQL vía API
  - MySQL vía API
  - MariaDB vía API
  - SQL Server vía API
  - REST genérico

Respaldo opcional
  - ninguno
  - Google Drive
  - OneDrive
  - VPS/API
  - ZIP local
```

## Regla principal

La aplicación Android no recibe usuario/contraseña de una base SQL remota. PostgreSQL, MySQL, MariaDB y SQL Server se conectan mediante una API HTTPS del servidor. Esto permite cambiar VPS o motor de base sin distribuir credenciales dentro del APK.

## Qué está activo en 3.11.0

Supabase continúa siendo el `RemoteDataService` productivo. El conector alternativo permite guardar los parámetros y hacer una prueba real de conectividad (`GET /health`). El cambio de sincronización a otro backend requiere implementar el contrato de datos del servidor; la UI ya queda preparada para ello.

Los destinos de respaldo Google Drive, OneDrive y VPS sí son utilizables en esta versión.
