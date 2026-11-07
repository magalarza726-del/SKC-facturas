# SKC Facturas Android Nativo 1.5.1

Aplicación Android Kotlin/Compose para facturas, transferencias, historial, recordatorios, sincronización y respaldos de SKC Ingeniería.

## Flujo principal

- `Factura`: Ingreso/Gasto (Gasto por defecto), Monto, Proveedor, Proyectos, Número de factura, Observaciones y Evidencia. Fecha y responsable se guardan automáticamente.
- `Mensajes`: Solicitar transferencia = Monto + Motivo. Enviar transferencia = Monto + Motivo + Evidencia.
- `Historial`: facturas, transferencias y solicitudes en orden cronológico; tocar abre el detalle completo. ADMIN puede seleccionar varios y eliminarlos.
- `Recordatorios`: solo se muestran pendientes; completar o cancelar los retira de la pantalla.
- `Inicio`: botones separados para descargar Excel de Facturas y Excel de Transferencias.

## Formatos personalizados

`Configuración → Formatos personalizados` permite a ADMIN crear formatos para Factura, Solicitar y Enviar.

Tipos de campo:

- Captura de evidencia
- Celda libre
- Celda desplegable simple
- Celda desplegable vinculada
- Celda desplegable compuesta

Cada campo puede usar un identificador de dato definido por el ADMIN. Los campos personalizados se guardan con el registro y participan en la sincronización.

### CSV / Excel vinculado a una celda

Desde 3.15.0 una **Celda desplegable vinculada** puede importar un CSV o Excel como catálogo propio. Cada columna del archivo puede definirse como:

- **Columna principal**: valor principal que se busca en la celda.
- **Subcategoría**: crea un selector dependiente del nivel anterior.
- **Dato vinculado**: se completa automáticamente al identificar la fila.
- **Ignorar**: la columna no se usa.

Ejemplo: `Proyecto → Subproyecto → Proyecto 2`. Las columnas adicionales, como Cliente o Código, pueden mostrarse o permanecer ocultas; en ambos casos se guardan. Consulta `DATOS_VINCULADOS_CSV_EXCEL.md` y prueba `EJEMPLO_PROYECTOS.csv`.

El archivo se importa a la configuración del formulario. Si el archivo original cambia, debe usarse **Reemplazar archivo** para actualizar los datos.

## Proveedores

La app incluye el directorio suministrado en `Proveedor-4265.xls`: 1.019 proveedores con RUC y correo vinculados. En Factura, `Proveedor` funciona como búsqueda/autocompletado por nombre, RUC o correo; al elegir una coincidencia se guardan nombre, RUC y correo.

Un usuario puede proponer un proveedor nuevo desde Facturas indicando nombre, RUC y correo. La solicitud llega a cada ADMIN activo dentro de Mensajes; solo ADMIN puede aprobarlo e incorporarlo al catálogo oficial. Los administradores también pueden crear, buscar, editar y eliminar proveedores desde Configuración.

Si se necesita sustituir ese comportamiento por un catálogo administrado mediante archivo, el campo Proveedor también puede configurarse como una celda vinculada a CSV/Excel usando el sistema genérico iniciado en 3.15.0.

## Excel

Cada archivo de exportación se genera automáticamente desde el formato activo:

- `Excel de Facturas`: columnas del formato de Factura + datos automáticos del sistema.
- `Excel de Transferencias`: columnas del formato de Enviar transferencia + fecha automática.

## Datos y almacenamiento

- SQLite local/offline.
- Supabase como backend productivo compartido.
- Evidencias: Cloudflare R2 o Supabase Storage.
- Conectores configurables: Supabase, VPS/API, PostgreSQL vía API, MySQL vía API, MariaDB vía API, SQL Server vía API y REST genérico.
- ADMIN puede analizar almacenamiento y limpiar archivos huérfanos desde `Nube y respaldo`.

## Respaldos

El respaldo es opcional y admite múltiples destinos simultáneos:

- Google Drive
- OneDrive
- VPS/API
- ZIP local

Los destinos remotos seleccionados pueden participar en el respaldo automático. El ZIP local es manual.

## Configuración JSON

