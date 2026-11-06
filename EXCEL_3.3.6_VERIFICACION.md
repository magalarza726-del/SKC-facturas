# Verificación Excel 3.3.6

## Archivo real 3.3.5 recibido desde el teléfono
- ZIP válido.
- `xl/calcChain.xml`: ausente.
- `Tabla1`: `A1:Q1`.
- `dimension`: `A1:Q1`.
- validaciones: siguen en fila 2.
- `calculatedColumnFormula`: eliminadas.
- `calcPr`: `calcMode="auto" forceFullCalc="1" fullCalcOnLoad="1"`.

Esto demuestra que el popup de 3.3.5 ya no provenía de `calcChain`; había una regresión adicional en la reestructuración OOXML.

## Generador 3.3.6
Prueba JVM del motor real:

### Histórico vacío
- ZIP íntegro.
- 17 entradas.
- `calcChain`: ausente en parte, relaciones y Content Types.
- `Tabla1`: `A1:Q2` (comportamiento estable 3.3.4).
- `dimension`: `A1:Q2`.
- `calcPr`: se conserva como `calcId="191028"`.
- `calculatedColumnFormula`: se conservan.

### Un movimiento
- ZIP íntegro.
- `Tabla1`: `A1:Q2`.
- fila 2 generada correctamente.
- estilos y catálogos conservados.

## Compilación Android
El núcleo Kotlin de Excel fue compilado y ejecutado con `kotlinc`/JDK. El Gradle Wrapper no puede completar la compilación Android dentro del entorno de preparación porque intenta descargar Gradle 9.3.1 y no hay salida de red.
