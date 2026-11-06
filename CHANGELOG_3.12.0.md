# SKC Facturas 3.12.0

## Interfaz

- `Base de datos` se reemplaza por `Historial`.
- Historial muestra solamente `Factura/Transferencia` y monto; el detalle completo se abre al tocar el registro.
- ADMIN puede activar selección múltiple y eliminar varios movimientos en una sola operación.
- Inicio ya no muestra los últimos movimientos.
- Inicio incluye `Descargar Excel de Facturas` y `Descargar Excel de Transferencias`.
- Revocar factura no solicita clave adicional.
- Se retiraron de Configuración las pantallas de Catálogos y Formularios.
- Se eliminaron textos auxiliares/explicativos de las pantallas principales.

## Formatos personalizados

- Nueva sección ADMIN `Formatos personalizados`.
- Formatos independientes para `Factura`, `Solicitar` y `Enviar`.
- Crear desde cero, restaurar, reordenar, editar y eliminar campos.
- Tipos: evidencia, libre, desplegable simple, vinculada y compuesta.
- Las opciones vinculadas pueden rellenar otros campos automáticamente.
- Las opciones compuestas pueden depender de la selección de un campo anterior.
- Facturas y mensajes renderizan el formato guardado en Configuración.

## Almacenamiento y respaldo

- Se pueden seleccionar varios conectores principales en la configuración.
- Se pueden seleccionar varios destinos de respaldo simultáneamente.
- El respaldo sigue siendo opcional.
- Los respaldos automáticos remotos pueden enviarse a Google Drive, OneDrive y/o VPS/API.
- La sincronización productiva de datos de esta versión continúa en Supabase; los demás conectores quedan configurables y comprobables por HTTPS para el adaptador futuro.

## Excel

- Exportación separada de facturas.
- Exportación separada de transferencias con columnas propias de transferencias.
