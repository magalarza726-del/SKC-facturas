# SKC Facturas v3.15.5

## Cambios

- En Configuración → Transferencias (ADMIN) es posible elegir confirmación por el receptor o transferencia directa desde el emisor.
- En modo directo se confirma inmediatamente la transferencia y se acredita al destinatario mediante un ingreso del libro de saldos, de forma atómica.
- Las transferencias antiguas pendientes siguen necesitando confirmación.
- Las solicitudes de monto respondidas con una transferencia directa quedan completadas.
- La evidencia se exige solo si el campo está configurado como obligatorio. Se corrigió el valor predeterminado que forzaba evidencias opcionales en facturas.
- Portal minimalista de descargas en GitHub Pages con acceso directo a la última APK, fecha y tamaño actualizados desde Releases.

## Portal y APK

- Portal: https://magalarza726-del.github.io/SKC-facturas/
- APK: https://github.com/magalarza726-del/SKC-facturas/releases/latest/download/SKC-Facturas.apk

**Primera activación de Pages:** Settings → Pages → Build and deployment → Source: GitHub Actions.
