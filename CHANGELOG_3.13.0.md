# SKC Facturas 3.13.0

## Formularios base

- Factura: Monto, Proveedor, Número de factura y Evidencia.
- Las facturas nuevas se registran como Gasto por defecto.
- Fecha y responsable se guardan automáticamente sin mostrarse en el formulario base.
- Solicitar transferencia: Monto y Motivo.
- Enviar transferencia: Monto, Motivo y Evidencia.
- Los formatos anteriores se migran al nuevo formato compacto conservando campos personalizados creados por el ADMIN.

## Historial

- Historial cronológico unificado para facturas, transferencias y solicitudes.
- Cada fila muestra tipo, monto y un resumen breve.
- Tocar un movimiento abre todos sus detalles y evidencias.
- ADMIN conserva selección múltiple y eliminación permanente conectada con Supabase y el almacenamiento de evidencias.

## Recordatorios

- Solo se muestran recordatorios pendientes.
- Al completar o cancelar un recordatorio desaparece de la pantalla.
- Se retiraron estadísticas y filtros de estados de la interfaz.

## Formatos personalizados

- Un campo ya no tiene que pertenecer a una lista predefinida.
- El ADMIN puede escribir un identificador nuevo y crear datos completamente personalizados.
- Se mantienen los tipos: evidencia, libre, desplegable simple, vinculada y compuesta.
- Los valores personalizados se guardan en `extraFields` y viajan con el registro.

## Excel

- Se retiró la pestaña de configuración del Excel antiguo.
- El Excel de facturas se genera desde las columnas del formato de Factura.
- El Excel de transferencias se genera desde las columnas del formato de Enviar transferencia.
- Fecha y responsable/fecha de transferencia se incluyen como datos automáticos del sistema.

## Nube y respaldo

- Se mantiene la configuración de múltiples almacenamientos principales y múltiples respaldos opcionales.
- La limpieza de almacenamiento queda dentro de `Nube y respaldo` para ADMIN.
- Se corrigió el respaldo diario para considerar todos los destinos remotos seleccionados.

## Configuración JSON

- Los nuevos formatos personalizados, identificadores y configuración de infraestructura siguen incluidos en importación/exportación JSON.
- Los datos operativos (facturas, transferencias y evidencias) no forman parte del JSON de configuración.
