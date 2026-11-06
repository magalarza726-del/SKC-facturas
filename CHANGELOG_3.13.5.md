# SKC Facturas 3.13.5 — búsqueda de proveedores optimizada

## Cambios

- Se reemplazó la normalización repetitiva por un índice de proveedores en memoria que se construye una sola vez por catálogo.
- La búsqueda conserva únicamente los mejores resultados solicitados (8 en Factura y 12 en Configuración) en vez de ordenar todas las coincidencias.
- El filtrado se ejecuta en `Dispatchers.Default`, fuera del hilo principal de Compose.
- Se añadió un debounce corto de 70 ms para evitar lanzar una búsqueda completa por cada tecla cuando el usuario escribe rápido.
- Al escribir en el campo Proveedor ya no se hace una segunda búsqueda exacta sobre todo el catálogo en cada pulsación. RUC y correo se completan al elegir una sugerencia o al guardar una coincidencia exacta.
- La misma optimización se aplica al buscador de proveedores de Configuración > Formularios.

## Compatibilidad

No cambia el formato del catálogo, la base de datos, Supabase ni el esquema de sincronización. Los proveedores existentes y sus vínculos RUC/correo siguen siendo compatibles.
