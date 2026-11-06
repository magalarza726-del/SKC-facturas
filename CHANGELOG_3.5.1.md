# SKC Facturas Android 3.5.1

## Corrección de persistencia de Configuración

- El editor de Formularios conserva simultáneamente los borradores de Factura, Solicitud, Transferencia/reposición y Recordatorio.
- Cambiar entre pestañas de Formularios ya no descarta cambios pendientes.
- La actualización automática de Supabase ya no reinicializa el editor mientras se está modificando.
- Guardar Formularios combina el borrador con la configuración local más reciente para no sobrescribir otros ajustes con una captura antigua de la interfaz.
- La sincronización ya no permite que una copia remota más antigua reemplace una configuración local más reciente.
- Los controles de campos estructuralmente obligatorios se muestran bloqueados en lugar de permitir cambios que el modelo posteriormente debía restaurar.
- versionCode 30501 / versionName 3.5.1.
