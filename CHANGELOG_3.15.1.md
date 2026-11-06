# SKC Facturas 3.15.1

## Corrección de compilación

- Corregida la inferencia de tipos en `LinkedTableConfig.fromJson()` (`Models.kt`).
- `rows` ahora se declara explícitamente como `List<Map<String, String>>`.
- `buildMap` y `buildList` usan genéricos `<String, String>` y `<String>` para evitar que Kotlin infiera `List<Any>`.
- No cambia el formato JSON ni la compatibilidad con configuraciones creadas en 3.15.0.
- Se incrementó `versionCode` a 31501 y `versionName` a 3.15.1.

## Nota de verificación

El entorno de generación no dispone localmente de Gradle 9.3.1 y no tiene acceso de red para descargarlo, por lo que no fue posible ejecutar el build Android completo. La corrección ataca directamente el error de tipo reportado por el compilador.
