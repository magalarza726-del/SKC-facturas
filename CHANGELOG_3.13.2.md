# SKC Facturas 3.13.2

- Proveedor predeterminado: Celda desplegable vinculada.
- En Configuración > Formatos personalizados > Factura > Proveedor > Editar se pueden crear, editar y eliminar proveedores con nombre, RUC/C.I. y correo.
- El directorio se guarda en Catalogs.suppliers, se sincroniza con Supabase y se incluye en JSON de configuración.
- En Factura continúa el autocompletado escribiendo por nombre, RUC o correo.
- Al seleccionar proveedor se vinculan RUC y correo automáticamente.
- Migración 501 preserva los demás formatos personalizados y cambia solo supplier a LINKED_SELECT en instalaciones 3.13.x.
- versionCode 31302, versionName 3.13.2.
