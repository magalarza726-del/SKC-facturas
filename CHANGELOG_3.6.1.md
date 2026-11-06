# SKC Facturas 3.6.1

## Corrección de integración VPS
- Eliminada toda IP, puerto y URL de VPS preconfigurada en la aplicación.
- El campo **URL base de la API** inicia vacío y se configura manualmente desde Respaldos.
- Cambiar de VPS no requiere modificar ni recompilar la app: solo se reemplaza la URL de la API.
- La app nunca se conecta directamente a la base de datos del VPS, ERP o Facelec.
- El token/API key pasa a ser opcional; si existe se envía como Bearer. Si cambia la URL de la API, se borra el token anterior para evitar reutilizar una credencial en otro servidor.
- Se eliminó del proyecto el servidor PowerShell de VPS para no acoplar la app a una implementación o puerto concreto.
- Se añadió `API_VPS_CONTRACT.md` con el contrato mínimo que debe cumplir cualquier API de respaldo.

## Drive / OneDrive
- Se mantiene la integración por selector de carpetas de Android y los respaldos ZIP portables.
