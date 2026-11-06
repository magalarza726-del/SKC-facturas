# SKC Facturas 3.12.1

- Exportación JSON completa de la configuración de la app.
- Importación JSON completa y compatible con archivos 3.8-3.12.
- Incluye formatos personalizados, reglas, Excel, almacenamiento, Supabase, múltiples backends, múltiples respaldos, notificaciones, catálogos y credenciales/API tokens configurados.
- Incluye el archivo de plantilla Excel en Base64 cuando existe.
- No exporta sesiones de autenticación, contraseñas de usuarios, facturas, transferencias, evidencias ni movimientos contables.
- Las URI de Drive/OneDrive se conservan en el JSON; Android puede requerir volver a autorizar la carpeta al importar en otro dispositivo.
