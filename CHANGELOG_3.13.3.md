# SKC Facturas 3.13.3

- Corrige la reaparición de proveedores eliminados.
- El catálogo de 1.019 proveedores de `Proveedor-4265.xls` pasa a ser una semilla de una sola ejecución.
- Instalaciones 3.13.1/3.13.2 se reconocen como ya sembradas sin reinsertar proveedores que un ADMIN haya eliminado.
- Altas, ediciones y eliminaciones en la celda vinculada `Proveedor` quedan persistentes en `Catalogs.suppliers`.
- El restablecimiento local vuelve a cargar explícitamente el catálogo inicial completo y marca la semilla como aplicada.
- versionCode 31303 / versionName 3.13.3.
- No requiere cambios SQL en Supabase.
