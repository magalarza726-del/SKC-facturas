# SKC Facturas 3.4.0 — Facturas simplificadas

## Objetivo
La pantalla de factura se concentra en la información que realmente conoce el usuario operativo y conserva la clasificación financiera para una adopción futura controlada por administración.

## Cambios
- Monto y proveedor pasan a ser los ejes obligatorios del registro.
- El proveedor se selecciona desde un catálogo administrado; RUC y correo se copian automáticamente al registro.
- Nota de crédito disponible como tipo de documento; `# de Documento` se exige únicamente para nota de crédito.
- Comprobante: si se marca como incluido/adjunto, se exige al menos una foto o PDF tanto en UI como en repositorio.
- Cliente opcional con búsqueda incremental por nombre comercial o razón social.
- Centro de costo, costo secundario, proyecto y demás clasificación quedan ocultos para usuarios por defecto; ADMIN puede seguir accediendo y puede reactivar campos para capacitación futura.
- Catálogo de proveedores administrable desde la app: alta, edición, eliminación e importación CSV, solo ADMIN.
- Flujo de revocación: el usuario solicita, ADMIN aprueba o rechaza y queda trazabilidad.
- Una revocación aprobada conserva el registro histórico, lo marca `REVOCADA`, crea un reverso contable idempotente y excluye la factura revocada del Excel operativo.
- CSV administrativo incluye RUC/correo del proveedor, tipo y número de documento y estado del registro.
- El motor Excel estable de 3.3.6 y su plantilla se mantienen sin cambios.

## Compatibilidad de datos
No se añade un nuevo `entity_type` en Supabase. Proveedores y directorio de clientes viajan dentro de `catalogs`; solicitudes de revocación usan `messages`. El esquema genérico `skc_events` existente sigue siendo compatible.
