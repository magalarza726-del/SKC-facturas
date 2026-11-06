# SKC Facturas Android 3.5.3

## Formularios
- Persistencia endurecida: el repositorio verifica el guardado leyendo nuevamente SQLite antes de devolver éxito.
- El editor mantiene borradores por módulo y solo se recarga desde almacenamiento después de un guardado confirmado.
- Se añadió `formsUpdatedAt` y `formsSchemaVersion` para resolver conflictos de sincronización a nivel de la sección Formularios.
- Las migraciones ya no restauran indiscriminadamente los campos a los valores por defecto de la app.

## Evidencias
- Factura: `Evidencias` es opcional y el estado inicial es `Compra sin comprobante`.
- Solicitud: `Evidencia` es opcional por defecto.
- Transferencia/reposición: `Evidencia` es opcional por defecto.
- Si el usuario indica explícitamente que el comprobante está adjunto, la factura sigue validando que exista un archivo.
- Un ADMIN puede volver a marcar Evidencia como obligatoria desde Formularios.

## Versión
- versionCode: 30503
- versionName: 3.5.3
- DATA_SCHEMA_VERSION: 350 (sin migración destructiva de catálogos)
- FORM_SCHEMA_VERSION: 353
