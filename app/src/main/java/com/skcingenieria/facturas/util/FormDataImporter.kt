package com.skcingenieria.facturas.util

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

private const val FORM_XLSX_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
private const val FORM_REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

/** Workbook already reduced to plain text rows so it can be safely embedded in form configuration. */
data class ImportedFormSheet(
    val name: String,
    val headers: List<String>,
    val rows: List<Map<String, String>>
)

data class ImportedFormWorkbook(
    val fileName: String,
    val sheets: List<ImportedFormSheet>
)

object FormDataImporter {
    private const val MAX_ROWS = 20_000
    private const val MAX_COLUMNS = 200
    private const val MAX_FILE_BYTES = 16 * 1024 * 1024
    private const val MAX_ZIP_ENTRY_BYTES = 16 * 1024 * 1024
    private const val MAX_UNZIPPED_BYTES = 40 * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 2_000

    fun parse(bytes: ByteArray, fileName: String): ImportedFormWorkbook {
        require(bytes.isNotEmpty()) { "El archivo está vacío" }
        require(bytes.size <= MAX_FILE_BYTES) { "El archivo supera el límite de 16 MB" }
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".csv") || lower.endsWith(".txt") || lower.endsWith(".tsv") -> parseDelimited(bytes, fileName)
            lower.endsWith(".xlsx") || lower.endsWith(".xlsm") -> parseXlsx(bytes, fileName)
            lower.endsWith(".xls") -> error("El formato .xls antiguo no es compatible. Ábrelo en Excel y guárdalo como .xlsx o CSV.")
            else -> runCatching { parseXlsx(bytes, fileName) }.getOrElse { parseDelimited(bytes, fileName) }
        }
    }

    private fun parseDelimited(bytes: ByteArray, fileName: String): ImportedFormWorkbook {
        val text = decodeText(bytes).removePrefix("\uFEFF")
        val delimiter = detectDelimiter(text)
        val matrix = parseDelimitedRows(text, delimiter)
            .filter { row -> row.any { it.isNotBlank() } }
        require(matrix.isNotEmpty()) { "El archivo no contiene filas" }
        require(matrix.size <= MAX_ROWS + 1) { "El archivo supera el límite de $MAX_ROWS filas de datos" }
        val headers = uniqueHeaders(matrix.first())
        require(headers.any { it.isNotBlank() }) { "No se encontraron encabezados" }
        val rows = distinctRows(headers, matrix.drop(1).mapNotNull { values ->
            rowMap(headers, values).takeIf { row -> row.values.any(String::isNotBlank) }
        })
        return ImportedFormWorkbook(fileName, listOf(ImportedFormSheet("Datos", headers, rows)))
    }

    private fun parseXlsx(bytes: ByteArray, fileName: String): ImportedFormWorkbook {
        val entries = unzip(bytes)
        require("xl/workbook.xml" in entries) { "El archivo no parece ser un Excel .xlsx válido" }
        val shared = parseSharedStrings(entries["xl/sharedStrings.xml"])
        val sheets = workbookSheets(entries).mapNotNull { (name, path) ->
            val content = entries[path] ?: return@mapNotNull null
            parseSheet(content, shared, name)
        }.filter { it.headers.isNotEmpty() }
        require(sheets.isNotEmpty()) { "El Excel no contiene hojas con datos" }
        return ImportedFormWorkbook(fileName, sheets)
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>, name: String): ImportedFormSheet {
        val doc = parseXml(bytes)
        val rawRows = linkedMapOf<Int, MutableMap<Int, String>>()
        var highestRow = 0
        val cells = doc.getElementsByTagNameNS(FORM_XLSX_NS, "c")
        for (i in 0 until cells.length) {
            val cell = cells.item(i) as? Element ?: continue
            val ref = parseReference(cell.getAttribute("r")) ?: continue
            val (col, row) = ref
            if (col > MAX_COLUMNS) continue
            highestRow = maxOf(highestRow, row)
            if (row > MAX_ROWS + 100) continue
            rawRows.getOrPut(row) { linkedMapOf() }[col] = cellText(cell, shared).trim()
        }
        if (rawRows.isEmpty()) return ImportedFormSheet(name, emptyList(), emptyList())

        val firstRow = rawRows.keys.sorted().firstOrNull { row -> rawRows[row].orEmpty().values.any(String::isNotBlank) }
            ?: return ImportedFormSheet(name, emptyList(), emptyList())
        require(highestRow - firstRow <= MAX_ROWS) { "La hoja $name supera el límite de $MAX_ROWS filas de datos" }
        val lastColumn = rawRows[firstRow].orEmpty().keys.maxOrNull() ?: 0
        if (lastColumn <= 0) return ImportedFormSheet(name, emptyList(), emptyList())
        val headers = uniqueHeaders((1..lastColumn).map { rawRows[firstRow]?.get(it).orEmpty() })
        val rows = distinctRows(headers, rawRows.keys.asSequence().filter { it > firstRow }.sorted().mapNotNull { rowIndex ->
            val values = (1..headers.size).map { col -> rawRows[rowIndex]?.get(col).orEmpty() }
            rowMap(headers, values).takeIf { row -> row.values.any(String::isNotBlank) }
        }.toList())
        return ImportedFormSheet(name, headers, rows)
    }

    private fun uniqueHeaders(values: List<String>): List<String> {
        val used = mutableMapOf<String, Int>()
        return values.take(MAX_COLUMNS).mapIndexed { index, raw ->
            val base = raw.trim().ifBlank { "Columna ${index + 1}" }
            val key = base.lowercase()
            val count = (used[key] ?: 0) + 1
            used[key] = count
            if (count == 1) base else "$base ($count)"
        }
    }

    private fun rowMap(headers: List<String>, values: List<String>): Map<String, String> = buildMap {
        headers.forEachIndexed { index, header -> put(header, values.getOrNull(index).orEmpty().trim()) }
    }

    private fun distinctRows(headers: List<String>, rows: List<Map<String, String>>): List<Map<String, String>> {
        val seen = mutableSetOf<String>()
        return rows.filter { row ->
            val key = headers.joinToString("\u001F") { header -> row[header].orEmpty() }
            seen.add(key)
        }
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return bytes.toString(Charset.forName("UTF-16LE"))
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return bytes.toString(Charset.forName("UTF-16BE"))
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun detectDelimiter(text: String): Char {
        val sample = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        return listOf(';', ',', '\t').maxByOrNull { candidate -> sample.count { it == candidate } }
            ?.takeIf { sample.contains(it) } ?: ','
    }

    private fun parseDelimitedRows(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        fun endCell() { row += cell.toString(); cell.clear() }
        fun endRow() { endCell(); rows += row.toList(); row.clear() }
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && quoted && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == delimiter && !quoted -> endCell()
                (c == '\n' || c == '\r') && !quoted -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    endRow()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    private fun cellText(cell: Element, shared: List<String>): String {
        return when (cell.getAttribute("t")) {
            "s" -> cell.getElementsByTagNameNS(FORM_XLSX_NS, "v").item(0)?.textContent?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
            "inlineStr" -> {
                val texts = cell.getElementsByTagNameNS(FORM_XLSX_NS, "t")
                buildString { for (i in 0 until texts.length) append(texts.item(i)?.textContent.orEmpty()) }
            }
            "str" -> cell.getElementsByTagNameNS(FORM_XLSX_NS, "v").item(0)?.textContent.orEmpty()
            else -> cell.getElementsByTagNameNS(FORM_XLSX_NS, "v").item(0)?.textContent.orEmpty()
        }
    }

    private fun parseSharedStrings(bytes: ByteArray?): List<String> {
        if (bytes == null) return emptyList()
        val doc = parseXml(bytes)
        val result = mutableListOf<String>()
        val strings = doc.getElementsByTagNameNS(FORM_XLSX_NS, "si")
        for (i in 0 until strings.length) {
            val element = strings.item(i) as? Element ?: continue
            val texts = element.getElementsByTagNameNS(FORM_XLSX_NS, "t")
            result += buildString { for (j in 0 until texts.length) append(texts.item(j)?.textContent.orEmpty()) }
        }
        return result
    }

    private fun workbookSheets(entries: Map<String, ByteArray>): List<Pair<String, String>> {
        val workbook = parseXml(entries["xl/workbook.xml"] ?: return emptyList())
        val rels = parseXml(entries["xl/_rels/workbook.xml.rels"] ?: return emptyList())
        val targets = mutableMapOf<String, String>()
        val relationships = rels.documentElement.childNodes
        for (i in 0 until relationships.length) {
            val e = relationships.item(i) as? Element ?: continue
            if (e.localName != "Relationship" && e.tagName != "Relationship") continue
            targets[e.getAttribute("Id")] = resolvePath("xl/workbook.xml", e.getAttribute("Target"))
        }
        val result = mutableListOf<Pair<String, String>>()
        val sheets = workbook.getElementsByTagNameNS(FORM_XLSX_NS, "sheet")
        for (i in 0 until sheets.length) {
            val e = sheets.item(i) as? Element ?: continue
            val id = e.getAttributeNS(FORM_REL_NS, "id").ifBlank { e.getAttribute("r:id") }
            val path = targets[id] ?: continue
            if (path.startsWith("xl/worksheets/")) result += e.getAttribute("name") to path
        }
        return result
    }

    private fun resolvePath(baseFile: String, target: String): String {
        if (target.startsWith('/')) return target.removePrefix("/")
        val base = baseFile.substringBeforeLast('/', "")
        val normalized = mutableListOf<String>()
        (if (base.isBlank()) target else "$base/$target").split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (normalized.isNotEmpty()) normalized.removeAt(normalized.lastIndex)
                else -> normalized += part
            }
        }
        return normalized.joinToString("/")
    }

    private fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var totalUnzipped = 0
        var entryCount = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entryCount++
                require(entryCount <= MAX_ZIP_ENTRIES) { "El Excel contiene demasiados archivos internos" }
                if (!entry.isDirectory) {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    var entryBytes = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        entryBytes += read
                        totalUnzipped += read
                        require(entryBytes <= MAX_ZIP_ENTRY_BYTES) { "Una sección del Excel es demasiado grande" }
                        require(totalUnzipped <= MAX_UNZIPPED_BYTES) { "El Excel descomprimido supera el límite permitido" }
                        output.write(buffer, 0, read)
                    }
                    entries[entry.name] = output.toByteArray()
                }
                input.closeEntry()
            }
        }
        return entries
    }

    private fun parseReference(reference: String): Pair<Int, Int>? {
        val match = Regex("^([A-Za-z]+)([1-9][0-9]*)$").matchEntire(reference.trim()) ?: return null
        var col = 0
        match.groupValues[1].uppercase().forEach { col = col * 26 + (it - 'A' + 1) }
        return col to match.groupValues[2].toInt()
    }

    private fun parseXml(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }
}
