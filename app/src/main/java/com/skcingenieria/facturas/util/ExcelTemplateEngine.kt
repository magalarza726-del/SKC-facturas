package com.skcingenieria.facturas.util

import com.skcingenieria.facturas.data.ExcelColumnMapping
import com.skcingenieria.facturas.data.ExcelTemplateConfig
import com.skcingenieria.facturas.data.Transaction
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

private const val XLSX_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

data class ExcelFieldOption(val key: String, val label: String)

data class ExcelSheetInspection(
    val name: String,
    val detectedHeaderRow: Int,
    val maxColumn: Int,
    val suggestedMappings: List<ExcelColumnMapping>
)

data class ExcelWorkbookInspection(
    val fileName: String,
    val sheets: List<ExcelSheetInspection>
)

data class ExcelCatalogData(
    val costCenters: List<String> = emptyList(),
    val secondaryCostsByCenter: Map<String, List<String>> = emptyMap(),
    val clients: List<String> = emptyList(),
    val sheetName: String = ""
) {
    val isUsable: Boolean get() = costCenters.isNotEmpty() || clients.isNotEmpty()
}

object ExcelFieldCatalog {
    val options: List<ExcelFieldOption> = listOf(
        ExcelFieldOption("", "— No vincular —"),
        ExcelFieldOption("purchaseDate", "Fecha"),
        ExcelFieldOption("year", "Año"),
        ExcelFieldOption("hash", "Símbolo #"),
        ExcelFieldOption("isoWeek", "Semana ISO"),
        ExcelFieldOption("monthName", "Mes"),
        ExcelFieldOption("category", "Categoría automática"),
        ExcelFieldOption("code", "Código del registro"),
        ExcelFieldOption("movementType", "Tipo de movimiento"),
        ExcelFieldOption("amount", "Monto"),
        ExcelFieldOption("income", "Ingreso"),
        ExcelFieldOption("expense", "Egreso"),
        ExcelFieldOption("descriptionBase", "Proyecto"),
        ExcelFieldOption("description", "Descripción / detalle"),
        ExcelFieldOption("area", "Área"),
        ExcelFieldOption("liquidationType", "Tipo de liquidación"),
        ExcelFieldOption("project", "Proyecto 2"),
        ExcelFieldOption("costCenter", "Centro de costo"),
        ExcelFieldOption("secondaryCost", "Costo secundario"),
        ExcelFieldOption("client", "Cliente"),
        ExcelFieldOption("supplier", "Proveedor"),
        ExcelFieldOption("supplierRuc", "RUC del proveedor"),
        ExcelFieldOption("supplierEmail", "Correo del proveedor"),
        ExcelFieldOption("documentType", "Tipo de documento"),
        ExcelFieldOption("invoiceNumber", "# de Documento"),
        ExcelFieldOption("paymentMethod", "Método de pago"),
        ExcelFieldOption("evidenceStatus", "Estado de evidencia"),
        ExcelFieldOption("observations", "Observaciones"),
        ExcelFieldOption("accountUsername", "Responsable"),
        ExcelFieldOption("createdByName", "Creado por"),
        ExcelFieldOption("groupName", "Grupo"),
        ExcelFieldOption("userEmail", "Correo del usuario"),
        ExcelFieldOption("currency", "Moneda"),
        ExcelFieldOption("createdAt", "Fecha de creación"),
        ExcelFieldOption("updatedAt", "Última actualización")
    )

