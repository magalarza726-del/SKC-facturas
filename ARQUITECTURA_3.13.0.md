# Arquitectura funcional 3.13.0

```text
SKC Facturas
├─ Factura
│  ├─ Monto
│  ├─ Proveedor
│  ├─ Número de factura
│  └─ Evidencia
│     + fecha/responsable automáticos
├─ Mensajes
│  ├─ Solicitar → monto + motivo
│  └─ Enviar    → monto + motivo + evidencia
├─ Historial
│  ├─ Facturas
│  ├─ Transferencias
│  └─ Solicitudes
├─ Recordatorios
│  └─ Solo pendientes
└─ Configuración ADMIN
   ├─ Usuarios
   ├─ Formatos personalizados
   └─ Nube y respaldo
```

## Datos personalizados

Cada formato contiene una lista ordenada de `FormFieldConfig`. Los identificadores no están limitados a una lista fija. Los campos propios se guardan en `extraFields`, manteniendo compatibilidad con Supabase y con el JSON de configuración.

## Excel

El XLSX se genera en el dispositivo a partir de los campos configurados. No depende de una plantilla externa.

## Persistencia

- SQLite: caché/local offline.
- Supabase: backend compartido productivo actual.
- Evidencias: Supabase Storage o Cloudflare R2.
- Conectores alternativos: preparados por API HTTPS.
- Respaldos: opcionales y múltiples.
