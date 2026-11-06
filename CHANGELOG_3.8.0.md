# CHANGELOG 3.8.0 — Cierre Supabase + Google Drive

## Objetivo

Esta versión reduce el alcance del entregable principal: **Supabase es el backend final de SKC Facturas** y **Google Drive es el respaldo externo prioritario**. OneDrive y la API externa/VPS se mantienen preparados, pero quedan ocultos dentro de “Integraciones opcionales”.

## Cambios principales

- Se añadió edición real de facturas desde `Base de datos → Facturas`.
- La edición conserva ID, código, autor, fecha de creación y evidencias existentes.
- Es posible agregar nuevas evidencias durante una edición sin borrar las anteriores por accidente.
- Si cambian monto, tipo de movimiento, responsable o fecha, el movimiento contable relacionado se actualiza en la misma transacción local para mantener saldos coherentes.
- Las facturas revocadas no se pueden editar.
- Se corrigió una declaración duplicada de `visibleTransactions()` en `AppViewModel` que podía producir error de compilación.
- El número de factura/documento ahora es visible y editable también en facturas normales; sigue siendo obligatorio para notas de crédito.

## Eliminación segura

- La eliminación definitiva ahora exige Google Drive conectado.
- Al confirmar, la app crea primero un ZIP individual de la factura en Drive.
- Ese ZIP incluye `manifest.json`, `invoice.json`, el movimiento contable relacionado y todas las evidencias disponibles.
- Si cualquier evidencia no puede respaldarse o falla su checksum, la eliminación se cancela.
- Después del respaldo, Supabase crea la tombstone remota y luego la app intenta liberar las evidencias físicas.
- El movimiento contable se conserva para no alterar saldos históricos.
- Si un archivo remoto no puede liberarse después de la tombstone, la app informa la advertencia sin resucitar la factura.

## Google Drive

- Seleccionar una carpeta ya realiza una prueba real de escritura; se eliminó el botón redundante “Probar”.
- El respaldo general en Drive siempre incluye evidencias.
- No se requieren Client ID, secretos ni API keys de Google; se utiliza el selector de documentos de Android y permiso persistente sobre la carpeta elegida.

## Interfaz

- En cada factura administrativa quedan únicamente las acciones `Editar` y `Eliminar`.
- En edición se quitó el botón `Limpiar` y se ocultan funciones que no corresponden al flujo de edición.
- La pantalla de respaldos muestra Google Drive como opción principal.
- OneDrive y VPS/API quedan dentro de una sección plegada de integraciones opcionales.

## Supabase

- Se mantiene `public.skc_events` como tabla compartida.
- La edición usa el upsert ya existente y respeta RLS.
- La eliminación usa `skc_can_delete_invoice()` y `skc_delete_invoice()` sin service-role dentro del APK.
- Para instalaciones existentes sigue siendo obligatorio ejecutar una vez `supabase/migration-3.7.0.sql` si todavía no se aplicó.
- Para instalaciones nuevas, `supabase/supabase-schema.sql` ya contiene la lógica de tombstones y eliminación.
