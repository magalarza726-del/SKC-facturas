# Verificación 3.14.0

## Validaciones realizadas

- El importador `FormDataImporter.kt` compila de forma aislada con `kotlinc`.
- CSV probado con separadores, campos entre comillas y jerarquía Proyecto/Subproyecto/Proyecto 2.
- XLSX de ejemplo probado con varias columnas y filas.
- Se verificó eliminación de filas exactamente duplicadas.
- Se revisó serialización/deserialización de `LinkedTableConfig` y `LinkedTableColumnConfig` mediante pruebas unitarias añadidas al proyecto.
- Revisión estática de los archivos Kotlin modificados: sin errores de sintaxis detectados.

## Limitación del entorno de verificación

No fue posible ejecutar la compilación Android completa porque el wrapper del proyecto requiere Gradle 9.3.1 y esa distribución no está disponible localmente en el entorno; el acceso de red para descargarla tampoco está habilitado. Por ello no se declara una compilación APK completa como aprobada en este entorno.
