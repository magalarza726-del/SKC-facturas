# SKC Facturas 3.15.0

- Formulario por defecto: selector **¿Es ingreso o un gasto?** (Gasto por defecto), **Proyectos**, **Observaciones**, monto, proveedor, número de factura y evidencia.
- Persistencia de formularios reforzada: la migración conserva personalizaciones ADMIN y el guardado se verifica localmente antes de sincronizar.
- Sincronización cercana a tiempo real: guardados administrativos se empujan inmediatamente y los clientes activos consultan cambios cada ~3 s.
- Proveedores: usuarios pueden proponer un proveedor desde Facturas con nombre, RUC y correo. La solicitud llega a ADMIN; solo ADMIN modifica el catálogo oficial.
- Configuración > Formatos > Factura: búsqueda, alta, edición y eliminación de proveedores integrada para ADMIN.
- Solicitudes/transferencias: monto de respuesta editable, edición y borrado tipo WhatsApp para mensajes no confirmados, con auditoría.
- Correcciones ADMIN: nuevo ajuste por usuario como ingreso o egreso desde Historial.
- Edición de facturas: el propietario puede editar sus facturas activas durante 24 h; ADMIN puede corregirlas después.
- Borrado de historial: selección administrativa elimina documento/mensaje y su asiento contable asociado cuando corresponde.
- Supabase: `migration-3.15.0.sql` habilita limpieza de `ledger` conservando autorización ADMIN en servidor.
