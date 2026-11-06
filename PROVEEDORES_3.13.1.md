# Directorio de proveedores 3.13.1

Fuente recibida: `Proveedor-4265.xls`.

- Filas de proveedores importadas: **1.019**.
- Proveedores con RUC/C.I.: **1.019**.
- Proveedores con correo: **1.019**.
- Archivo integrado en la app: `app/src/main/assets/providers_skc_4265.json`.

El archivo fuente se leyó por columnas `RUC/C.I.`, `Proveedor` y `Correo`. La aplicación usa el JSON como directorio base y lo fusiona con el catálogo existente.

En Factura, el campo Proveedor no se presenta como una lista desplegable convencional. El usuario escribe texto y recibe coincidencias por nombre, RUC o correo. Al seleccionar una coincidencia se guardan también `supplierRuc` y `supplierEmail`.
