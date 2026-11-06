# Verificación 3.6.0

## Validaciones realizadas
- AndroidManifest y recursos XML parseados correctamente.
- Se verificó la presencia de las rutas de código para Google Drive, OneDrive y API VPS.
- Se verificó el bloqueo de desarrollador y que la clave solicitada no queda almacenada como texto plano; la app compara un SHA-256.
- El token/API key opcional de la API VPS se guarda como metadato local separado y no se incorpora al respaldo portable.
- La integración VPS de esta versión fue reemplazada por el enfoque desacoplado de 3.6.1.
- Los archivos Kotlin modificados se pasaron por el parser de `kotlinc`; no se detectaron errores de sintaxis del tipo `expecting`, `unexpected tokens` o similares. Las referencias Android/Compose no pueden resolverse sin el SDK y dependencias del proyecto.

## Compilación completa
Se intentó ejecutar `./gradlew :app:assembleDebug`, pero este entorno no tiene Gradle 9.3.1/Android SDK en caché y no puede descargar el wrapper desde Internet. El fallo ocurrió antes de que Gradle llegara a compilar el código.

Para la comprobación final de toolchain, abre el proyecto en Android Studio con Internet y ejecuta **Build > Make Project** o `./gradlew :app:assembleDebug`.
