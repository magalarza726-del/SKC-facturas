# Verificación — Configuración 3.5.1

## Problema corregido

La pantalla de Formularios podía perder cambios por dos causas independientes:

1. El estado editable solo pertenecía a la pestaña de formulario activa. Al cambiar de Factura a Solicitud/Transferencia/Recordatorio se recreaba el estado desde la configuración guardada y se descartaban cambios pendientes de la pestaña anterior.
2. La sincronización podía reemplazar una fila local con una copia remota durante el pull. En 3.5.1 cualquier fila local PENDIENTE/ERROR se conserva hasta intentar enviarla; una copia remota vieja ya no puede borrar una configuración recién guardada.

## Cambios

- Borrador simultáneo de todos los módulos de Formularios.
- Un solo “Guardar formularios” persiste los cuatro módulos y la clave de revocación.
- El guardado se combina con la configuración local más reciente, no con una captura vieja de la interfaz.
- Los campos estructuralmente obligatorios muestran sus controles bloqueados para no aparentar que admiten cambios que el modelo no puede aceptar.
- Resolución de conflictos de sincronización: PENDIENTE/ERROR local tiene prioridad durante el pull; remoto solo reemplaza una copia local ya sincronizada cuando es realmente más reciente.
- versionCode 30501 / versionName 3.5.1.

## Controles realizados

- Balance de llaves/paréntesis en los cuatro archivos Kotlin modificados.
- Análisis de sintaxis con kotlinc: sin errores de parser en los archivos modificados.
- Referencias `saveFormSettings` verificadas entre UI → ViewModel → Repository.
- Gradle fue invocado; el entorno se detiene antes de compilar porque no puede descargar Gradle 9.3.1 desde services.gradle.org.
- Integridad del ZIP verificada con `unzip -t`.
