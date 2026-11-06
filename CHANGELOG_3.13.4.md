# SKC Facturas 3.13.4

- Corrige el campo Proveedor para que siempre sea un campo de texto editable con autocompletado.
- Las coincidencias aparecen mientras se escribe por nombre, RUC o correo.
- El proveedor integrado se detecta aunque una configuración antigua haya alterado su identificador.
- El identificador de los campos base queda de solo lectura para evitar romper la semántica interna del formulario.
- Migra el campo base Proveedor al identificador `supplier` y tipo `LINKED_SELECT` sin tocar los campos personalizados.
