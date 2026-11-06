# Verificación 3.13.1

- `versionCode = 31301` y `versionName = 3.13.1`.
- Directorio integrado: 1.019 proveedores, todos con RUC/C.I. y correo en el archivo fuente.
- El campo `supplier` usa autocompletado y no `SelectField`.
- La selección copia RUC y correo a `supplierRuc` y `supplierEmail`.
- La búsqueda de proveedores permite coincidencias por nombre, RUC y correo y normaliza tildes.
- El catálogo incluido se fusiona en instalaciones nuevas y existentes.
- La configuración JSON continúa exportando/importando `catalogs`, por lo que los proveedores también son portables.
- No se requieren cambios SQL adicionales.

La compilación Gradle debe validarse en Android Studio/GitHub Actions si el entorno local no dispone del SDK o acceso a las dependencias.

## Build en este entorno

Se intentó `./gradlew :app:testDebugUnitTest --stacktrace`, pero el wrapper no pudo descargar `gradle-9.3.1-bin.zip` porque este entorno no resuelve `services.gradle.org` (`UnresolvedAddressException`). Por eso el build Android completo debe validarse en Android Studio o GitHub Actions.

Como comprobación adicional, los Kotlin modificados se pasaron por `kotlinc` para detectar errores de parser/sintaxis; no aparecieron coincidencias de `expecting`, `unexpected tokens` ni `syntax error`.