    private val aliases = mapOf(
        "FECHA" to "purchaseDate",
        "DATE" to "purchaseDate",
        "ANO" to "year",
        "YEAR" to "year",
        "SEMANA" to "isoWeek",
        "SEMANAISO" to "isoWeek",
        "MES" to "monthName",
        "CATEGORIA" to "category",
        "CODIGO" to "code",
        "TIPOMOVIMIENTO" to "movementType",
        "MOVIMIENTO" to "movementType",
        "MONTO" to "amount",
        "INGRESO" to "income",
        "INGRESOS" to "income",
        "EGRESO" to "expense",
        "EGRESOS" to "expense",
        "DESCRIPCIONBASE" to "descriptionBase",
        "DESCRIPCION" to "description",
        "DETALLE" to "description",
        "AREA" to "area",
        "TIPODELIQUIDACION" to "liquidationType",
        "LIQUIDACION" to "liquidationType",
        "PROYECTO" to "descriptionBase",
        "PROYECTO2" to "project",
        "CENTROCOSTO" to "costCenter",
        "CENTROCOSTOS" to "costCenter",
        "CCOSTO" to "costCenter",
        "CCOSTOS" to "costCenter",
        "COSTOSECUNDARIO" to "secondaryCost",
        "COSTOSSECUNDARIOS" to "secondaryCost",
        "CCOSTOSECUNDARIO" to "secondaryCost",
        "CCOSTOSSECUNDARIOS" to "secondaryCost",
        "CLIENTE" to "client",
        "PROVEEDOR" to "supplier",
        "RUCPROVEEDOR" to "supplierRuc",
        "CORREOPROVEEDOR" to "supplierEmail",
        "TIPODOCUMENTO" to "documentType",
        "FACTURA" to "invoiceNumber",
        "NUMEROFACTURA" to "invoiceNumber",
        "NFACTURA" to "invoiceNumber",
        "METODOPAGO" to "paymentMethod",
        "FORMAPAGO" to "paymentMethod",
        "ESTADOEVIDENCIA" to "evidenceStatus",
        "EVIDENCIA" to "evidenceStatus",
        "OBSERVACION" to "observations",
        "OBSERVACIONES" to "observations",
        "RESPONSABLE" to "accountUsername",
        "USUARIO" to "accountUsername",
        "CREADOPOR" to "createdByName",
        "GRUPO" to "groupName",
        "CORREO" to "userEmail",
        "EMAIL" to "userEmail",
        "MONEDA" to "currency"
    )

    fun detect(header: String): String {
        val normalized = normalize(header)
        if (normalized.isBlank() || normalized == "GRUPOWS" || normalized == "GRUPOWHATSAPP") return ""
        aliases[normalized]?.let { return it }
        return aliases.entries.firstOrNull { (alias, _) ->
            normalized.contains(alias) || alias.contains(normalized).takeIf { normalized.length >= 5 } == true
        }?.value.orEmpty()
    }

    fun label(key: String): String = when {
        key.startsWith("custom:") -> key.removePrefix("custom:")
        else -> options.firstOrNull { it.key == key }?.label ?: key
    }

    fun display(transaction: Transaction, fieldKey: String): String = when (fieldKey) {
        "purchaseDate" -> TimeUtils.displayDate(transaction.purchaseDate)
        "year" -> transaction.purchaseDate.take(4)
        "hash" -> "#"
        "isoWeek" -> TimeUtils.isoWeek(transaction.purchaseDate).toString()
        "monthName" -> TimeUtils.monthName(transaction.purchaseDate)
        "category" -> category(transaction)
        "code" -> transaction.code
        "movementType" -> transaction.movementType
        "amount" -> formatNumber(transaction.amount)
        "income" -> if (transaction.movementType.equals("Ingreso", true)) formatNumber(transaction.amount) else ""
        "expense" -> if (transaction.movementType.equals("Gasto", true)) formatNumber(transaction.amount) else ""
        "descriptionBase" -> transaction.descriptionBase
        "description" -> transaction.description.ifBlank { transaction.descriptionBase }
        "area" -> transaction.area
        "liquidationType" -> transaction.liquidationType
        "project" -> transaction.project
        "costCenter" -> transaction.costCenter
        "secondaryCost" -> transaction.secondaryCost
        "client" -> transaction.client
        "supplier" -> transaction.supplier
        "supplierRuc" -> transaction.supplierRuc
        "supplierEmail" -> transaction.supplierEmail
        "documentType" -> transaction.documentType
        "invoiceNumber" -> transaction.invoiceNumber
        "paymentMethod" -> transaction.paymentMethod
        "evidenceStatus" -> transaction.evidenceStatus
        "observations" -> transaction.observations
        "accountUsername" -> transaction.accountUsername
        "createdByName" -> transaction.createdByName
        "groupName" -> transaction.groupName
        "userEmail" -> transaction.userEmail
        "currency" -> transaction.currency
        "createdAt" -> transaction.createdAt
        "updatedAt" -> transaction.updatedAt
        else -> if (fieldKey.startsWith("custom:")) transaction.extraFields[fieldKey.removePrefix("custom:")].orEmpty() else ""
    }

