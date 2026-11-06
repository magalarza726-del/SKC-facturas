# Verificación técnica 3.8.0

## Alcance revisado

1. Supabase como backend principal.
2. Edición de facturas.
3. Respaldo individual obligatorio en Google Drive antes de eliminar.
4. Eliminación segura con tombstones.
5. Interfaz simplificada.
6. Integraciones secundarias ocultas del flujo principal.

## Revisiones estáticas realizadas

- La clave de desarrollador `AlfaOmega777` no aparece en texto plano; se valida contra SHA-256.
- No existe IP fija de VPS dentro de `app/src`.
- Se eliminó la función duplicada `visibleTransactions()` de `AppViewModel`.
- La edición preserva identidad de la factura y actualiza el ledger relacionado en la misma transacción SQLite.
- Las evidencias existentes se preservan; las nuevas se agregan y se sincronizan mediante el mecanismo normal de Storage.
- La eliminación se invoca únicamente mediante `backupAndDeleteInvoice()`, que primero crea el respaldo individual en Google Drive.
- El escritor del ZIP cierra/flushea correctamente el archivo temporal antes de calcular SHA-256.
- El respaldo de una factura falla de forma cerrada: si una evidencia no se puede descargar o verificar, el ZIP parcial se elimina y la factura no se borra.
- La tombstone remota se crea antes de limpiar archivos, evitando que un dispositivo offline resucite la factura.

## Supabase requerido

Para una instalación existente, ejecutar una sola vez:

`supabase/migration-3.7.0.sql`

Después puede ejecutarse `supabase/verify-3.8.0.sql`, que solo consulta el estado y no modifica datos.

## Build

Se intentó ejecutar `./gradlew :app:compileDebugKotlin`, pero el entorno actual no puede resolver `services.gradle.org`, por lo que Gradle Wrapper 9.3.1 no pudo descargarse. No se declara un build exitoso en este entorno. La validación final debe hacerse en Android Studio con `Build → Make Project`.
