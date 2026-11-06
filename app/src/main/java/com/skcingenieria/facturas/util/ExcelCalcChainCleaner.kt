package com.skcingenieria.facturas.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Corrección OOXML deliberadamente mínima.
 *
 * El archivo oficial de SKC heredaba xl/calcChain.xml de una versión anterior
 * de la plantilla. Después de que la app reemplaza las filas de datos, esa
 * cadena puede seguir apuntando a fórmulas que ya no existen y Microsoft Excel
 * la reporta como contenido dañado.
 *
 * Esta clase NO reescribe workbook.xml, worksheets, tablas, validaciones,
 * estilos ni fórmulas. Solo elimina las tres referencias de calcChain:
 *   1) xl/calcChain.xml
 *   2) su Relationship en xl/_rels/workbook.xml.rels
 *   3) su Override en [Content_Types].xml
 *
 * Mantener la intervención así de pequeña evita cambiar estructuras que ya eran
 * estables en la versión 3.3.4, especialmente en Excel Mobile.
 */
object ExcelCalcChainCleaner {
    private val relationshipRegex = Regex(
        pattern = """<(?:(?:[A-Za-z_][A-Za-z0-9_.-]*):)?Relationship\\b[^>]*/>""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val overrideRegex = Regex(
        pattern = """<(?:(?:[A-Za-z_][A-Za-z0-9_.-]*):)?Override\\b[^>]*/>""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    fun clean(bytes: ByteArray): ByteArray {
        require(bytes.isNotEmpty()) { "El archivo Excel está vacío" }
        if (isClean(bytes)) return bytes

        val output = ByteArrayOutputStream(bytes.size)
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            ZipOutputStream(output).use { zip ->
                while (true) {
                    val sourceEntry = input.nextEntry ?: break
                    val name = sourceEntry.name

                    if (name.equals("xl/calcChain.xml", ignoreCase = true)) {
                        input.closeEntry()
                        continue
                    }

                    val sourceBytes = if (sourceEntry.isDirectory) ByteArray(0) else input.readBytes()
                    val resultBytes = when (name) {
                        "xl/_rels/workbook.xml.rels" -> removeCalcChainRelationship(sourceBytes)
                        "[Content_Types].xml" -> removeCalcChainContentType(sourceBytes)
                        else -> sourceBytes
                    }

                    val targetEntry = ZipEntry(name).apply {
                        // Conservar la marca de tiempo cuando exista. No copiamos method/CRC
                        // porque ZipOutputStream los recalcula de forma segura.
                        if (sourceEntry.time >= 0) time = sourceEntry.time
                    }
                    zip.putNextEntry(targetEntry)
                    if (!sourceEntry.isDirectory) zip.write(resultBytes)
                    zip.closeEntry()
                    input.closeEntry()
                }
            }
        }

        return output.toByteArray()
    }

    fun isClean(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        var hasCalcChainPart = false
        var relationshipHasCalcChain = false
        var contentTypeHasCalcChain = false

        val ok = runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val name = entry.name
                    when {
                        name.equals("xl/calcChain.xml", ignoreCase = true) -> hasCalcChainPart = true
                        name == "xl/_rels/workbook.xml.rels" -> {
                            relationshipHasCalcChain = input.readBytes()
                                .toString(Charsets.UTF_8)
                                .contains("calcChain", ignoreCase = true)
                        }
                        name == "[Content_Types].xml" -> {
                            contentTypeHasCalcChain = input.readBytes()
                                .toString(Charsets.UTF_8)
                                .contains("calcChain", ignoreCase = true)
                        }
                    }
                    input.closeEntry()
                }
            }
        }.isSuccess

        return ok && !hasCalcChainPart && !relationshipHasCalcChain && !contentTypeHasCalcChain
    }

    private fun removeCalcChainRelationship(bytes: ByteArray): ByteArray {
        val text = bytes.toString(Charsets.UTF_8)
        if (!text.contains("calcChain", ignoreCase = true)) return bytes
        return relationshipRegex.replace(text) { match ->
            if (match.value.contains("calcChain", ignoreCase = true)) "" else match.value
        }.toByteArray(Charsets.UTF_8)
    }

    private fun removeCalcChainContentType(bytes: ByteArray): ByteArray {
        val text = bytes.toString(Charsets.UTF_8)
        if (!text.contains("calcChain", ignoreCase = true)) return bytes
        return overrideRegex.replace(text) { match ->
            if (match.value.contains("calcChain", ignoreCase = true)) "" else match.value
        }.toByteArray(Charsets.UTF_8)
    }
}
