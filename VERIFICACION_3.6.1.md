# Verificación 3.6.1

- `versionName` actualizado a 3.6.1 y `versionCode` a 30601.
- La configuración por defecto de la API VPS queda vacía.
- Guardar preferencias no obliga a configurar una API VPS.
- La URL solo se valida cuando el usuario introduce un valor.
- Probar/Respaldar mediante VPS solo se habilita cuando existe una URL configurada.
- Token/API key opcional: se envía como `Authorization: Bearer` solo si fue configurado. Al cambiar la URL de la API se elimina el token anterior.
- No se incluye servidor PowerShell ni implementación de infraestructura VPS dentro del proyecto.
- El contrato de integración queda documentado en `API_VPS_CONTRACT.md`.
- Google Drive y OneDrive permanecen sin cambios funcionales.

## Comprobación técnica en este entorno
- Recursos XML parseados correctamente.
- Revisión de sintaxis de los Kotlin modificados con `kotlinc`: no aparecieron diagnósticos de parseo como `expecting`, `unexpected tokens` o errores de sintaxis. Las referencias Android/Compose requieren el classpath del SDK para una compilación completa.
- Búsqueda global del proyecto: no quedan la IP/puerto usados en la propuesta anterior ni literales IPv4 dentro de `app/src`.