Permite importar/exportar la configuración de la app, incluidos formatos personalizados, catálogos vinculados, infraestructura, almacenamiento, respaldos y credenciales/tokens configurados. El archivo JSON debe tratarse como sensible. No contiene facturas, transferencias, fotografías ni la sesión iniciada.

## Supabase

Para una instalación que ya estaba en 3.14.x, ejecuta en orden:

1. `supabase/migration-3.15.0.sql` — habilita la limpieza administrativa completa, incluidos asientos `ledger`.
2. `supabase/migration-3.15.2.sql` — agrega borrado físico de mensajes, facturas, ledger y demás limpieza ADMIN, además de retirar tombstones antiguas de movimientos eliminables. La app verifica esta migración antes de ejecutar la limpieza.
3. `supabase/migration-3.15.3.sql` — permite de forma segura que el destinatario de un mensaje existente sincronice su respuesta (`CONFIRMADO` o `RECHAZADO`) mediante el UPSERT de `skc_events`. Si ya ejecutaste el hotfix de transferencias del 2026-10-06, este paso es idempotente. La 3.15.4 ya no genera `RECIBIDO` automáticamente.

Para instalaciones atrasadas puede usarse antes `supabase/SKC_SUPABASE_CATCHUP_TO_3.10.1.sql`.

`appConfig` y `catalogs` son compartidos: ADMIN escribe, todos los usuarios autenticados leen. Al guardar un formato la app sincroniza inmediatamente y los demás dispositivos conectados hacen pull aproximadamente cada 3 segundos.

### Cloudflare R2 / evidencias

En 3.15.2 debe redesplegarse `supabase/functions/skc-storage-r2/index.ts`. La firma PUT ya no duplica `x-amz-meta-sha256`, lo que corrige el `403 SignatureDoesNotMatch` observado en evidencias. Si un teléfono todavía alcanza una función R2 antigua y recibe ese 403 concreto, Android intenta la misma evidencia en Supabase Storage como respaldo. Los adjuntos históricos fallidos por esa firma se vuelven a poner en cola si el archivo local todavía existe.

## Compilación

- JDK 17
- compileSdk / targetSdk 36
- Gradle Wrapper 9.3.1

```bash
./gradlew assembleDebug
```

APK esperado:

`app/build/outputs/apk/debug/app-debug.apk`

## Documentación de esta versión

- `CHANGELOG_1.5.1.md` (versión vigente; contador comercial reiniciado)

- `CHANGELOG_3.15.4.md`
- `VERIFICACION_3.15.4.md`
- `CHANGELOG_3.15.2.md`
- `VERIFICACION_3.15.2.md`
- `DATOS_VINCULADOS_CSV_EXCEL.md`
- `EJEMPLO_PROYECTOS.csv`
- `ARQUITECTURA_3.13.0.md` (base de arquitectura vigente)


## Compatibilidad con instalaciones anteriores

**Las APK anteriores de GitHub se compilaban con firmas temporales y podrían
provocar el error "Conflicto con el paquete".**
El flujo actual requiere la firma original antes de publicar nuevos APK.
Consulta [COMPATIBILIDAD_APK.md](COMPATIBILIDAD_APK.md) para preparar
una actualización que preserve los datos sin desinstalar.

## APK automático en GitHub

Cada actualización a `main` activa GitHub Actions. Si la compilación termina correctamente, el APK se publica en GitHub Releases.

**Enlace directo (funciona cuando existe una release exitosa):** https://github.com/magalarza726-del/SKC-facturas/releases/latest/download/SKC-Facturas.apk

Este APK es `debug`, lleva el sufijo de aplicación `.pilot` y está pensado para pruebas. No es una versión release firmada para distribución.


## Portal de descargas

El portal de una sola sección se publica desde [index.html](index.html) (con copia en [docs/index.html](docs/index.html)). El botón descarga la última versión publicada en Releases.

- **Portal:** https://magalarza726-del.github.io/SKC-facturas/
- **APK directa:** https://github.com/magalarza726-del/SKC-facturas/releases/latest/download/SKC-Facturas.apk
- **Versión actual: v1.5.1.** [Cambios de la versión](CHANGELOG_1.5.1.md).
- Historial anterior: [v3.15.5](CHANGELOG_3.15.5.md).
