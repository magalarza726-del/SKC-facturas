# SKC Facturas 3.14.0 — catálogos vinculados a formularios

## Nuevo

- Cualquier **Celda desplegable vinculada** puede importar un catálogo desde CSV o Excel.
- Configuración por columna: **principal**, **subcategoría**, **dato vinculado** o **ignorar**.
- Jerarquías dependientes de varios niveles, por ejemplo `Proyecto → Subproyecto → Proyecto 2`.
- Datos vinculados visibles u ocultos; ambos se conservan al guardar la transacción o solicitud.
- Un dato vinculado o subcategoría puede mapearse a un campo existente de SKC o guardarse automáticamente como dato adicional.
- Selector de hoja para libros Excel con varias hojas.
- Al reemplazar el archivo se conservan las configuraciones de columnas con encabezados coincidentes.

## Rendimiento y seguridad

- La búsqueda dentro de las opciones sigue usando filtrado con debounce fuera del hilo visual.
- Se eliminan filas exactamente duplicadas durante la importación.
- Se valida que la combinación `principal + subcategorías` sea inequívoca antes de guardar el campo.
- Se limitan tamaño de archivo, filas, columnas y contenido descomprimido para evitar bloqueos por archivos excesivos o dañados.

## Compatibilidad

- CSV, TSV, TXT, XLSX y XLSM.
- Los `.xls` antiguos deben convertirse a XLSX o CSV.
- Los formularios personalizados existentes se preservan durante la migración a esquema de formularios 600.
