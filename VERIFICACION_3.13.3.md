# Verificación 3.13.3

- versionCode 31303 / versionName 3.13.3.
- El asset `providers_skc_4265.json` contiene 1.019 proveedores.
- La migración de proveedores usa el marcador local `bundled_suppliers_seed_version`.
- En una instalación nueva el catálogo completo se carga una vez y se marca como aplicado.
- En actualización desde 3.13.1/3.13.2, si el directorio ya contiene la semilla, solo se crea el marcador y no se reinsertan proveedores eliminados.
- `resetLocalData()` carga nuevamente el directorio inicial de forma explícita.
- No se modificó el contrato SQL/Supabase.
- La validación del ZIP y comprobaciones estáticas se realizaron antes del empaquetado.

- `kotlinc` no reportó errores de parser/sintaxis en los archivos modificados; la compilación aislada muestra referencias Android no disponibles fuera del proyecto, como es esperado.
- Se intentó `:app:compileDebugKotlin`; el wrapper no pudo resolver/descargar Gradle por falta de acceso de red en este entorno.
