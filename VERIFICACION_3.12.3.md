# Verificación 3.12.3

## Validaciones realizadas

- `versionCode = 31203` y `versionName = 3.12.3`.
- `STORAGE_UNAVAILABLE` definido en `Models.kt`.
- La sincronización marca como `SYNCED` un registro aceptado por Supabase aunque una evidencia falle.
- Los fallos de evidencia se conservan en `Attachment.storageStatus/storageError`.
- Las evidencias locales ausentes dejan de generar un `syncError` permanente del registro.
- Las facturas revocadas no reintentan la carga de evidencias.
- Se incluyó reparación automática de estados heredados de versiones anteriores.
- El análisis sintáctico local de los Kotlin modificados no reportó errores de parser.
- `./gradlew :app:compileDebugKotlin` fue intentado, pero el entorno no pudo resolver `services.gradle.org` para descargar Gradle 9.3.1; por eso el build Android completo debe validarse en Android Studio/GitHub Actions.
