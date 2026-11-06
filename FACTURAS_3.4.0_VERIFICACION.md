# Verificación interna — SKC Facturas 3.4.0

## Revisiones realizadas
- Comparación de errores del compilador Kotlin entre 3.3.6 y 3.4.0 sin dependencias Android: no aparecieron errores sintácticos nuevos; la única referencia adicional no resuelta es `ColumnScope`, perteneciente a Compose y cubierta por `androidx.compose.foundation.layout.*` en la compilación Android real.
- Compilación JVM aislada de `Models.kt`, `TimeUtils.kt`, `CodeUtils.kt`, `SupplierCsvImporter.kt` e `InvoiceRules.kt` con stubs de `org.json`: OK.
- Prueba ejecutable del importador CSV con separador `;` y `,`, campos entre comillas y nombre con coma: OK.
- Prueba ejecutable de reglas de factura: comprobante prometido sin archivo = error; nota de crédito sin # de Documento = error; factura normal sin # Documento = válida.
- Prueba ejecutable de reverso contable: un EGRESO + su reverso INGRESO y un INGRESO + su reverso EGRESO producen saldo neto 0.
- Auditoría de RLS: `transactions`, `messages` y `ledger` ya permiten el flujo usuario→ADMIN necesario; no hace falta migración SQL.
- Revisión de staging de evidencias: se limpia la primera copia temporal si aparece diálogo de posible duplicado, evitando archivos huérfanos.
- Reverso por revocación usa ID determinista `reverso-<ledgerId>` para evitar doble reverso si dos ADMIN procesan la misma solicitud desde dispositivos distintos.
- La plantilla Excel 3.3.6 conserva exactamente el mismo SHA-256: `9d21a039f7b59fa6b504886f48b5a1abc9eb5bf73687fe8ccb5f0e5f28da5826`.

## Limitación del entorno
No fue posible ejecutar `./gradlew test`/`assembleDebug` porque el wrapper requiere descargar Gradle 9.3.1 y este entorno no dispone de esa distribución ni acceso de red para obtenerla. Por eso la compilación Android final debe ejecutarse en Android Studio. La revisión estática compara contra la base 3.3.6 y las piezas puras modificadas sí fueron compiladas/ejecutadas por separado.
