package com.skcingenieria.facturas.util

import com.skcingenieria.facturas.data.SupplierDefinition

object SupplierCsvImporter {
    data class Result(val suppliers: List<SupplierDefinition>, val skipped: Int)

    fun parse(text: String): Result {
        val clean = text.removePrefix("\uFEFF").trim()
        if (clean.isBlank()) return Result(emptyList(), 0)
        val lines = clean.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return Result(emptyList(), 0)
        val delimiter = detectDelimiter(lines.first())
        val rows = lines.map { parseLine(it, delimiter) }
        val header = rows.first().map(::normalizeHeader)
        val hasHeader = header.any { it in NAME_HEADERS + RUC_HEADERS + EMAIL_HEADERS }
        val start = if (hasHeader) 1 else 0
        val nameIndex = if (hasHeader) header.indexOfFirst { it in NAME_HEADERS }.takeIf { it >= 0 } ?: 0 else 0
        val rucIndex = if (hasHeader) header.indexOfFirst { it in RUC_HEADERS }.takeIf { it >= 0 } ?: 1 else 1
        val emailIndex = if (hasHeader) header.indexOfFirst { it in EMAIL_HEADERS }.takeIf { it >= 0 } ?: 2 else 2

        var skipped = 0
        val seen = mutableSetOf<String>()
        val suppliers = buildList {
            rows.drop(start).forEachIndexed { index, row ->
                val name = row.getOrNull(nameIndex).orEmpty().trim()
                val ruc = row.getOrNull(rucIndex).orEmpty().trim()
                val email = row.getOrNull(emailIndex).orEmpty().trim()
                if (name.isBlank()) { skipped++; return@forEachIndexed }
                val key = name.lowercase()
                if (!seen.add(key)) { skipped++; return@forEachIndexed }
                add(SupplierDefinition(
                    id = "supplier-${CodeUtils.slug(name).ifBlank { index.toString() }}-${name.lowercase().hashCode().toUInt().toString(16)}",
                    name = name,
                    ruc = ruc,
                    email = email
                ))
            }
        }
        return Result(suppliers, skipped)
    }

    private fun detectDelimiter(line: String): Char = listOf(';', ',', '\t')
        .maxByOrNull { candidate -> line.count { it == candidate } }
        ?.takeIf { line.contains(it) } ?: ','

    private fun parseLine(line: String, delimiter: Char): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"'); i++
                }
                c == '"' -> quoted = !quoted
                c == delimiter && !quoted -> { out += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        out += current.toString()
        return out
    }

    private fun normalizeHeader(value: String): String = java.text.Normalizer
        .normalize(value.trim().lowercase(), java.text.Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .replace("[^a-z0-9]".toRegex(), "")

    private val NAME_HEADERS = setOf("proveedor", "nombreproveedor", "nombre", "razonsocial", "nombrecomercial")
    private val RUC_HEADERS = setOf("ruc", "identificacion", "identificaciontributaria", "taxid")
    private val EMAIL_HEADERS = setOf("correo", "email", "correoelectronico", "mail")
}
