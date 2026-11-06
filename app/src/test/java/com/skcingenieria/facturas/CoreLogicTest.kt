package com.skcingenieria.facturas

import com.skcingenieria.facturas.data.*
import com.skcingenieria.facturas.util.CodeUtils
import com.skcingenieria.facturas.network.StorageIntegrity
import com.skcingenieria.facturas.network.StorageServiceFactory
import com.skcingenieria.facturas.util.ExcelExporter
import com.skcingenieria.facturas.util.SupplierCsvImporter
import com.skcingenieria.facturas.util.InvoiceRules
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CoreLogicTest {
    @Test fun defaultUsersAreRealAndZero(){
        val users=SeedData.users()
        assertEquals(listOf("Dalton","Evelyn","Javier","Karen","Tito","Administración"),users.map{it.name})
        assertTrue(users.all{it.initialBalance==0.0})
        assertEquals(ROLE_ADMIN, users.first{it.id=="administracion"}.role)
        assertTrue(users.first{it.id=="administracion"}.transferEnabled)
        assertEquals("administracion@gmail.skc", users.first{it.id=="administracion"}.email)
        assertEquals(ROLE_ADMIN, users.first{it.id=="dalton"}.role)
        assertTrue(users.filter{it.id !in listOf("administracion", "dalton")}.all{it.role==ROLE_USER})
        assertFalse(users.any{it.name.contains("Demo",true)})
    }

    @Test fun noDefaultOperationalUserIsSelected(){
        assertEquals("", LocalSettings().profileUserId)
    }

    @Test fun authUserIdRoundTripsInUserProfile(){
        val user=UserProfile(id="javier",name="Javier",email="javier@skc.com",authUserId="auth-123")
        assertEquals("auth-123", UserProfile.fromJson(user.toJson()).authUserId)
    }

    @Test fun codesAreHumanReadable(){
        val c=CodeUtils.transactionCode("Javier","2026-08-14")
        assertTrue(c.startsWith("SKC-20260814-JAVIER-"))
    }

    @Test fun ledgerSignsAreCorrect(){
        val base=LedgerEntry("1","AJUSTE","1","A","javier","Javier","INGRESO",10.0,"","","","","","x")
        assertEquals(10.0,base.signedAmount(),0.001)
        assertEquals(-10.0,base.copy(direction="EGRESO").signedAmount(),0.001)
    }

    @Test fun webMessageReasonAndAttachmentFilenameAreCompatible(){
        val msg=Message.fromJson(JSONObject("""{
          "id":"m1","code":"SOL-1","messageType":"SOLICITUD_MONTO","senderUserId":"javier","senderName":"Javier",
          "recipientUserId":"karen","recipientName":"Karen","amount":10,"reason":"Compra de material","createdAt":"","updatedAt":"",
          "attachments":[{"id":"a1","filename":"factura.jpg","mime":"image/jpeg","size":10,"sha256":"abc"}]
        }"""))
        assertEquals("Compra de material",msg.reason)
        assertEquals("Compra de material",msg.concept)
        assertEquals("factura.jpg",msg.attachments.first().name)
        assertEquals("factura.jpg",msg.toJson().getJSONArray("attachments").getJSONObject(0).getString("filename"))
    }


    @Test fun requestResponseLinksRoundTrip(){
        val msg=Message(
            id="m1", code="TRF-1", messageType="TRANSFERENCIA", senderUserId="karen", senderName="Karen",
            recipientUserId="javier", recipientName="Javier", createdAt="", updatedAt="",
            sourceRequestId="sol-1", responseTransferId="", respondedByUserId="karen", respondedAt="2026-08-24T20:00:00Z"
        )
        val restored=Message.fromJson(msg.toJson())
        assertEquals("sol-1", restored.sourceRequestId)
        assertEquals("karen", restored.respondedByUserId)
        assertEquals("2026-08-24T20:00:00Z", restored.respondedAt)
    }

    @Test fun webReminderAliasesRoundTrip(){
        val reminder=Reminder.fromJson(JSONObject("""{
          "id":"r1","userId":"javier","username":"Javier","reason":"Compra","status":"CANCELADO","nextNotifyAt":"",
          "completedTransactionId":"tx1","cancellationReason":"Duplicado","notificationCount":2,"createdAt":"","updatedAt":""
        }"""))
        assertEquals("tx1",reminder.linkedTransactionId)
        assertEquals("Duplicado",reminder.cancelReason)
        assertEquals(2,reminder.notificationCount)
    }

    @Test fun coreFieldsCannotBeHiddenByImportedWebConfig(){
        val json=JSONObject("""{
          "id":"global","forms":{"invoice":[{"id":"amount","label":"Valor","visible":false,"required":false,"order":5}]}
        }""")
        val config=AppConfig.fromJson(json)
        val amount=config.fields("invoice").first{it.key=="amount"}
        assertTrue(amount.visible)
        assertTrue(amount.required)
        assertEquals("Valor",amount.label)
    }

    @Test fun excelIsRealZipXlsx(){
        val tx=Transaction("1","SKC-1","Gasto","2026-08-14","Material","Proyecto","Compra",amount=12.5,accountUserId="javier",accountUsername="Javier",createdByUserId="javier",createdByName="Javier",createdAt="",updatedAt="")
        val bytes=ExcelExporter.build(listOf(tx))
        assertTrue(bytes.size>1000)
        assertEquals('P'.code.toByte(),bytes[0])
        assertEquals('K'.code.toByte(),bytes[1])
    }
    @Test fun officialExcelCatalogsAndMappingsAreReady(){
        val catalogs=SeedData.catalogs()
        assertTrue(catalogs.costCenters.contains("Suministros"))
        assertTrue(catalogs.secondaryCostsByCenter["Suministros"].orEmpty().contains("Materiales de oficina"))
        assertTrue(catalogs.clients.contains("BORSEA S.A."))
        val mapping=com.skcingenieria.facturas.util.ExcelTemplateEngine.defaultMappings()
        assertEquals("", mapping.first{it.header=="GRUPO WS"}.fieldKey)
        assertEquals("income", mapping.first{it.header=="INGRESOS"}.fieldKey)
        assertEquals("expense", mapping.first{it.header=="EGRESOS"}.fieldKey)
    }

    @Test fun customInvoiceFieldAndTransferExcelFlagRoundTrip(){
        val custom=FormFieldConfig("custom_1","Orden interna",visible=true,required=false,order=99,core=false)
        val config=AppConfig(forms=defaultFormLayouts().toMutableMap().apply { put("invoice", defaultFormLayouts()["invoice"].orEmpty()+custom) }, includeInternalTransfersInExcel=true)
        val restored=AppConfig.fromJson(config.toJson())
        assertTrue(restored.includeInternalTransfersInExcel)
        assertEquals("Orden interna", restored.fields("invoice").first{it.key=="custom_1"}.label)
    }

    @Test fun requiredFlagRoundTripsForAdministratorFormChanges(){
        val custom=FormFieldConfig("custom_required","Orden obligatoria",visible=true,required=true,order=99,core=false)
        val config=AppConfig(forms=defaultFormLayouts().toMutableMap().apply {
            put("invoice", defaultFormLayouts()["invoice"].orEmpty()+custom)
        })
        val restored=AppConfig.fromJson(config.toJson())
        assertTrue(restored.fields("invoice").first{it.key=="custom_required"}.required)
    }

    @Test fun linkedTableFormConfigurationRoundTrips(){
        val table = LinkedTableConfig(
            enabled = true,
            sourceName = "proyectos.xlsx",
            sheetName = "Proyectos",
            headers = listOf("Proyecto", "Subproyecto", "Cliente"),
            rows = listOf(mapOf("Proyecto" to "P1", "Subproyecto" to "SP1", "Cliente" to "ACME")),
            columns = listOf(
                LinkedTableColumnConfig("Proyecto", LINKED_COLUMN_PRIMARY, "Proyecto", order = 0),
                LinkedTableColumnConfig("Subproyecto", LINKED_COLUMN_SUBCATEGORY, "Subproyecto", order = 1),
                LinkedTableColumnConfig("Cliente", LINKED_COLUMN_LINKED, "Cliente", targetKey = "client", visible = false, order = 2)
            )
        )
        val field = FormFieldConfig("projects", "Proyectos", fieldType = FIELD_LINKED_SELECT, linkedTable = table)
        val restored = FormFieldConfig.fromJson(field.toJson())
        assertTrue(restored.linkedTable.isUsable())
        assertEquals("Proyecto", restored.linkedTable.primary()?.sourceColumn)
        assertEquals("Subproyecto", restored.linkedTable.subcategories().single().sourceColumn)
        assertEquals("client", restored.linkedTable.linked().single().targetKey)
        assertFalse(restored.linkedTable.linked().single().visible)
        assertEquals("ACME", restored.linkedTable.rows.single()["Cliente"])
    }

    @Test fun storageConfigAndAttachmentProviderRoundTrip(){
        val storage=StorageConfig(provider=STORAGE_R2, basePrefix="skc", imageMaxMb=4, optimizeImages=true)
        val config=AppConfig(storage=storage)
        val restored=AppConfig.fromJson(config.toJson())
        assertEquals(STORAGE_R2, restored.storage.provider)
        assertEquals(4, restored.storage.imageMaxMb)

        val attachment=Attachment("a1","factura.pdf","application/pdf",123,"abc",storageProvider=STORAGE_R2,storageKey="skc/u1/factura.pdf",storageObjectId="opaque-1",storageStatus=STORAGE_AVAILABLE)
        val restoredAttachment=Attachment.fromJson(attachment.toJson())
        assertEquals(STORAGE_R2, restoredAttachment.storageProvider)
        assertEquals("skc/u1/factura.pdf", restoredAttachment.storageKey)
        assertEquals("opaque-1", restoredAttachment.storageObjectId)
        assertEquals(STORAGE_AVAILABLE, restoredAttachment.storageStatus)
    }

    @Test fun legacyRemotePathRemainsSupabaseCompatible(){
        val legacy=Attachment.fromJson(JSONObject("""{
          "id":"a1","filename":"foto.jpg","mime":"image/jpeg","size":10,"sha256":"x","remotePath":"facturas/2026/08/foto.jpg"
        }"""))
        assertEquals(STORAGE_SUPABASE, legacy.storageProvider)
        assertEquals("facturas/2026/08/foto.jpg", legacy.storageKey)
        assertEquals(STORAGE_AVAILABLE, legacy.storageStatus)
    }

    @Test fun storageIntegrityUsesActualPayload() {
        val file = kotlin.io.path.createTempFile("skc-storage-test", ".bin").toFile()
        try {
            file.writeBytes(ByteArray(4096) { (it % 251).toByte() })
            val fileHash = StorageIntegrity.sha256(file)
            val bytesHash = StorageIntegrity.sha256(file.readBytes())
            assertEquals(fileHash, bytesHash)
            assertEquals(setOf(STORAGE_SUPABASE, STORAGE_R2), StorageServiceFactory.supportedProviders())
        } finally {
            file.delete()
        }
    }

    @Test fun officialExcelMappingHasExactlySeventeenUniqueColumns(){
        val config=com.skcingenieria.facturas.util.ExcelTemplateEngine.defaultTemplateConfig()
        com.skcingenieria.facturas.util.ExcelTemplateEngine.validate(config)
        assertEquals(17, config.mappings.size)
        assertEquals(17, config.mappings.map { it.reference.substringBefore(":") }.distinct().size)
        assertEquals(1, config.mappings.count { it.header == "Cliente" })
    }

    @Test fun dataSchemaVersionRoundTrips(){
        val catalogs=Catalogs(schemaVersion=DATA_SCHEMA_VERSION)
        assertEquals(DATA_SCHEMA_VERSION, Catalogs.fromJson(catalogs.toJson()).schemaVersion)
        val config=AppConfig(schemaVersion=DATA_SCHEMA_VERSION)
        assertEquals(DATA_SCHEMA_VERSION, AppConfig.fromJson(config.toJson()).schemaVersion)
    }

    @Test fun project2AndClientsShareLevelTwoUnderProject(){
        val definition=ProjectDefinition(
            id="mantenimiento",
            description="Mantenimiento",
            project2Options=listOf("Proyecto A","Proyecto B"),
            clients=listOf("CLIENTE A")
        )
        val catalogs=Catalogs(projects=listOf(definition), clients=listOf("CLIENTE GLOBAL")).normalizedProjectHierarchy()
        assertEquals(listOf("CLIENTE A"), catalogs.clientsFor("Mantenimiento", "Proyecto A"))
        assertEquals(listOf("CLIENTE A"), catalogs.clientsFor("Mantenimiento", "Proyecto B"))
        val general=catalogs.project(GENERAL_PROJECT_ID)!!
        assertTrue(general.project2Options.containsAll(listOf("Proyecto A","Proyecto B")))
        assertTrue(general.allClients().containsAll(listOf("CLIENTE GLOBAL","CLIENTE A")))
        val restored=ProjectDefinition.fromJson(definition.toJson())
        assertEquals(listOf("CLIENTE A"), restored.allClients())
    }

    @Test fun legacyProject2ClientsCollapseToProjectLevel(){
        val legacy=ProjectDefinition(
            id="p",
            description="Proyecto",
            project2Options=listOf("P2-A","P2-B"),
            clientsByProject2=mapOf("P2-A" to listOf("CLIENTE A"), "P2-B" to listOf("CLIENTE B"))
        )
        val normalized=Catalogs(projects=listOf(legacy)).normalizedProjectHierarchy()
        assertEquals(listOf("CLIENTE A","CLIENTE B"), normalized.project("p")!!.allClients())
        assertTrue(normalized.project(GENERAL_PROJECT_ID)!!.allClients().containsAll(listOf("CLIENTE A","CLIENTE B")))
    }


    @Test fun invoiceRulesRejectMissingPromisedEvidenceAndMissingCreditNoteNumber(){
        assertTrue(InvoiceRules.indicatesAttached("Comprobante adjunto"))
        assertNotNull(InvoiceRules.evidenceError("Comprobante incluido", 0))
        assertNull(InvoiceRules.evidenceError("Comprobante incluido", 1))
        assertNull(InvoiceRules.evidenceError("Compra sin comprobante", 0))
        assertNotNull(InvoiceRules.documentError("NOTA_CREDITO", ""))
        assertNull(InvoiceRules.documentError("NOTA_CREDITO", "NC-001"))
        assertNull(InvoiceRules.documentError("FACTURA", ""))
    }

    @Test fun invoiceDefaultsFocusOnAmountSupplierAndDocument(){
        val fields=defaultFormLayouts()["invoice"].orEmpty().associateBy{it.key}
        assertTrue(fields.getValue("amount").visible)
        assertTrue(fields.getValue("amount").required)
        assertTrue(fields.getValue("supplier").visible)
        assertTrue(fields.getValue("supplier").required)
        assertEquals("Número de factura", fields.getValue("invoiceNumber").label)
        assertFalse(fields.getValue("costCenter").visible)
        assertFalse(fields.getValue("secondaryCost").visible)
        assertFalse(fields.getValue("descriptionBase").visible)
    }

    @Test fun supplierCatalogAndTransactionMetadataRoundTrip(){
        val supplier=SupplierDefinition("s1","PROVEEDOR UNO","0999999999001","facturas@proveedor.ec")
        val catalogs=Catalogs(suppliers=listOf(supplier)).normalizedProjectHierarchy()
        assertEquals("0999999999001", catalogs.supplier("PROVEEDOR UNO")?.ruc)
        val restoredCatalogs=Catalogs.fromJson(catalogs.toJson())
        assertEquals("facturas@proveedor.ec", restoredCatalogs.suppliers.single().email)

        val tx=Transaction(
            id="t1", code="SKC-1", movementType="Gasto", purchaseDate="2026-09-01",
            descriptionBase="", project="", description="", supplier=supplier.name,
            supplierRuc=supplier.ruc, supplierEmail=supplier.email, documentType="NOTA_CREDITO", invoiceNumber="NC-001",
            amount=10.0, accountUserId="u1", accountUsername="U1", createdByUserId="u1", createdByName="U1",
            createdAt="", updatedAt="", recordStatus="REVOCACION_SOLICITADA", revocationRequestId="m1"
        )
        val restored=Transaction.fromJson(tx.toJson())
        assertEquals("0999999999001", restored.supplierRuc)
        assertEquals("NOTA_CREDITO", restored.documentType)
        assertEquals("REVOCACION_SOLICITADA", restored.recordStatus)
        assertEquals("m1", restored.revocationRequestId)
    }

    @Test fun supplierSearchMatchesNameWithoutAccentsAndLinkedData(){
        val catalogs=Catalogs(suppliers=listOf(
            SupplierDefinition("s1","OÑATE ÁLVAREZ FRANKLIN","0105055461001","facturas@onate.ec"),
            SupplierDefinition("s2","OTRO PROVEEDOR","0999999999001","otro@proveedor.ec")
        ))
        assertEquals("0105055461001", catalogs.supplierCandidates("onate").single().ruc)
        assertEquals("OÑATE ÁLVAREZ FRANKLIN", catalogs.supplierCandidates("010505").single().name)
        assertEquals("OÑATE ÁLVAREZ FRANKLIN", catalogs.supplierCandidates("facturas@onate").single().name)
    }

    @Test fun supplierCsvImporterAcceptsCommonHeadersAndQuotedNames(){
        val csv="""Proveedor;RUC;Correo
"Proveedor, S.A.";0991112223001;facturas@proveedor.ec
OTRO;0990000000001;otro@proveedor.ec"""
        val result=SupplierCsvImporter.parse(csv)
        assertEquals(2, result.suppliers.size)
        assertEquals("Proveedor, S.A.", result.suppliers.first().name)
        assertEquals("0991112223001", result.suppliers.first().ruc)
        assertEquals("facturas@proveedor.ec", result.suppliers.first().email)
    }

    @Test fun clientDirectorySearchesCommercialAndLegalNames(){
        val catalogs=Catalogs(
            clients=listOf("INDUSTRIAS EJEMPLO S.A."),
            clientDirectory=listOf(ClientDirectoryEntry("c1","Marca Ejemplo","INDUSTRIAS EJEMPLO S.A."))
        ).normalizedProjectHierarchy()
        assertEquals("INDUSTRIAS EJEMPLO S.A.", catalogs.clientCandidates("Marca").single().legalName)
        assertEquals("Marca Ejemplo", catalogs.clientCandidates("INDUSTRIAS").single().commercialName)
    }

    @Test fun revocationMessageKeepsRelatedTransaction(){
        val msg=Message(
            id="m1", code="REV-1", messageType="SOLICITUD_REVOCACION", senderUserId="u1", senderName="U1",
            recipientUserId="admin", recipientName="Admin", reason="Factura equivocada", status="PENDIENTE",
            relatedTransactionId="tx1", createdAt="", updatedAt=""
        )
        val restored=Message.fromJson(msg.toJson())
        assertEquals("tx1", restored.relatedTransactionId)
        assertEquals("SOLICITUD_REVOCACION", restored.messageType)
    }

    @Test fun release315DefaultInvoiceFormIsCurrent(){
        assertEquals(351, DATA_SCHEMA_VERSION)
        assertEquals(610, FORM_SCHEMA_VERSION)
        val invoice=defaultFormLayouts().getValue("invoice").associateBy { it.key }
        assertTrue(invoice.getValue("movementType").visible)
        assertTrue(invoice.getValue("movementType").required)
        assertEquals("Gasto", invoice.getValue("movementType").defaultValue)
        assertEquals(FIELD_SELECT, invoice.getValue("movementType").fieldType)
        assertTrue(invoice.getValue("project").visible)
        assertEquals(FIELD_LINKED_SELECT, invoice.getValue("project").fieldType)
        assertTrue(invoice.getValue("observations").visible)
        assertTrue(invoice.getValue("attachments").required)
    }

    @Test fun messageEditDeleteMetadataRoundTrips(){
        val message=Message(
            id="m1", code="MSG-1", messageType="SOLICITUD_MONTO", senderUserId="u1", senderName="A",
            recipientUserId="u2", recipientName="B", amount=10.0, createdAt="2026-10-02T10:00:00Z", updatedAt="2026-10-02T10:05:00Z",
            editedAt="2026-10-02T10:04:00Z", editedByUserId="u1", deletedAt="", deletedByUserId=""
        )
        val restored=Message.fromJson(message.toJson())
        assertEquals(message.editedAt, restored.editedAt)
        assertEquals(message.editedByUserId, restored.editedByUserId)
    }

    @Test fun formsRevisionRoundTripsWithConfiguration(){
        val config=AppConfig(formsUpdatedAt="2026-09-05T19:00:00Z", formsSchemaVersion=FORM_SCHEMA_VERSION)
        val restored=AppConfig.fromJson(config.toJson())
        assertEquals("2026-09-05T19:00:00Z", restored.formsUpdatedAt)
        assertEquals(FORM_SCHEMA_VERSION, restored.formsSchemaVersion)
    }

    @Test fun sharedRevocationKeyRoundTrips(){
        val config=AppConfig(revocationKey="SKC-REV-350")
        val restored=AppConfig.fromJson(config.toJson())
        assertEquals("SKC-REV-350", restored.revocationKey)
    }

    @Test fun officialExcelProjectsMapToProjectAndProject2(){
        val mapping=com.skcingenieria.facturas.util.ExcelTemplateEngine.defaultMappings()
        assertEquals("descriptionBase", mapping.first { it.header == "DESCRIPCIÓN" }.fieldKey)
        assertEquals("project", mapping.first { it.header == "PROYECTO2" }.fieldKey)
    }

    @Test fun excelValidationRequiresBothProjectLevels(){
        val base=com.skcingenieria.facturas.util.ExcelTemplateEngine.defaultTemplateConfig()
        com.skcingenieria.facturas.util.ExcelTemplateEngine.validate(base)
        val withoutProject2=base.copy(mappings=base.mappings.filterNot { it.fieldKey == "project" })
        assertThrows(IllegalArgumentException::class.java) {
            com.skcingenieria.facturas.util.ExcelTemplateEngine.validate(withoutProject2)
        }
    }

    @Test fun allFormModulesAndEditableFieldsRoundTrip(){
        var config = AppConfig(schemaVersion = DATA_SCHEMA_VERSION)
        val invoice = config.fields("invoice").map { field ->
            when (field.key) {
                "invoiceNumber" -> field.copy(label = "Documento / NC", visible = false, required = true, defaultValue = "NC-")
                else -> field
            }
        } + FormFieldConfig("custom_persist", "Código interno", visible = true, required = false, order = 99, core = false)
        val transfer = config.fields("transfer").map { field ->
            if (field.key == "reference") field.copy(label = "Referencia", visible = true, required = true) else field
        }
        config = config.withFields("invoice", invoice).withFields("transfer", transfer).copy(revocationKey = "SKC-350")
        val restored = AppConfig.fromJson(config.toJson())
        val document = restored.fields("invoice").first { it.key == "invoiceNumber" }
        assertEquals("Documento / NC", document.label)
        assertFalse(document.visible)
        assertTrue(document.required)
        assertEquals("NC-", document.defaultValue)
        assertEquals("Código interno", restored.fields("invoice").first { it.key == "custom_persist" }.label)
        val reference = restored.fields("transfer").first { it.key == "reference" }
        assertTrue(reference.visible)
        assertTrue(reference.required)
        assertEquals("SKC-350", restored.revocationKey)
    }

}
