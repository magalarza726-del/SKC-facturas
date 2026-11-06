# Verificación 3.13.2

- versionCode 31302 / versionName 3.13.2.
- FORM_SCHEMA_VERSION 501.
- Proveedor predeterminado usa FIELD_LINKED_SELECT.
- CRUD de proveedores integrado dentro de la edición del campo Proveedor.
- Guardado mediante saveCatalogs: SQLite + pendiente de sincronización Supabase.
- Autocompletado por nombre, RUC o correo sigue activo.
- supplierRuc y supplierEmail se completan automáticamente.
- catalogs.suppliers continúa incluido en la configuración JSON.

Se intentó `./gradlew :app:compileDebugKotlin --no-daemon`, pero el entorno no puede resolver services.gradle.org para descargar Gradle 9.3.1. El chequeo independiente con kotlinc no mostró errores de parser/sintaxis.
