# Verificación técnica 3.8.1

## Caso corregido
1. Instalación nueva sin URL/key -> aparece `Conexión inicial`.
2. Se guardan URL/key -> el formulario sigue disponible mientras no exista un login exitoso, permitiendo corregir errores.
3. Login exitoso -> se marca el bootstrap como completado.
4. Cerrar sesión -> la conexión ya no queda expuesta; solo puede modificarse con la clave de desarrollador.
5. Actualización desde una versión con Supabase ya configurado -> el bootstrap se inicializa como cerrado.

## Persistencia
La bandera `initial_setup_open` se guarda en la metadata local. No depende de la sesión actual, por lo que un logout no reabre la configuración.

## Supabase
No se cambió el esquema remoto ni las migraciones. Esta versión corrige únicamente el flujo local de configuración/autenticación.

## Comprobación local realizada
- Delimitadores Kotlin balanceados en los cuatro archivos modificados.
- No aparece la clave de desarrollador en texto plano dentro de `app/src`.
- No reapareció una IP/puerto fijo del VPS dentro de `app/src`.
- Se intentó `./gradlew :app:compileDebugKotlin --offline`, pero el wrapper no está cacheado y este entorno no puede resolver `services.gradle.org`; el build final debe ejecutarse en Android Studio.