    fun numeric(transaction: Transaction, fieldKey: String): Double? = when (fieldKey) {
        "purchaseDate" -> runCatching { LocalDate.parse(transaction.purchaseDate).toEpochDay() + 25569.0 }.getOrNull()
        "amount" -> transaction.amount
        "income" -> transaction.amount.takeIf { transaction.movementType.equals("Ingreso", true) }
        "expense" -> transaction.amount.takeIf { transaction.movementType.equals("Gasto", true) }
        else -> null
    }

    private fun category(t: Transaction): String = if (
        listOf(t.descriptionBase, t.description).any {
            it.contains("almuerzo", true) || it.contains("agua", true) || it.contains("refrigerio", true)
        }
    ) "Alim" else ""

    private fun formatNumber(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun normalize(value: String): String = Normalizer.normalize(value.uppercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .replace("[^A-Z0-9]".toRegex(), "")
}

object ExcelTemplateEngine {
    fun defaultMappings(): List<ExcelColumnMapping> = listOf(
        ExcelColumnMapping("A1", "FECHA", "purchaseDate"),
        ExcelColumnMapping("B1", "AÑO", "year"),
        ExcelColumnMapping("C1", "SEMANA", "isoWeek"),
        ExcelColumnMapping("D1", "MES", "monthName"),
        ExcelColumnMapping("E1", "Area", "area"),
        ExcelColumnMapping("F1", "Tipo de Liquidación", "liquidationType"),
        ExcelColumnMapping("G1", "DESCRIPCIÓN", "descriptionBase"),
        ExcelColumnMapping("H1", "PROYECTO2", "project"),
        ExcelColumnMapping("I1", "Centro Costos", "costCenter"),
        ExcelColumnMapping("J1", "C Costos secundario", "secondaryCost"),
        ExcelColumnMapping("K1", "Cliente", "client"),
        ExcelColumnMapping("L1", "Factura", "invoiceNumber"),
        ExcelColumnMapping("M1", "INGRESOS", "income"),
        ExcelColumnMapping("N1", "EGRESOS", "expense"),
        ExcelColumnMapping("O1", "RESPONSABLE", "accountUsername"),
        ExcelColumnMapping("P1", "GRUPO WS", ""),
        ExcelColumnMapping("Q1", "OBSERVACION2", "observations")
    )

    fun defaultTemplateConfig(): ExcelTemplateConfig = ExcelTemplateConfig(
        enabled = true,
        fileName = "RE-S11.01 FLUJO FM 2026.xlsx",
        sheetName = "FLUJO PROYECTO",
        dataStartRow = 2,
        mappings = defaultMappings()
    )

    fun columns(config: ExcelTemplateConfig): List<ExcelColumnMapping> =
        (if (config.enabled && config.mappings.isNotEmpty()) config.mappings else defaultMappings())
            .sortedBy { referenceColumn(it.reference) ?: Int.MAX_VALUE }

    fun extractCatalogs(bytes: ByteArray): ExcelCatalogData {
        if (bytes.isEmpty()) return ExcelCatalogData()
        val entries = unzip(bytes)
        val shared = parseSharedStrings(entries["xl/sharedStrings.xml"])
        val sheet = workbookSheets(entries).firstOrNull { (name, _) ->
            val normalized = normalizeCatalogText(name)
            normalized == "CCOSTOS" || normalized == "COSTOS" || normalized.contains("COSTOS")
        } ?: return ExcelCatalogData()
        val sheetBytes = entries[sheet.second] ?: return ExcelCatalogData()
        val doc = parseXml(sheetBytes)
        val values = mutableMapOf<Int, MutableMap<Int, String>>()
        var maxRow = 1
        val cells = doc.getElementsByTagNameNS(XLSX_NS, "c")
        for (i in 0 until cells.length) {
            val cell = cells.item(i) as? Element ?: continue
            val parsed = parseReference(cell.getAttribute("r")) ?: continue
            val (col, row) = parsed
            if (row > 1000 || col > 100) continue
            val text = cellText(cell, shared).trim()
            if (text.isNotBlank()) values.getOrPut(row) { mutableMapOf() }[col] = text
            maxRow = maxOf(maxRow, row)
        }
        if (values.isEmpty()) return ExcelCatalogData(sheetName = sheet.first)

        val headerRow = (1..minOf(30, maxRow)).maxByOrNull { row ->
            val rowValues = values[row].orEmpty().values
            val hasClient = rowValues.any { normalizeCatalogText(it) == "CLIENTE" }
            rowValues.size + if (hasClient) 100 else 0
        } ?: 1
        val headers = values[headerRow].orEmpty()
        val clientCol = headers.entries.firstOrNull { normalizeCatalogText(it.value) == "CLIENTE" }?.key
            ?: return ExcelCatalogData(sheetName = sheet.first)
        val categoryColumns = headers.entries
            .filter { (col, header) -> col < clientCol && header.isNotBlank() }
            .sortedBy { it.key }

        fun distinctValues(items: List<String>): List<String> {
            val seen = mutableSetOf<String>()
            return items.map(String::trim).filter(String::isNotBlank).filter { seen.add(normalizeCatalogText(it)) }
        }

        val secondary = linkedMapOf<String, List<String>>()
        categoryColumns.forEach { (col, header) ->
            val valuesForCenter = (headerRow + 1..maxRow).mapNotNull { row -> values[row]?.get(col) }
            secondary[header.trim()] = distinctValues(valuesForCenter)
        }
        val clients = distinctValues((headerRow + 1..maxRow).mapNotNull { row -> values[row]?.get(clientCol) })
        return ExcelCatalogData(
            costCenters = secondary.keys.toList(),
            secondaryCostsByCenter = secondary,
            clients = clients,
            sheetName = sheet.first
        )
    }

    fun inspect(bytes: ByteArray, fileName: String): ExcelWorkbookInspection {
        require(bytes.isNotEmpty()) { "El archivo Excel está vacío" }
        val entries = unzip(bytes)
        require("xl/workbook.xml" in entries) { "El archivo no parece ser un .xlsx válido" }
        val shared = parseSharedStrings(entries["xl/sharedStrings.xml"])
        val sheets = workbookSheets(entries).map { (name, path) -> inspectSheet(name, path, entries, shared) }
        require(sheets.isNotEmpty()) { "El Excel no contiene hojas utilizables" }
        return ExcelWorkbookInspection(fileName, sheets)
    }

    fun validate(config: ExcelTemplateConfig) {
        require(config.sheetName.isNotBlank()) { "Selecciona una hoja del Excel" }
        require(config.dataStartRow >= 1) { "La primera fila de datos no es válida" }
        require(config.mappings.isNotEmpty()) { "Agrega al menos una referencia de columna" }
        val usedColumns = mutableSetOf<Int>()
        config.mappings.forEach { mapping ->
            val parsed = parseReference(mapping.reference)
                ?: throw IllegalArgumentException("Referencia inválida: ${mapping.reference}. Usa A1 o A1:C1.")
            if (mapping.fieldKey.isNotBlank()) {
                require(mapping.fieldKey.startsWith("custom:") || ExcelFieldCatalog.options.any { it.key == mapping.fieldKey }) { "Campo no reconocido: ${mapping.fieldKey}" }
                require(usedColumns.add(parsed.first)) { "Hay dos campos vinculados a la misma columna ${columnName(parsed.first)}" }
            }
        }
        val linkedFields = config.mappings.map { it.fieldKey }.filter { it.isNotBlank() }.toSet()
        require("descriptionBase" in linkedFields) { "El formato Excel debe incluir Proyecto" }
        require("project" in linkedFields) { "El formato Excel debe incluir Proyecto 2" }
    }

    fun buildFromTemplate(
        templateBytes: ByteArray,
        config: ExcelTemplateConfig,
        transactions: List<Transaction>
    ): ByteArray {
        validate(config)
        val entries = unzip(ExcelCalcChainCleaner.clean(templateBytes))
        val sheetPath = workbookSheets(entries).firstOrNull { it.first == config.sheetName }?.second
            ?: error("La hoja ${config.sheetName} ya no existe en el formato Excel")
        val source = entries[sheetPath] ?: error("No se encontró el XML de la hoja ${config.sheetName}")
        val doc = parseXml(source)
        val sheetData = doc.getElementsByTagNameNS(XLSX_NS, "sheetData").item(0) as? Element
            ?: error("La hoja no contiene sheetData")

        val styles = mutableMapOf<Int, String>()
        var sampleRowHeight = ""
        val rows = childElements(sheetData, "row")
        val sample = rows.firstOrNull { it.getAttribute("r").toIntOrNull() == config.dataStartRow }
            ?: rows.firstOrNull { (it.getAttribute("r").toIntOrNull() ?: 0) > config.dataStartRow }
        sample?.let { row ->
            sampleRowHeight = row.getAttribute("ht")
            childElements(row, "c").forEach { c ->
                referenceColumn(c.getAttribute("r"))?.let { col ->
                    c.getAttribute("s").takeIf { it.isNotBlank() }?.let { styles[col] = it }
                }
            }
        }

        rows.filter { (it.getAttribute("r").toIntOrNull() ?: Int.MAX_VALUE) >= config.dataStartRow }
            .forEach { sheetData.removeChild(it) }

        val mappings = config.mappings.filter { it.fieldKey.isNotBlank() }
            .sortedBy { referenceColumn(it.reference) ?: Int.MAX_VALUE }
        val finalRow = if (transactions.isEmpty()) config.dataStartRow else config.dataStartRow + transactions.size - 1
        transactions.sortedBy { it.purchaseDate }.forEachIndexed { index, transaction ->
            val rowNumber = config.dataStartRow + index
            val row = doc.createElementNS(XLSX_NS, "row").apply {
                setAttribute("r", rowNumber.toString())
                if (sampleRowHeight.isNotBlank()) { setAttribute("ht", sampleRowHeight); setAttribute("customHeight", "1") }
            }
            mappings.forEach { mapping ->
                val col = referenceColumn(mapping.reference) ?: return@forEach
                val numeric = ExcelFieldCatalog.numeric(transaction, mapping.fieldKey)
                val cell = doc.createElementNS(XLSX_NS, "c").apply {
                    setAttribute("r", "${columnName(col)}$rowNumber")
                    styles[col]?.let { setAttribute("s", it) }
                }
                if (numeric != null) {
                    cell.appendChild(doc.createElementNS(XLSX_NS, "v").apply { textContent = numeric.toString() })
                } else {
                    cell.setAttribute("t", "inlineStr")
                    val inline = doc.createElementNS(XLSX_NS, "is")
                    inline.appendChild(doc.createElementNS(XLSX_NS, "t").apply {
                        val value = ExcelFieldCatalog.display(transaction, mapping.fieldKey)
                        if (value.startsWith(" ") || value.endsWith(" ")) setAttribute("xml:space", "preserve")
                        textContent = value
                    })
                    cell.appendChild(inline)
                }
                row.appendChild(cell)
            }
            sheetData.appendChild(row)
        }

        val maxCol = config.mappings.mapNotNull { referenceColumn(it.reference) }.maxOrNull() ?: 1
        val headerRow = config.mappings.mapNotNull { parseReference(it.reference)?.second }.maxOrNull()
            ?.coerceAtMost(config.dataStartRow - 1) ?: (config.dataStartRow - 1).coerceAtLeast(1)
        (doc.getElementsByTagNameNS(XLSX_NS, "dimension").item(0) as? Element)?.setAttribute(
            "ref", "A1:${columnName(maxCol)}${finalRow.coerceAtLeast(headerRow)}"
        )
        (doc.getElementsByTagNameNS(XLSX_NS, "autoFilter").item(0) as? Element)?.let { filter ->
            val oldStart = filter.getAttribute("ref").substringBefore(':').ifBlank { "A$headerRow" }
            filter.setAttribute("ref", "$oldStart:${columnName(maxCol)}${finalRow.coerceAtLeast(config.dataStartRow)}")
        }
        updateValidationRanges(doc, config.dataStartRow, finalRow.coerceAtLeast(config.dataStartRow))
        updateSheetTables(entries, sheetPath, config.dataStartRow, finalRow.coerceAtLeast(config.dataStartRow))

        entries[sheetPath] = xmlBytes(doc)
        return zip(entries)
    }

    private fun inspectSheet(
        name: String,
        path: String,
        entries: Map<String, ByteArray>,
        shared: List<String>
    ): ExcelSheetInspection {
        val doc = parseXml(entries[path] ?: return ExcelSheetInspection(name, 1, 1, listOf(ExcelColumnMapping("A1"))))
        val mergedTopLeft = mutableMapOf<String, String>()
        val merges = doc.getElementsByTagNameNS(XLSX_NS, "mergeCell")
        for (i in 0 until merges.length) {
            val ref = (merges.item(i) as? Element)?.getAttribute("ref").orEmpty()
            ref.substringBefore(':').takeIf { it.isNotBlank() }?.let { mergedTopLeft[it] = ref }
        }

        val valuesByRow = mutableMapOf<Int, MutableMap<Int, Pair<String, String>>>()
        var maxColumn = 1
        val cells = doc.getElementsByTagNameNS(XLSX_NS, "c")
        for (i in 0 until cells.length) {
            val c = cells.item(i) as? Element ?: continue
            val ref = c.getAttribute("r")
            val parsed = parseReference(ref) ?: continue
            val (col, row) = parsed
            if (row > 80 || col > 100) continue
            maxColumn = maxOf(maxColumn, col)
            val text = cellText(c, shared)
            valuesByRow.getOrPut(row) { mutableMapOf() }[col] = (mergedTopLeft[ref] ?: ref) to text
        }

        val dimensionRef = (doc.getElementsByTagNameNS(XLSX_NS, "dimension").item(0) as? Element)?.getAttribute("ref").orEmpty()
        dimensionRef.substringAfter(':', dimensionRef).let { last -> referenceColumn(last)?.let { maxColumn = maxOf(maxColumn, it.coerceAtMost(100)) } }

        val candidateRows = (1..minOf(30, valuesByRow.keys.maxOrNull() ?: 1))
        val headerRow = candidateRows.maxByOrNull { row ->
            val values = valuesByRow[row].orEmpty().values.map { it.second }.filter { it.isNotBlank() }
            val recognized = values.count { ExcelFieldCatalog.detect(it).isNotBlank() }
            values.size + recognized * 4
        } ?: 1

        val rowValues = valuesByRow[headerRow].orEmpty()
        val suggested = (1..maxColumn).map { col ->
            val cell = rowValues[col]
            val ref = cell?.first ?: "${columnName(col)}$headerRow"
            val header = cell?.second.orEmpty()
            ExcelColumnMapping(ref, header, ExcelFieldCatalog.detect(header))
        }
        return ExcelSheetInspection(name, headerRow, maxColumn, suggested)
    }

    private fun updateValidationRanges(doc: Document, dataStartRow: Int, finalRow: Int) {
        val standard = doc.getElementsByTagNameNS(XLSX_NS, "dataValidation")
        for (i in 0 until standard.length) {
            val element = standard.item(i) as? Element ?: continue
            val sqref = element.getAttribute("sqref")
            if (sqref.isNotBlank()) element.setAttribute("sqref", extendRowRanges(sqref, dataStartRow, finalRow))
        }
        val sqrefs = doc.getElementsByTagNameNS("*", "sqref")
        for (i in 0 until sqrefs.length) {
            val node = sqrefs.item(i) ?: continue
            val text = node.textContent.orEmpty()
            if (text.isNotBlank()) node.textContent = extendRowRanges(text, dataStartRow, finalRow)
        }
    }

    private fun extendRowRanges(value: String, dataStartRow: Int, finalRow: Int): String =
        value.split(Regex("\\s+")).joinToString(" ") { token ->
            val match = Regex("^(\\$?[A-Z]+)\\$?([0-9]+)(?::(\\$?[A-Z]+)\\$?([0-9]+))?$").matchEntire(token)
                ?: return@joinToString token
            val startCol = match.groupValues[1]
            val startRow = match.groupValues[2].toIntOrNull() ?: return@joinToString token
            val endCol = match.groupValues[3].ifBlank { startCol }
            val endRow = match.groupValues[4].toIntOrNull() ?: startRow
            if (startRow >= dataStartRow || endRow >= dataStartRow) "$startCol$startRow:$endCol$finalRow" else token
        }

    private fun updateSheetTables(entries: MutableMap<String, ByteArray>, sheetPath: String, dataStartRow: Int, finalRow: Int) {
        val relPath = sheetPath.substringBeforeLast('/') + "/_rels/" + sheetPath.substringAfterLast('/') + ".rels"
        val relBytes = entries[relPath] ?: return
        val relDoc = parseXml(relBytes)
        val relations = relDoc.documentElement.childNodes
        val tablePaths = mutableListOf<String>()
        for (i in 0 until relations.length) {
            val relation = relations.item(i) as? Element ?: continue
            if (!relation.getAttribute("Type").endsWith("/table")) continue
            relation.getAttribute("Target").takeIf { it.isNotBlank() }?.let { tablePaths += resolvePath(sheetPath, it) }
        }
        tablePaths.forEach { tablePath ->
            val bytes = entries[tablePath] ?: return@forEach
            val tableDoc = parseXml(bytes)
            val table = tableDoc.documentElement
            val ref = table.getAttribute("ref")
            val parts = ref.split(':', limit = 2)
            if (parts.size != 2) return@forEach
            val start = parseReference(parts[0]) ?: return@forEach
            val end = parseReference(parts[1]) ?: return@forEach
            if (end.second < dataStartRow) return@forEach
            val newRef = "${columnName(start.first)}${start.second}:${columnName(end.first)}$finalRow"
            table.setAttribute("ref", newRef)
            (tableDoc.getElementsByTagNameNS(XLSX_NS, "autoFilter").item(0) as? Element)?.setAttribute("ref", newRef)
            entries[tablePath] = xmlBytes(tableDoc)
        }
    }

    private fun cellText(cell: Element, shared: List<String>): String {
        return when (cell.getAttribute("t")) {
            "s" -> cell.getElementsByTagNameNS(XLSX_NS, "v").item(0)?.textContent?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
            "inlineStr" -> {
                val texts = cell.getElementsByTagNameNS(XLSX_NS, "t")
                buildString { for (i in 0 until texts.length) append(texts.item(i)?.textContent.orEmpty()) }
            }
            else -> cell.getElementsByTagNameNS(XLSX_NS, "v").item(0)?.textContent.orEmpty()
        }
    }

    private fun parseSharedStrings(bytes: ByteArray?): List<String> {
        if (bytes == null) return emptyList()
        val doc = parseXml(bytes)
        val list = mutableListOf<String>()
        val strings = doc.getElementsByTagNameNS(XLSX_NS, "si")
        for (i in 0 until strings.length) {
            val si = strings.item(i) as? Element ?: continue
            val texts = si.getElementsByTagNameNS(XLSX_NS, "t")
            list += buildString { for (j in 0 until texts.length) append(texts.item(j)?.textContent.orEmpty()) }
        }
        return list
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
        val sheets = workbook.getElementsByTagNameNS(XLSX_NS, "sheet")
        for (i in 0 until sheets.length) {
            val e = sheets.item(i) as? Element ?: continue
            val id = e.getAttributeNS(REL_NS, "id").ifBlank { e.getAttribute("r:id") }
            val path = targets[id] ?: continue
            if (path.startsWith("xl/worksheets/")) result += e.getAttribute("name") to path
        }
        return result
    }

    private fun resolvePath(baseFile: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val base = baseFile.substringBeforeLast('/', "")
        val parts = (if (base.isBlank()) target else "$base/$target").split('/')
        val normalized = mutableListOf<String>()
        parts.forEach { part -> when (part) { "", "." -> Unit; ".." -> if (normalized.isNotEmpty()) normalized.removeAt(normalized.lastIndex); else -> normalized += part } }
        return normalized.joinToString("/")
    }

    private fun normalizeCatalogText(value: String): String = Normalizer
        .normalize(value.uppercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .replace("[^A-Z0-9]".toRegex(), "")

    private fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name] = input.readBytes()
                input.closeEntry()
            }
        }
        return entries
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun parseXml(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun xmlBytes(document: Document): ByteArray {
        val out = ByteArrayOutputStream()
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            setOutputProperty(OutputKeys.STANDALONE, "yes")
        }
        transformer.transform(DOMSource(document), StreamResult(out))
        return out.toByteArray()
    }

    private fun childElements(parent: Element, localName: String): List<Element> = buildList {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val e = children.item(i) as? Element ?: continue
            if (e.localName == localName || e.tagName == localName) add(e)
        }
    }

    fun referenceColumn(reference: String): Int? = parseReference(reference)?.first

    fun parseReference(reference: String): Pair<Int, Int>? {
        val first = reference.trim().uppercase(Locale.ROOT).substringBefore(':')
        val match = Regex("^([A-Z]+)([1-9][0-9]*)$").matchEntire(first) ?: return null
        var col = 0
        match.groupValues[1].forEach { col = col * 26 + (it - 'A' + 1) }
        return col to match.groupValues[2].toInt()
    }

    fun columnName(number: Int): String {
        var n = number.coerceAtLeast(1)
        var result = ""
        while (n > 0) {
            n--
            result = ('A'.code + n % 26).toChar() + result
            n /= 26
        }
        return result
    }
}
