package com.skcingenieria.facturas.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplierSearchTest {
    private val catalogs = Catalogs(
        suppliers = listOf(
            SupplierDefinition("s1", "ÁVILA GARCÍA", "0101000000001", "avila@example.com"),
            SupplierDefinition("s2", "GERARDO ORTIZ E HIJOS", "0190072002001", "ventas@ortiz.example"),
            SupplierDefinition("s3", "ORTIZ SERVICIOS", "0999999999001", "contacto@example.com")
        )
    )

    @Test
    fun searchIgnoresAccentsAndPunctuation() {
        assertEquals("s1", catalogs.supplierCandidates("avila garcia").first().id)
    }

    @Test
    fun searchMatchesRucAndEmail() {
        assertEquals("s2", catalogs.supplierCandidates("0190072").first().id)
        assertEquals("s2", catalogs.supplierCandidates("ventas@ortiz").first().id)
    }

    @Test
    fun startsWithRanksBeforeContains() {
        val ids = catalogs.supplierCandidates("ortiz").map { it.id }
        assertTrue(ids.indexOf("s3") < ids.indexOf("s2"))
    }

    @Test
    fun exactLookupSupportsNameAndId() {
        assertEquals("s1", catalogs.supplier("ÁVILA GARCÍA")?.id)
        assertEquals("s2", catalogs.supplier("s2")?.id)
    }
}
