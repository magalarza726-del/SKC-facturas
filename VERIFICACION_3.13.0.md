# Verificación 3.13.0

## Revisado

- `versionCode = 31300` y `versionName = 3.13.0`.
- Formulario base de factura reducido a monto, proveedor, número y evidencia.
- Fecha y responsable permanecen como campos de sistema ocultos.
- Solicitud reducida a monto + motivo.
- Transferencia reducida a monto + motivo + evidencia.
- Historial unificado con detalle y selección múltiple ADMIN.
- Recordatorios muestran solamente pendientes.
- Editor de formatos acepta identificadores de datos nuevos.
- Excel de facturas y transferencias se genera dinámicamente desde los formatos.
- Pestaña Excel retirada de Configuración.
- Configuración de múltiples almacenamientos/respaldos conservada.
- Limpieza de evidencias accesible desde Nube y respaldo para ADMIN.
- Respaldo diario revisado para múltiples destinos remotos.
- Archivos Kotlin modificados revisados con `kotlinc` para errores de parser/sintaxis: sin errores detectados.

## Build completo

Se intentó ejecutar Gradle Wrapper, pero el entorno no pudo resolver `services.gradle.org` para descargar Gradle 9.3.1. Por tanto, la compilación Android completa debe validarse en Android Studio / GitHub Actions con acceso a Internet y SDK Android.
