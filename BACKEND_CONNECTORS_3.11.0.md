# Conectores de base de datos 3.11.0

## Por qué se usa una API/bridge

Android no debe conectarse directamente a puertos PostgreSQL, MySQL o SQL Server. Exponer esos puertos a Internet y guardar sus credenciales en un APK aumenta el riesgo y dificulta cambiar de infraestructura.

La app guarda únicamente:

- tipo de motor/conector;
- URL HTTPS de la API;
- ruta de salud, por defecto `/health`;
- nombre de base/instancia como referencia;
- token opcional guardado localmente y excluido del JSON portátil.

## Prueba mínima

La API debe permitir:

```http
GET /health
Authorization: Bearer <token-opcional>
```

Cualquier respuesta HTTP 2xx se considera conexión válida. Si devuelve JSON, la app utiliza `message` o `status` como descripción.

## Contrato de datos futuro

Para convertir un conector alternativo en backend principal habrá que implementar el contrato de sincronización de SKC sobre esa API. La versión 3.11.0 no finge esa capacidad: solo deja la configuración y la prueba de conexión preparadas.
