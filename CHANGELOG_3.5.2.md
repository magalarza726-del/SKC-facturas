# SKC Facturas Android 3.5.2

## Auditoría de bugs

- Evita que guardar Excel o Almacenamiento sobrescriba cambios recientes de Formularios u otras secciones de Configuración. Cada sección se combina con la configuración local más reciente antes de guardar.
- El cambio/restauración de la plantilla Excel se vuelve a basar en la configuración más reciente después de la subida del archivo.
- Serializa operaciones del ViewModel para evitar escrituras simultáneas desde varios botones y el sincronizador automático.
- Serializa las sincronizaciones en todo el proceso para evitar que el poller, una sincronización manual y WorkManager suban o eliminen la misma evidencia al mismo tiempo.
- Serializa la renovación de sesión Supabase para evitar carreras con refresh tokens.
- El editor de Catálogos mantiene un borrador local inmediato; ediciones rápidas consecutivas ya no se calculan sobre una captura anterior.
- La importación CSV de proveedores se combina con el catálogo local más reciente.
- Un USUARIO ya no recibe por la API interna la lista completa de movimientos contables; `visibleLedger` limita los datos a su propio usuario.
- Un recordatorio no se marca como avisado ni mueve su próxima fecha si Android no concedió permiso de notificaciones.
- Si falla la copia, optimización o validación de una evidencia, se eliminan archivos temporales incompletos.
- La política RLS de Supabase para actualizar facturas se alinea con la revocación directa: permite al creador o al usuario responsable (`accountUserId`). Se incluye `supabase/migration-3.5.2.sql`.
- Se eliminaron textos explicativos residuales del formulario de factura.
- Se eliminó un aviso duplicado en el flujo de confirmación de factura duplicada.
- Se actualizaron metadatos de exportación y previews a 3.5.2.

## Versión

- versionCode: 30502
- versionName: 3.5.2
- DATA_SCHEMA_VERSION permanece en 350; no hay cambio incompatible del formato local de datos.
