# Auditoría técnica 3.5.2

## Bugs confirmados y corregidos

1. **Sobrescritura entre secciones de Configuración**: Excel y Almacenamiento guardaban `snapshot.config.copy(...)`. Si el snapshot estaba desactualizado podían restaurar Formularios antiguos. Ahora existen guardados parciales que parten de la fila `appConfig` más reciente.
2. **Carrera durante carga de plantilla Excel**: la subida puede tardar y al terminar usaba la configuración capturada antes de la subida. Ahora vuelve a cargar `appConfig` justo antes de activar/restaurar la plantilla.
3. **Operaciones de UI concurrentes**: varios `viewModelScope.launch` podían escribir al mismo tiempo. Se usa `Mutex` para serializar acciones y el poller.
4. **Sincronizaciones concurrentes**: manual, poller y WorkManager podían procesar la misma evidencia a la vez. `SyncEngine.sync()` tiene bloqueo global de proceso.
5. **Renovación concurrente de sesión**: dos operaciones podían intentar refrescar la sesión Supabase simultáneamente. `ensureSession()` está serializado.
6. **Catálogos con ediciones rápidas**: callbacks consecutivos podían partir del mismo snapshot anterior. El editor mantiene un borrador local que se actualiza antes de encolar el guardado.
7. **Importación de proveedores sobre catálogo viejo**: el CSV se fusionaba con un snapshot capturado antes de parsear. Ahora usa la fila de Catálogos más reciente.
8. **Exposición innecesaria del ledger**: `visibleLedger()` devolvía todos los movimientos a cualquier usuario autenticado. Ahora solo ADMIN ve todos; USUARIO ve los suyos.
9. **Recordatorios consumidos sin permiso**: Android 13+ podía incrementar el contador y posponer el recordatorio aunque no pudiera mostrar la notificación. Ya no avanza hasta poder notificar.
10. **Archivos temporales huérfanos**: una evidencia que fallaba durante copia/optimización podía dejar archivos parciales. Ahora se limpian en cualquier fallo.
11. **Revocación vs RLS**: la app permitía revocar una factura asignada al usuario mediante `accountUserId`, pero la política SQL de UPDATE solo aceptaba `createdByUserId`. Se corrigió el esquema y se añadió una migración.

## Verificaciones realizadas

- Búsqueda de patrones de guardado completo desde snapshots obsoletos en la UI: corregidos los callsites existentes.
- Búsqueda de errores de sintaxis con `kotlinc`: no aparecieron diagnósticos de parser; las referencias Android/Compose no pueden resolverse con el compilador CLI sin el SDK/dependencias Android.
- Intento de Gradle offline: el wrapper no puede descargar Gradle 9.3.1 por falta de resolución de red en este entorno, por lo que no se declara un build Android completo.
- Plantilla Excel oficial conservada sin cambios respecto de 3.5.1.
