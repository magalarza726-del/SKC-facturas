package com.skcingenieria.facturas.util

import com.skcingenieria.facturas.data.ExcelTemplateConfig
import com.skcingenieria.facturas.data.Transaction
import com.skcingenieria.facturas.data.Message
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ExcelExporter {

    private fun txField(t: Transaction, key: String): String = when (key) {
        "amount" -> t.amount.toString()
        "supplier" -> t.supplier
        "supplierRuc" -> t.supplierRuc
        "supplierEmail" -> t.supplierEmail
        "invoiceNumber" -> t.invoiceNumber
        "attachments" -> t.attachments.joinToString(" | ") { it.name.ifBlank { it.storageKey.ifBlank { it.remotePath } } }
        "purchaseDate" -> t.purchaseDate
        "accountUserId" -> t.accountUsername.ifBlank { t.accountUserId }
        "movementType" -> t.movementType
        "descriptionBase" -> t.descriptionBase
        "project" -> t.project
        "description" -> t.description
        "area" -> t.area
        "liquidationType" -> t.liquidationType
        "costCenter" -> t.costCenter
        "secondaryCost" -> t.secondaryCost
        "client" -> t.client
        "paymentMethod" -> t.paymentMethod
        "observations" -> t.observations
        "documentType" -> t.documentType
        else -> t.extraFields[key].orEmpty()
    }

    private fun messageField(m: Message, key: String): String = when (key) {
        "amount" -> m.amount.toString()
        "reason" -> m.reason
        "attachments" -> m.attachments.joinToString(" | ") { it.name.ifBlank { it.storageKey.ifBlank { it.remotePath } } }
        "transferDate" -> m.transferDate.ifBlank { m.createdAt.take(10) }
        "reference" -> m.transferReference
        "project" -> m.project
        "recipientUserId" -> m.recipientName.ifBlank { m.recipientUserId }
        "recipientEmail" -> m.recipientEmail
        "balanceDeclared" -> m.balanceDeclared.toString()
        else -> m.extraFields[key].orEmpty()
    }

    private fun buildDynamicTable(sheetName: String, headers: List<String>, data: List<List<String>>): ByteArray {
        val safeHeaders = headers.ifEmpty { listOf("Registro") }
        val safeData = if (headers.isEmpty()) data.map { listOf(it.joinToString(" | ")) } else data
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun add(name: String, text: String) {
                z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry()
            }
            add("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""")
            add("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            add("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${esc(sheetName)}" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            add("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            add("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF0B67B2"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFill="1" applyFont="1"/></cellXfs></styleSheet>""")
            val rows = StringBuilder()
            rows.append("""<row r="1">""")
            safeHeaders.forEachIndexed { i, h -> rows.append(cell("${col(i + 1)}1", h, 1)) }
            rows.append("</row>")
            safeData.forEachIndexed { idx, row ->
                val r = idx + 2
                rows.append("""<row r="$r">""")
                safeHeaders.indices.forEach { i -> rows.append(cell("${col(i + 1)}$r", row.getOrElse(i) { "" })) }
                rows.append("</row>")
            }
            val lastCol = col(safeHeaders.size)
            val cols = safeHeaders.indices.joinToString("") { i -> """<col min="${i + 1}" max="${i + 1}" width="22" customWidth="1"/>""" }
            val sheet = """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols>$cols</cols><sheetData>$rows</sheetData><autoFilter ref="A1:${lastCol}1"/></worksheet>"""
            add("xl/worksheets/sheet1.xml", sheet)
        }
        return out.toByteArray()
    }

    fun buildInvoicesDynamic(transactions: List<Transaction>, fields: List<com.skcingenieria.facturas.data.FormFieldConfig>): ByteArray {
        val visible = fields.filter { it.visible || it.key in setOf("purchaseDate", "accountUserId") }.sortedBy { it.order }
        return buildDynamicTable("FACTURAS", visible.map { it.label }, transactions.sortedBy { it.purchaseDate }.map { t -> visible.map { txField(t, it.key) } })
    }

    fun buildTransfersDynamic(messages: List<Message>, fields: List<com.skcingenieria.facturas.data.FormFieldConfig>): ByteArray {
        val visible = fields.filter { it.visible || it.key == "transferDate" }.sortedBy { it.order }
        return buildDynamicTable("TRANSFERENCIAS", visible.map { it.label }, messages.filter { it.messageType == "TRANSFERENCIA" }.sortedBy { it.transferDate.ifBlank { it.createdAt } }.map { m -> visible.map { messageField(m, it.key) } })
    }

    fun build(
        transactions: List<Transaction>,
        config: ExcelTemplateConfig,
        templateBytes: ByteArray?,
        defaultTemplateBytes: ByteArray? = null
    ): ByteArray {
        if (config.enabled) {
            require(templateBytes != null && templateBytes.isNotEmpty()) { "No se pudo cargar el formato Excel activo" }
            return ExcelTemplateEngine.buildFromTemplate(templateBytes, config, transactions)
        }
        if (defaultTemplateBytes != null && defaultTemplateBytes.isNotEmpty()) {
            return ExcelTemplateEngine.buildFromTemplate(defaultTemplateBytes, ExcelTemplateEngine.defaultTemplateConfig(), transactions)
        }
        return build(transactions)
    }

    private val headers=listOf("FECHA","AÑO","SEMANA","MES","Area","Tipo de Liquidación","DESCRIPCIÓN","PROYECTO2","Centro Costos","C Costos secundario","Cliente","Factura","INGRESOS","EGRESOS","RESPONSABLE","GRUPO WS","OBSERVACION2")
    private fun esc(v:String)=v.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    private fun col(n:Int):String { var x=n; var s=""; while(x>0){x--;s=('A'.code+x%26).toChar()+s;x/=26};return s }
    private fun cell(ref:String,value:String,style:Int=0)="<c r=\"$ref\" t=\"inlineStr\" s=\"$style\"><is><t>${esc(value)}</t></is></c>"
    private fun num(ref:String,value:Double,style:Int=0)="<c r=\"$ref\" s=\"$style\"><v>$value</v></c>"

    fun build(transactions:List<Transaction>):ByteArray {
        val out=ByteArrayOutputStream();ZipOutputStream(out).use{z->
            fun add(name:String,text:String){z.putNextEntry(ZipEntry(name));z.write(text.toByteArray());z.closeEntry()}
            add("[Content_Types].xml","""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""")
            add("_rels/.rels","""<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            add("xl/workbook.xml","""<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="REGISTRO" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            add("xl/_rels/workbook.xml.rels","""<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            add("xl/styles.xml","""<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF0B67B2"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFDDEBF7"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFill="1" applyFont="1"/><xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs></styleSheet>""")
            val rows=StringBuilder();rows.append("<row r=\"1\">");headers.forEachIndexed{i,h->rows.append(cell("${col(i+1)}1",h,1))};rows.append("</row>")
            transactions.sortedBy{it.purchaseDate}.forEachIndexed { idx,t ->
                val r=idx+2
                val text=listOf(TimeUtils.displayDate(t.purchaseDate),t.purchaseDate.take(4),TimeUtils.isoWeek(t.purchaseDate).toString(),TimeUtils.monthName(t.purchaseDate),t.area,t.liquidationType,t.descriptionBase.ifBlank{t.description},t.project,t.costCenter,t.secondaryCost,t.client,t.invoiceNumber)
                rows.append("<row r=\"$r\">");text.forEachIndexed{i,v->rows.append(cell("${col(i+1)}$r",v))}
                if(t.movementType.equals("Ingreso",true))rows.append(num("M$r",t.amount,2)) else rows.append(cell("M$r",""))
                if(t.movementType.equals("Gasto",true))rows.append(num("N$r",t.amount,2)) else rows.append(cell("N$r",""))
                rows.append(cell("O$r",t.accountUsername));rows.append(cell("P$r",""));rows.append(cell("Q$r",t.observations));rows.append("</row>")
            }
            val sheet="""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols>${listOf(12,4,8,12,4,10,46,48,18,24,30,15,13,13,18,16,42).mapIndexed{i,w->"<col min=\"${i+1}\" max=\"${i+1}\" width=\"$w\" customWidth=\"1\"/>"}.joinToString("")}</cols><sheetData>$rows</sheetData><autoFilter ref="A1:Q1"/></worksheet>"""
            add("xl/worksheets/sheet1.xml",sheet)
        };return out.toByteArray()
    }
    fun buildTransfers(messages: List<Message>): ByteArray {
        val transferHeaders = listOf("FECHA", "CÓDIGO", "DE", "PARA", "MONTO", "PROYECTO", "MOTIVO", "REFERENCIA", "ESTADO")
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun add(name: String, text: String) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
            add("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""")
            add("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            add("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="TRANSFERENCIAS" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            add("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            add("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF0B67B2"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFill="1" applyFont="1"/><xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs></styleSheet>""")
            val rows = StringBuilder()
            rows.append("<row r=\"1\">")
            transferHeaders.forEachIndexed { i, h -> rows.append(cell("${col(i + 1)}1", h, 1)) }
            rows.append("</row>")
            messages.filter { it.messageType == "TRANSFERENCIA" }.sortedBy { it.transferDate.ifBlank { it.createdAt } }.forEachIndexed { idx, m ->
                val r = idx + 2
                rows.append("<row r=\"$r\">")
                rows.append(cell("A$r", TimeUtils.displayDate(m.transferDate.ifBlank { m.createdAt.take(10) })))
                rows.append(cell("B$r", m.code))
                rows.append(cell("C$r", m.senderName))
                rows.append(cell("D$r", m.recipientName.ifBlank { m.recipientEmail }))
                rows.append(num("E$r", m.amount, 2))
                rows.append(cell("F$r", m.project))
                rows.append(cell("G$r", m.reason))
                rows.append(cell("H$r", m.transferReference))
                rows.append(cell("I$r", m.status))
                rows.append("</row>")
            }
            val widths = listOf(14, 20, 24, 24, 14, 24, 42, 24, 18)
            val sheet = """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols>${widths.mapIndexed { i, w -> "<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>" }.joinToString("")}</cols><sheetData>$rows</sheetData><autoFilter ref="A1:I1"/></worksheet>"""
            add("xl/worksheets/sheet1.xml", sheet)
        }
        return out.toByteArray()
    }

}
