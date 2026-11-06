# Verificación SKC Facturas 3.7.0

## Revisión realizada

- `versionCode=30700` y `versionName=3.7.0`.
- La clave de desarrollador solicitada se valida contra SHA-256; el texto literal no aparece en `app/src`.
- El hash configurado corresponde a la clave entregada por SKC.
- No existe la IP `38.247.163.204` ni puertos del VPS anterior dentro de `app/src`.
- Google Drive y OneDrive continúan usando `OpenDocumentTree` + permiso persistente de Android.
- La URL de API externa/VPS queda configurable en tiempo de ejecución; no existe URL fija.
- `Base de datos`, `Base compartida`, `Almacenamiento` y `Respaldos` requieren desbloqueo de desarrollador y las operaciones de mantenimiento además exigen rol ADMIN.
- La eliminación permanente borra evidencias mediante el proveedor real (`Supabase Storage` o `R2`), crea una tombstone remota y borra la copia SQLite de la factura.
- El libro contable no se elimina al limpiar la factura, evitando modificar saldos históricos.
- `SyncEngine` reconoce tombstones y elimina copias locales para impedir que un dispositivo offline resucite registros antiguos.
- Se añadió `supabase/migration-3.7.0.sql` con RPC administrativa, protección de tombstone, permiso de borrado de evidencia para ADMIN y lectura de tombstones para sincronización segura.
- `Configuración > Base compartida > Ver parámetros reales` muestra los valores técnicos usados por la aplicación y no inventa credenciales directas de PostgreSQL.

## Comprobación de sintaxis

Se ejecutó `kotlinc` sobre los archivos Kotlin modificados y se filtraron errores de parser (`expecting`, `unexpected tokens`, `syntax`, `redeclaration`). No se detectaron errores de sintaxis.

## Build Android

Se intentó ejecutar:

```text
./gradlew test --no-daemon
```

El wrapper intentó descargar `gradle-9.3.1-bin.zip`, pero este entorno no tiene acceso de red a `services.gradle.org`, por lo que no fue posible completar la compilación Android aquí. No se trata de un error reportado por el código fuente: el proceso se detuvo antes de cargar Gradle.

En Android Studio se recomienda ejecutar, en este orden:

1. Sync Project with Gradle Files.
2. Build > Make Project.
3. Ejecutar `supabase/migration-3.7.0.sql` una vez en el proyecto Supabase actual.
4. Probar con una factura de prueba y una evidencia pequeña antes de limpiar documentos reales.

## Caso de prueba recomendado

1. Iniciar sesión como ADMIN.
2. Abrir Base de datos y desbloquear acceso de desarrollador.
3. Crear respaldo en Google Drive o OneDrive.
4. Elegir una factura de prueba con evidencia y tocar Eliminar.
5. Confirmar que desaparece de la lista y que el saldo del usuario no cambia.
6. Sincronizar otro teléfono y confirmar que la factura tampoco reaparece allí.
7. Verificar en Storage que la evidencia física fue eliminada.
