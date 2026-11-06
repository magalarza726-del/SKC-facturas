# Datos vinculados desde CSV / Excel

SKC Facturas 3.15.0 permite que cualquier campo de tipo **Celda desplegable vinculada** use un CSV o Excel como catálogo.

## Ejemplo: Proyectos

Archivo:

| Proyecto | Subproyecto | Proyecto 2 | Cliente | Código |
|---|---|---|---|---|
| Edificio Norte | Estructura | Torre A | Cliente Alpha | PR-001 |
| Edificio Norte | Instalaciones | Torre A | Cliente Alpha | PR-002 |
| Edificio Norte | Instalaciones | Torre B | Cliente Beta | PR-003 |

Configuración recomendada:

- `Proyecto`: **Columna principal**.
- `Subproyecto`: **Subcategoría**.
- `Proyecto 2`: **Subcategoría**.
- `Cliente`: **Dato vinculado**, visible si quieres mostrarlo en el formulario.
- `Código`: **Dato vinculado**, puede quedar oculto y aun así se guarda.

En el formulario el flujo queda así: **Proyecto → Subproyecto → Proyecto 2**. Cada nivel filtra al siguiente usando solo las filas compatibles del archivo.

## Cómo configurarlo

1. Abre **Configuración → Formatos personalizados** y elige Factura, Solicitar o Enviar.
2. Crea un campo nuevo o edita uno existente.
3. Elige **Celda desplegable vinculada**.
4. Pulsa **Vincular CSV / Excel** y selecciona el archivo.
5. Si el Excel tiene varias hojas, selecciona la hoja que contiene el catálogo.
6. Asigna exactamente una **Columna principal** y las **Subcategorías** necesarias.
7. Marca las demás columnas como **Dato vinculado** o **Ignorar**.
8. Para un dato vinculado puedes elegir un campo existente de SKC o dejar que se guarde automáticamente como dato adicional.
9. Guarda el campo y después pulsa **Guardar formatos**.

## Comportamiento importante

- La búsqueda es escribible y muestra coincidencias; no obliga a recorrer una lista desplegable enorme.
- Las subcategorías son dependientes. No se habilita el segundo nivel hasta seleccionar el anterior.
- Las columnas vinculadas ocultas **sí se guardan**; simplemente no se muestran al usuario durante el llenado.
- Si una combinación sigue repetida, la app pide agregar otra columna como subcategoría para identificar una fila de forma inequívoca.
- Al reemplazar un archivo, se intentan conservar los roles, nombres, destinos, visibilidad y orden de las columnas cuyos encabezados sigan existiendo.
- El archivo se **importa** a la configuración del formulario; no queda enlazado en vivo con su ubicación original. Si el archivo externo cambia, usa **Reemplazar archivo** para actualizarlo.

## Formatos y límites

Compatibles: `.csv`, `.tsv`, `.txt`, `.xlsx` y `.xlsm`. El formato antiguo `.xls` debe guardarse primero como `.xlsx` o CSV.

Por estabilidad, el importador acepta hasta 16 MB por archivo, 20.000 filas de datos y 200 columnas.
