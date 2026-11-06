# 3.3.2

- Reemplaza la edición de catálogos mediante bloques de texto por selectores con agregar, editar y eliminar.
- Descripción / proyecto principal controla automáticamente la lista de Proyecto 2.
- Cada Proyecto 2 puede tener clientes vinculados propios.
- El formulario filtra clientes según Descripción y Proyecto 2; los proyectos sin relación usan el catálogo general como respaldo.
- Permite vincular un cliente general existente o crear uno nuevo desde el proyecto.
- Renombrar un cliente actualiza sus vínculos en todos los proyectos.
- Centro de costo y costo secundario pasan a edición dependiente mediante listas.
- Estados de evidencia, métodos de pago y clientes generales incorporan altas, edición y bajas.
- La migración 332 conserva los datos actuales y recupera relaciones cliente/proyecto ya utilizadas en movimientos locales cuando es posible.
- No requiere cambios en Supabase, R2, Edge Functions ni migraciones SQL.
