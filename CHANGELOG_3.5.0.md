# Changelog 3.5.0

## Navegación e Inicio

- Eliminados Historial y Mi flujo de la navegación.
- Eliminada la barra inferior.
- Inicio reorganizado en botones → flujo general → últimos 5 movimientos.
- Añadido regreso mediante Atrás de Android y flecha superior izquierda.
- Eliminados “Datos protegidos”, “EN VIVO” y textos descriptivos de navegación/secciones.

## Mensajes

- Lista de contactos con los demás usuarios activos.
- Conversaciones visuales por contacto.
- Sin chat libre.
- Acciones disponibles: solicitar transferencia/reposición y enviar transferencia/reposición.
- Monto, motivo y evidencia forman el flujo estructurado.
- Evidencia obligatoria validada en UI y repositorio.

## Facturas y recordatorios

- Revocación directa de facturas propias para usuarios mediante clave común.
- ADMIN puede revocar facturas sin clave.
- Reverso contable determinista para evitar duplicados.
- Motivo de cancelación de recordatorio opcional.

## Administración

- Base de datos con pestañas Facturas y Transferencias.
- Facturas muestran Proveedor, RUC y correo.
- Eliminados resúmenes globales de total/ingresos/egresos/saldo neto de Base de datos.
- Configuración: Cuenta, Usuarios, Catálogos, Formularios, Excel, Base compartida, Almacenamiento.
- Eliminado Preparación.
- Ocultadas las opciones Permitir crear cuentas y Bloquear identidad al correo/UID.
- Catálogos pueden exportarse e importarse en JSON.

## Formularios y Excel

- Formularios de solicitud/transferencia migrados a Contacto, Monto, Motivo y Evidencia.
- Migración posterior a sincronización para impedir que configuración remota antigua restaure campos 3.4.x.
- Excel: DESCRIPCIÓN usa Proyecto y PROYECTO2 usa Proyecto 2.
- Los formatos personalizados requieren ambos niveles de proyecto.
- Plantilla Excel estable 3.3.6 preservada sin modificación.

## Versión

- versionCode: 30500
- versionName: 3.5.0
- DATA_SCHEMA_VERSION: 350
