# Verificación Formularios 3.5.3

## Problema corregido

Se reforzó la persistencia de Formularios para que una modificación no pueda desaparecer silenciosamente al guardar, reiniciar o sincronizar otra sección de Configuración.

## Cambios

- Guardado contra la configuración local más reciente.
- Lectura inmediata desde SQLite después del guardado y comparación exacta de todos los campos.
- `formsUpdatedAt` y `formsSchemaVersion` separan la versión de Formularios del resto de `AppConfig`.
- La sincronización preserva una revisión local de Formularios más nueva aunque llegue una actualización remota posterior de otra sección.
- Las migraciones de formularios dejan de restaurar indiscriminadamente los valores por defecto; conservan etiquetas, visibilidad, obligatoriedad, valores por defecto, orden y campos personalizados.
- Después de un guardado confirmado, la pantalla vuelve a cargar exactamente lo que quedó persistido.

## Evidencias por defecto

- Factura: evidencia opcional. El estado inicial es `Compra sin comprobante`.
- Solicitud: evidencia opcional.
- Transferencia/reposición: evidencia opcional.
- Respuesta a una solicitud: evidencia opcional si Transferencia no está configurada como obligatoria.
- Un ADMIN puede marcar `Evidencia` como obligatoria desde Formularios.
- Si en Factura se activa explícitamente `Comprobante incluido / adjunto`, sigue siendo obligatorio adjuntar el archivo correspondiente.

## Comprobaciones estáticas

- versionCode 30503: OK
- versionName 3.5.3: OK
- DATA_SCHEMA_VERSION permanece 350: OK
- FORM_SCHEMA_VERSION 353: OK
- Persistencia JSON de `formsUpdatedAt`: OK
- Persistencia JSON de `formsSchemaVersion`: OK
- Verificación de lectura posterior a escritura: OK
- Protección de Formularios en merge remoto: OK
- Migración no destructiva: OK
- Evidencia de Factura opcional por defecto: OK
- Evidencia de Solicitud opcional por defecto: OK
- Evidencia de Transferencia opcional por defecto: OK
- Validación UI respeta la configuración de obligatoriedad: OK
- Preview sin referencia `displayName`: OK
- Parser Kotlin: sin errores de sintaxis detectados en los archivos modificados.

## Build completo

El wrapper intenta descargar Gradle 9.3.1 desde `services.gradle.org`. Este entorno no tiene resolución de red para ese host, por lo que `compileDebugKotlin` no llega a iniciar. El fallo observado es `java.net.ConnectException / UnresolvedAddressException`, previo a la compilación del proyecto.
