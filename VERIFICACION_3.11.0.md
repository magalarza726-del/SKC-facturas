# Verificación técnica 3.11.0

## Comprobado en este entorno

- versión Android actualizada a 3.11.0 / 31100;
- constantes de respaldo incluyen `NONE`, Google Drive, OneDrive, VPS y ZIP local;
- OneDrive usa el mismo Storage Access Framework persistente que Drive;
- VPS conserva URL totalmente configurable y exige HTTPS;
- conector alternativo soporta VPS/API, PostgreSQL, MySQL, MariaDB, SQL Server y REST genérico mediante HTTPS;
- tokens de VPS y backend alternativo se guardan fuera del JSON portátil;
- respaldo automático generalizado a Google Drive, OneDrive y VPS;
- limpieza de facturas/transferencias ya no depende obligatoriamente de Drive;
- balance de llaves/paréntesis/corchetes verificado en los Kotlin modificados;
- XML del proyecto sin cambios incompatibles;
- no se agregó IP fija de VPS.

## Build Gradle

Se intentó `./gradlew :app:compileDebugKotlin`, pero el entorno no pudo resolver `services.gradle.org` para descargar Gradle 9.3.1. Por ese motivo la compilación Android completa debe verificarse en Android Studio/GitHub Actions con acceso a Internet y SDK 36.

No hay cambios SQL nuevos en 3.11.0.
