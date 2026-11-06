# Verificación 3.12.0

Comprobaciones realizadas en el código fuente:

- versionCode `31200` y versionName `3.12.0`.
- No existe pantalla visible `Base de datos`.
- Configuración ADMIN expone `Formatos personalizados` y no expone `Catálogos`/`Formularios`.
- Historial tiene selección múltiple ADMIN.
- Las filas de Historial muestran sólo tipo y monto antes de abrir detalle.
- Inicio contiene las dos exportaciones Excel y no contiene lista de últimos movimientos.
- Revocación directa no valida clave compartida.
- Formatos personalizados soportan cinco tipos de campo.
- Configuración soporta listas de proveedores principales y de respaldo.
- Respaldo puede quedar vacío/desactivado.
- No hay IP fija de VPS ni clave de desarrollador en `app/src`.
- Manifest mantiene tráfico HTTP sin cifrar deshabilitado.
- Kotlin modificado pasó comprobación de parser local sin errores de sintaxis detectados.
- XML de recursos/manifest validado.

La compilación Gradle completa no pudo ejecutarse en este entorno porque Gradle Wrapper necesita descargar `gradle-9.3.1-bin.zip` y no hay resolución de red hacia `services.gradle.org`. Debe validarse con `Build > Make Project` en Android Studio.
