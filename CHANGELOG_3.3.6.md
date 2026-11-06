# SKC Facturas 3.3.6 — Excel estable

## Motivo
La 3.3.5 eliminó `calcChain.xml`, pero también cambió varias estructuras OOXML a la vez. En una exportación real de Excel Mobile se observó que, cuando no había movimientos, la tabla `Tabla1` pasó de `A1:Q2` a `A1:Q1`, se eliminaron fórmulas calculadas de la tabla y se forzó `fullCalcOnLoad/forceFullCalc`. Excel siguió mostrando reparación y llegó a congelarse después de pulsar **Sí**.

## Estrategia 3.3.6
Se vuelve al motor Excel de 3.3.4 y se aplica únicamente la corrección confirmada por Microsoft Excel:

- eliminar `xl/calcChain.xml`;
- eliminar su Relationship en `xl/_rels/workbook.xml.rels`;
- eliminar su Override en `[Content_Types].xml`.

No se modifica `workbook.xml`, no se fuerza recálculo completo, no se eliminan `calculatedColumnFormula`, no se cambia la política de tabla vacía y no se reescribe el motor de filas.

## Compatibilidad con plantillas
La plantilla oficial embebida ya viene sin `calcChain`. Además, `ExcelTemplateEngine.buildFromTemplate()` pasa cualquier plantilla (incluidas plantillas antiguas/remotas) por `ExcelCalcChainCleaner.clean()` antes de generar la exportación.

## Histórico
La descarga continúa reconstruyéndose desde `excelRows(snapshot)` en cada exportación. Para ADMIN, `visibleTransactions()` entrega todas las transacciones disponibles del snapshot y se agregan transferencias internas según la configuración existente. El Excel del día anterior no se reutiliza como fuente.
