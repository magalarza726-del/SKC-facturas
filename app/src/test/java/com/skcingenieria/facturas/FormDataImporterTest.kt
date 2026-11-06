package com.skcingenieria.facturas

import com.skcingenieria.facturas.util.FormDataImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormDataImporterTest {
    @Test
    fun csvSupportsHierarchicalColumnsAndQuotedValues() {
        val csv = """Proyecto;Subproyecto;Proyecto 2;Cliente
P1;SP1;P1-A;"Cliente; Uno"
P1;SP2;P1-B;Cliente Dos
P2;SP3;P2-A;Cliente Tres
""".trimIndent()

        val workbook = FormDataImporter.parse(csv.toByteArray(), "proyectos.csv")
        val sheet = workbook.sheets.single()

        assertEquals(listOf("Proyecto", "Subproyecto", "Proyecto 2", "Cliente"), sheet.headers)
        assertEquals(3, sheet.rows.size)
        assertEquals("Cliente; Uno", sheet.rows.first()["Cliente"])
        assertEquals("P1-B", sheet.rows[1]["Proyecto 2"])
    }

    @Test
    fun duplicateRowsAreRemovedWithoutChangingDistinctHierarchyRows() {
        val csv = """Proyecto,Subproyecto,Codigo
P1,SP1,001
P1,SP1,001
P1,SP2,002
""".trimIndent()

        val sheet = FormDataImporter.parse(csv.toByteArray(), "proyectos.csv").sheets.single()
        assertEquals(2, sheet.rows.size)
        assertTrue(sheet.rows.any { it["Subproyecto"] == "SP2" })
    }
}
