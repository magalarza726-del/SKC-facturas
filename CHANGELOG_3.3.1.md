# 3.3.1

- Corrige la actualización de instalaciones existentes: catálogos y campos del Excel oficial ya no dependen del indicador `seeded`.
- Añade versionado de esquema para configuración y catálogos, evitando que una configuración antigua bloquee migraciones futuras.
- Lee directamente la hoja `C. Costos` del Excel oficial integrado para obtener centros de costo, costos secundarios y clientes.
- Mantiene valores personalizados al sincronizar catálogos y reemplaza correctamente los catálogos heredados cuando aún eran datos iniciales.
- Añade una acción administrativa para sincronizar manualmente catálogos desde el Excel oficial.
- Muestra en Configuración el archivo, hoja, columnas activas y mapeos del formato Excel actualmente usado.
- Corrige el mapeo duplicado de la columna Cliente en el formato oficial. El mapeo predeterminado vuelve a tener exactamente 17 columnas A-Q.
- Verifica que la exportación basada en el formato oficial conserve `FLUJO PROYECTO` y `C. Costos`.
