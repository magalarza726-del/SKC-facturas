# SKC Facturas 3.13.1

## Proveedores

- Se incorporó el directorio de `Proveedor-4265.xls` con 1.019 proveedores.
- Cada proveedor conserva nombre, RUC y correo vinculados.
- El campo `Proveedor` del formulario de Factura ahora es autocompletable: el usuario escribe y la app muestra hasta 6 coincidencias.
- La búsqueda considera nombre, RUC y correo y no depende de tildes para encontrar nombres.
- Al seleccionar una coincidencia, RUC y correo se guardan automáticamente en la factura.
- Si se escribe manualmente un proveedor que no está en el directorio, la factura sigue pudiendo guardarse y los datos vinculados quedan vacíos.
- En instalaciones existentes el catálogo incluido se fusiona con los proveedores ya conocidos sin reemplazar datos no vacíos del usuario.
- Importar una configuración o catálogo antiguo vuelve a incorporar el directorio base para que no desaparezca.

## Compatibilidad

- No requiere cambios SQL nuevos en Supabase.
- `versionCode = 31301`, `versionName = 3.13.1`.
