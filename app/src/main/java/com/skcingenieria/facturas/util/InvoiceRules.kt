package com.skcingenieria.facturas.util

/** Shared invoice invariants used by both Compose UI and repository validation. */
object InvoiceRules {
    const val FACTURA = "FACTURA"
    const val NOTA_CREDITO = "NOTA_CREDITO"

    fun normalizeDocumentType(value: String): String =
        if (value.equals(NOTA_CREDITO, ignoreCase = true)) NOTA_CREDITO else FACTURA

    fun indicatesAttached(evidenceStatus: String): Boolean =
        evidenceStatus.contains("adjunt", ignoreCase = true) ||
            evidenceStatus.contains("incluid", ignoreCase = true)

    fun evidenceError(evidenceStatus: String, attachmentCount: Int): String? =
        if (indicatesAttached(evidenceStatus) && attachmentCount <= 0)
            "Marcaste que el comprobante está incluido, pero no adjuntaste ninguna foto o PDF."
        else null

    fun documentError(documentType: String, documentNumber: String): String? =
        if (normalizeDocumentType(documentType) == NOTA_CREDITO && documentNumber.trim().isBlank())
            "El # de Documento es obligatorio para una nota de crédito"
        else null
}
