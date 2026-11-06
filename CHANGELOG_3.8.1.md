# CHANGELOG 3.8.1 — Inicio de sesión y bootstrap de Supabase

## Corregido
- Se elimina el bloqueo circular del primer arranque: antes el login necesitaba una URL de Supabase, pero la configuración de esa URL estaba detrás de una sesión/rol que todavía no podía existir.
- En una instalación nueva, `Configuración` muestra una tarjeta mínima de **Conexión inicial** con solo `Project URL` y `Publishable key`. No solicita clave de desarrollador.
- La conexión inicial permanece editable incluso si se cierra y abre la app antes del primer login correcto.
- Después del primer inicio de sesión exitoso, la configuración de infraestructura se bloquea de forma permanente para usuarios normales. Cerrar sesión no vuelve a abrirla.
- Si luego hace falta corregir la URL o key sin poder iniciar sesión, existe un único acceso de recuperación: **Cambiar conexión**, protegido por la clave de desarrollador.
- La contraseña de inicio de sesión ahora se oculta visualmente.
- El botón `Iniciar sesión` solo se habilita cuando hay conexión, correo y contraseña.

## Compatibilidad
- Una instalación que ya tenía un proyecto Supabase configurado antes de actualizar a 3.8.1 se considera ya inicializada; no se expone de nuevo la configuración sin clave.
- No requiere cambios SQL en Supabase.

## Versión
- `versionCode`: 30801
- `versionName`: 3.8.1
