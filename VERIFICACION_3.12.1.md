# Verificación 3.12.1

La configuración JSON usa schemaVersion 5. `appConfig` y `localSettings` se serializan completos mediante sus modelos oficiales. También se incluyen catálogos, tokens de conectores y, cuando existe, la plantilla Excel personalizada codificada en Base64.

Se conserva compatibilidad de importación con `skc-app-settings` schemaVersion <= 4 y `skc-app-configuration` de 3.8.x. Las sesiones de autenticación y los datos operativos no forman parte del archivo de configuración.
