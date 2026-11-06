package com.skcingenieria.facturas.util

import com.skcingenieria.facturas.data.Transaction

object CsvExporter {
    private fun esc(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    fun build(items: List<Transaction>): ByteArray {
        val lines = mutableListOf<String>()
        lines += listOf(
            "Fecha", "Código", "Movimiento", "Monto", "Responsable", "Creado por",
            "Proyecto", "Proyecto 2", "Descripción", "Proveedor", "RUC proveedor", "Correo proveedor",
            "Tipo documento", "# Documento", "Cliente", "Estado evidencia", "Estado registro", "Sincronización"
        ).joinToString(",") { esc(it) }
        items.sortedByDescending { it.purchaseDate }.forEach { tx ->
            lines += listOf(
                tx.purchaseDate, tx.code, tx.movementType, "%.2f".format(java.util.Locale.US, tx.amount),
                tx.accountUsername, tx.createdByName, tx.descriptionBase, tx.project, tx.description,
                tx.supplier, tx.supplierRuc, tx.supplierEmail, tx.documentType, tx.invoiceNumber, tx.client,
                tx.evidenceStatus, tx.recordStatus, tx.syncStatus
            ).joinToString(",") { esc(it) }
        }
        return ("\uFEFF" + lines.joinToString("\r\n") + "\r\n").toByteArray(Charsets.UTF_8)
    }
}
