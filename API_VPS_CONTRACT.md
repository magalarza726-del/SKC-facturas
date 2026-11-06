# Contrato de API para respaldo en VPS

La app **no contiene una IP, puerto ni dirección de VPS preconfigurada**. El administrador pega en la pantalla de Respaldos la URL base de la API que corresponda al servidor vigente.

## URL configurable

Ejemplo genérico: `https://api.midominio.com/skc-backup`

La URL puede cambiar en cualquier momento desde la app sin recompilar el APK.

## Endpoints esperados

### GET /health
Usado por el botón **Probar API**. Debe responder HTTP 2xx. Puede devolver JSON como:

```json
{ "message": "API disponible" }
```

### PUT /backup?name=<archivo.zip>
Usado por el botón **Respaldar**. El cuerpo de la solicitud es el ZIP del respaldo con `Content-Type: application/zip`. Debe responder HTTP 2xx cuando el archivo se haya recibido correctamente.

## Autenticación

La pantalla permite guardar un token/API key opcional. Si existe, la app envía:

```text
Authorization: Bearer <token>
```

Si la API usa otro mecanismo de autenticación, se puede adaptar esta capa sin cambiar el resto de la aplicación.

## Regla de arquitectura

La app Android solo consume la API. No debe recibir credenciales de SQL Server, PostgreSQL, MySQL, ERP o Facelec, ni conectarse directamente a esas bases o servicios internos. La API del servidor es la encargada de validar, almacenar y enrutar la información.
