# Verificación 3.15.0

- `versionCode = 31500`, `versionName = 3.15.0`.
- `DATA_SCHEMA_VERSION = 351`, `FORM_SCHEMA_VERSION = 610`.
- Revisión estática: balance de llaves/paréntesis correcto en Models, Repository, ViewModel y AppRoot.
- Se intentó `./gradlew testDebugUnitTest --offline`; el wrapper no pudo ejecutar porque Gradle 9.3.1 no existe localmente y el entorno no permite descargar `services.gradle.org`.
- La migración `supabase/migration-3.15.0.sql` debe aplicarse para el borrado completo de asientos `ledger` en Supabase.
