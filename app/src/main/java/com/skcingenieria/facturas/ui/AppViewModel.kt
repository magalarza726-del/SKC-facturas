package com.skcingenieria.facturas.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skcingenieria.facturas.data.*
import com.skcingenieria.facturas.repository.*
import com.skcingenieria.facturas.worker.BackupScheduler
import com.skcingenieria.facturas.util.CsvExporter
import com.skcingenieria.facturas.util.ExcelExporter
import com.skcingenieria.facturas.util.ExcelTemplateEngine
import com.skcingenieria.facturas.util.ExcelWorkbookInspection
import com.skcingenieria.facturas.util.FileUtils
import com.skcingenieria.facturas.util.FormDataImporter
import com.skcingenieria.facturas.util.ImportedFormWorkbook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AppRepository(app)

    var snapshot by mutableStateOf(repo.snapshot()); private set
    var screen by mutableStateOf(Screen.HOME)
    var busy by mutableStateOf(false); private set
    var notice by mutableStateOf(""); private set
    var error by mutableStateOf(""); private set
    var duplicate by mutableStateOf<DuplicateDetected?>(null); private set
    var invoicePrefill by mutableStateOf(""); private set
    var editingInvoiceId by mutableStateOf<String?>(null); private set
    var excelInspection by mutableStateOf<ExcelWorkbookInspection?>(null); private set
    var formDataWorkbook by mutableStateOf<ImportedFormWorkbook?>(null); private set
    var formsSaveRevision by mutableStateOf(0L); private set
    var initialSetupOpen by mutableStateOf(repo.initialSetupOpen()); private set
    var adminSpaceReport by mutableStateOf<AdminSpaceReport?>(null); private set

    private val operationMutex = Mutex()
    private var pendingExcelTemplateBytes: ByteArray? = null
    private var completingReminderId: String? = null
    private var pendingInvoice: Pair<InvoiceDraft, List<Uri>>? = null
    private var pollJob: Job? = null

    init { startPoller() }

    fun refresh() {
        snapshot = repo.snapshot()
        initialSetupOpen = repo.initialSetupOpen()
    }
    fun navigate(value: Screen) {
        if (screen == Screen.INVOICE && value != Screen.INVOICE) editingInvoiceId = null
        screen = value
        error = ""
        notice = ""
    }
    fun clearMessages() { notice = ""; error = "" }

    fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            operationMutex.withLock {
                busy = true
                error = ""
                try {
                    block()
                    refresh()
                } catch (e: Exception) {
                    error = e.message ?: "Error"
                } finally {
                    busy = false
                }
            }
        }
    }

    fun sync() = run {
        val result = withContext(Dispatchers.IO) { repo.syncNow() }
        notice = "Sincronización completa · ${result.message}"
    }
    fun testConnection() = run { withContext(Dispatchers.IO) { repo.testConnection() }; notice = "Conexión correcta" }
    fun testStorageConnection() = run {
        val result = withContext(Dispatchers.IO) { repo.testStorageConnection() }
        notice = "${result.message}${result.bucket.takeIf { it.isNotBlank() }?.let { " · Bucket: $it" }.orEmpty()} · Lectura: ${if (result.readOk) "OK" else "ERROR"} · Escritura: ${if (result.writeOk) "OK" else "ERROR"}"
    }

    fun retryFailedStorage() = run {
        val result = withContext(Dispatchers.IO) { repo.retryFailedStorage() }
        notice = "Reintento completado · ${result.message}"
    }

    fun saveSupabase(url: String, key: String, poll: Int, auto: Boolean, allowSignup: Boolean, lockIdentity: Boolean) = run {
        withContext(Dispatchers.IO) { repo.saveSupabase(url, key, poll, auto, allowSignup, lockIdentity) }
        notice = "Conexión guardada"; startPoller()
    }
    fun saveNotifications(enabled: Boolean) { repo.saveNotifications(enabled); refresh() }
    fun signIn(email: String, password: String) = run { withContext(Dispatchers.IO) { repo.signIn(email, password) }; notice = "Sesión iniciada" }
    fun signUp(email: String, password: String) = run { withContext(Dispatchers.IO) { repo.signUp(email, password) }; notice = "Cuenta creada" }
    fun signOut() { repo.signOut(); refresh(); notice = "Sesión cerrada" }
    fun setProfile(id: String) { repo.setProfile(id); refresh() }
    fun bootstrapAdmin(id: String, email: String) = run { withContext(Dispatchers.IO) { repo.bootstrapAdmin(id, email) }; notice = "Administrador configurado" }

    fun saveUser(user: UserProfile) = run { withContext(Dispatchers.IO) { repo.saveUser(user); runCatching { repo.syncNow() } }; notice = "Usuario guardado y sincronizado" }
    fun saveCatalogs(catalogs: Catalogs) = run {
        withContext(Dispatchers.IO) { repo.saveCatalogs(catalogs); runCatching { repo.syncNow() } }
        notice = "Catálogos guardados y sincronizados"
    }
    fun updateCatalogs(catalogs: Catalogs) = run {
        withContext(Dispatchers.IO) { repo.saveCatalogs(catalogs); runCatching { repo.syncNow() } }
    }
    fun reloadOfficialExcelCatalogs() = run {
        withContext(Dispatchers.IO) { repo.reloadOfficialExcelCatalogs() }
        notice = "Catálogos sincronizados desde la hoja C. Costos del Excel oficial"
    }
    fun importSuppliers(uri: Uri) = run {
        val app = getApplication<Application>()
        val text = withContext(Dispatchers.IO) {
            app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("No se pudo leer el archivo de proveedores")
        }
        val result = withContext(Dispatchers.IO) { repo.importSuppliersCsv(text) }
        notice = "Proveedores importados: ${result.suppliers.size}${if (result.skipped > 0) " · Omitidos: ${result.skipped}" else ""}"
    }
    fun saveConfig(config: AppConfig) = run {
        withContext(Dispatchers.IO) { repo.saveAppConfig(config); runCatching { repo.syncNow() } }
        notice = "Configuración actualizada para todos"
    }
    fun saveExcelOptions(includeInternalTransfersInExcel: Boolean) = run {
        withContext(Dispatchers.IO) { repo.saveExcelOptions(includeInternalTransfersInExcel); runCatching { repo.syncNow() } }
        notice = "Excel actualizado"
    }
    fun saveStorageConfig(storage: StorageConfig) = run {
        withContext(Dispatchers.IO) { repo.saveStorageConfig(storage); runCatching { repo.syncNow() } }
        notice = "Almacenamiento actualizado"
    }

    fun saveInfrastructure(config: InfrastructureConfig) = run {
        withContext(Dispatchers.IO) { repo.saveInfrastructureConfig(config); runCatching { repo.syncNow() } }
        val remoteAuto = config.dailyDriveBackupEnabled &&
            config.backupProviders.any { it in setOf(BACKUP_GOOGLE_DRIVE, BACKUP_ONEDRIVE, BACKUP_VPS) }
        BackupScheduler.configure(getApplication<Application>(), remoteAuto, config.dailyBackupHour)
        notice = "Arquitectura y respaldo actualizados"
    }

    fun saveBackupPreferences(
        config: InfrastructureConfig,
        includeFiles: Boolean,
        vpsBaseUrl: String,
        vpsToken: String?
    ) = run {
        withContext(Dispatchers.IO) {
            if (BACKUP_VPS in config.backupProviders) repo.saveExternalBackupOptions(includeFiles, vpsBaseUrl, vpsToken)
            else repo.saveExternalBackupIncludeFiles(includeFiles)
            repo.saveInfrastructureConfig(config)
        }
        val remoteAuto = config.dailyDriveBackupEnabled &&
            config.backupProviders.any { it in setOf(BACKUP_GOOGLE_DRIVE, BACKUP_ONEDRIVE, BACKUP_VPS) }
        BackupScheduler.configure(getApplication<Application>(), remoteAuto, config.dailyBackupHour)
        notice = "Preferencias de respaldo actualizadas"
    }

    fun saveAlternateBackend(config: AlternateBackendConfig, bearerToken: String?) = run {
        withContext(Dispatchers.IO) { repo.saveAlternateBackend(config, bearerToken) }
        notice = "Conector alternativo guardado"
    }

    fun hasBackendApiToken(): Boolean = repo.hasBackendApiToken()
    fun hasBackendApiToken(provider: String): Boolean = repo.hasBackendApiToken(provider)

    fun saveBackendConnection(config: AlternateBackendConfig, bearerToken: String?) = run {
        withContext(Dispatchers.IO) { repo.saveBackendConnection(config, bearerToken) }
        notice = "Conexión guardada"
    }

    fun testBackendConnection(provider: String) = run {
        val message = withContext(Dispatchers.IO) { repo.testBackendConnection(provider) }
        notice = "Conexión correcta · $message"
    }

    fun testAlternateBackend() = run {
        val message = withContext(Dispatchers.IO) { repo.testAlternateBackend() }
        notice = "Conexión correcta · $message"
    }

    fun connectExternalFolder(provider: String, uri: Uri) = run {
        withContext(Dispatchers.IO) { repo.connectExternalFolder(provider, uri) }
        notice = if (provider == BACKUP_GOOGLE_DRIVE) "Google Drive conectado y verificado" else "OneDrive conectado y verificado"
    }

    fun disconnectExternalFolder(provider: String) = run {
        withContext(Dispatchers.IO) { repo.disconnectExternalFolder(provider) }
        notice = "Destino desconectado"
    }

    fun saveExternalBackupOptions(includeFiles: Boolean, vpsBaseUrl: String, vpsToken: String?) = run {
        withContext(Dispatchers.IO) { repo.saveExternalBackupOptions(includeFiles, vpsBaseUrl, vpsToken) }
        notice = "Configuración de respaldos guardada"
    }

    fun hasVpsBackupToken(): Boolean = repo.hasVpsBackupToken()

    fun testExternalBackup(provider: String) = run {
        val message = withContext(Dispatchers.IO) { repo.testExternalBackup(provider) }
        notice = "Conexión correcta · $message"
    }

    fun saveAndTestVpsBackup(includeFiles: Boolean, vpsBaseUrl: String, vpsToken: String?) = run {
        val message = withContext(Dispatchers.IO) {
            repo.saveExternalBackupOptions(includeFiles, vpsBaseUrl, vpsToken)
            repo.testExternalBackup(BACKUP_VPS)
        }
        notice = "VPS guardado y verificado · $message"
    }

    fun createExternalBackup(provider: String) = run {
        val result = withContext(Dispatchers.IO) { repo.createExternalBackup(provider) }
        val label = when (provider) {
            BACKUP_GOOGLE_DRIVE -> "Google Drive"
            BACKUP_ONEDRIVE -> "OneDrive"
            BACKUP_VPS -> "API externa / VPS"
            else -> "destino configurado"
        }
        notice = "Respaldo creado en $label · ${result.entities} registros · ${result.filesExported} archivos${if (result.filesFailed > 0) " · ${result.filesFailed} con error" else ""}"
    }
    fun databaseDiagnostics(): DatabaseDiagnostics = repo.databaseDiagnostics()

    fun scanAdminSpace() = run {
        adminSpaceReport = withContext(Dispatchers.IO) { repo.adminSpaceReport() }
        val report = adminSpaceReport!!
        notice = "Análisis completo · ${report.remoteFiles} archivo(s) remotos · ${report.orphanFiles} huérfano(s)"
    }

    fun deleteOrphanedStorage() = run {
        val result = withContext(Dispatchers.IO) { repo.deleteOrphanedStorage() }
        adminSpaceReport = withContext(Dispatchers.IO) { repo.adminSpaceReport() }
        notice = buildString {
            append("Limpieza de archivos huérfanos completa")
            if (result.filesDeleted > 0) append(" · ${result.filesDeleted} archivo(s)")
            if (result.bytesFreed > 0) append(" · ${humanReadableBytes(result.bytesFreed)}")
            if (result.warnings.isNotEmpty()) append(" · ${result.warnings.size} advertencia(s)")
        }
    }

    fun deleteMessagePermanently(messageId: String) = run {
        val result = withContext(Dispatchers.IO) { repo.backupAndDeleteMessage(messageId) }
        adminSpaceReport = null
        notice = buildString {
            append(if (result.backupFileName.isNotBlank()) "Registro respaldado y eliminado" else "Registro eliminado")
            if (result.filesDeleted > 0) append(" · ${result.filesDeleted} evidencia(s)")
            if (result.bytesFreed > 0) append(" · ${humanReadableBytes(result.bytesFreed)}")
            if (result.warnings.isNotEmpty()) append(" · ${result.warnings.size} advertencia(s)")
        }
    }

    fun cleanupOldRecords(category: String, days: Int) = run {
        val result = withContext(Dispatchers.IO) { repo.cleanupOldRecords(category, days) }
        adminSpaceReport = null
        notice = buildString {
            append("Limpieza terminada · ${result.recordsDeleted} registro(s)")
            if (result.filesDeleted > 0) append(" · ${result.filesDeleted} archivo(s)")
            if (result.bytesFreed > 0) append(" · ${humanReadableBytes(result.bytesFreed)}")
            if (result.backupFileName.isNotBlank()) append(" · respaldo previo creado")
            if (result.warnings.isNotEmpty()) append(" · ${result.warnings.size} advertencia(s)")
        }
    }

    fun purgeTombstones(days: Int) = run {
        val count = withContext(Dispatchers.IO) { repo.purgeTombstones(days) }
        adminSpaceReport = null
        notice = "Papelera técnica depurada · $count registro(s) eliminados"
    }

    fun deleteHistorySelection(transactionIds: Set<String>, messageIds: Set<String>) = run {
        val result = withContext(Dispatchers.IO) { repo.deleteHistorySelection(transactionIds, messageIds) }
        adminSpaceReport = null
        notice = "${result.recordsDeleted} movimiento(s) eliminados${if (result.filesDeleted > 0) " · ${result.filesDeleted} archivo(s)" else ""}${if (result.warnings.isNotEmpty()) " · ${result.warnings.size} advertencia(s)" else ""}"
    }

    private fun humanReadableBytes(value: Long): String = when {
        value >= 1024L * 1024L * 1024L -> "%.2f GB".format(value / (1024.0 * 1024.0 * 1024.0))
        value >= 1024L * 1024L -> "%.2f MB".format(value / (1024.0 * 1024.0))
        value >= 1024L -> "%.1f KB".format(value / 1024.0)
        else -> "$value B"
    }

    fun deleteInvoicePermanently(transactionId: String) = run {
        val result = withContext(Dispatchers.IO) { repo.backupAndDeleteInvoice(transactionId) }
        val mb = result.bytesFreed / (1024.0 * 1024.0)
        notice = buildString {
            append(if (result.backupFileName.isNotBlank()) "Factura respaldada y eliminada de Supabase" else "Factura eliminada de Supabase")
            if (result.backupFileName.isNotBlank()) append(" · ${result.backupFileName}")
            if (result.filesDeleted > 0) append(" · ${result.filesDeleted} evidencia(s) liberadas")
            if (result.bytesFreed > 0) append(" · %.2f MB".format(mb))
            if (result.cleanupWarnings.isNotEmpty()) append(" · ${result.cleanupWarnings.size} archivo(s) no pudieron liberarse del almacenamiento")
            append(" · el movimiento contable se conservó")
        }
    }

    fun editingInvoice(): Transaction? = editingInvoiceId?.let { id -> snapshot.transactions.firstOrNull { it.id == id } }
    fun canEditInvoice(transactionId: String): Boolean = repo.canEditInvoice(transactionId)

    fun startInvoiceEdit(transactionId: String) {
        val tx = snapshot.transactions.firstOrNull { it.id == transactionId } ?: error("La factura ya no existe")
        require(tx.recordStatus == "ACTIVA") { "Solo se pueden modificar facturas activas" }
        require(repo.canEditInvoice(transactionId)) { "Solo puedes editar tus facturas durante 24 horas; luego requiere corrección de ADMIN" }
        editingInvoiceId = transactionId
        invoicePrefill = ""
        screen = Screen.INVOICE
        error = ""
        notice = ""
    }

    fun cancelInvoiceEdit() {
        editingInvoiceId = null
        screen = Screen.HISTORY
    }

    fun updateInvoice(draft: InvoiceDraft, newUris: List<Uri>) = run {
        val id = editingInvoiceId ?: error("No hay una factura en edición")
        withContext(Dispatchers.IO) { repo.updateInvoice(id, draft, newUris) }
        editingInvoiceId = null
        notice = "Cambios guardados y pendientes de sincronizar con Supabase"
        screen = Screen.HISTORY
    }

    fun saveFormSettings(forms: Map<String, List<FormFieldConfig>>, revocationKey: String) = run {
        withContext(Dispatchers.IO) { repo.saveFormSettings(forms, revocationKey); runCatching { repo.syncNow() } }
        // Refresh before notifying the editor so it can reload exactly what SQLite persisted.
        refresh()
        formsSaveRevision++
        notice = "Formatos guardados y sincronizados"
    }

    fun saveInvoice(draft: InvoiceDraft, uris: List<Uri>) = run {
        try {
            val enriched = draft.copy(sourceReminderId = completingReminderId.orEmpty())
            val tx = withContext(Dispatchers.IO) { repo.saveInvoice(enriched, uris) }
            completingReminderId?.let { repo.completeReminder(it, tx.id) }
            completingReminderId = null; invoicePrefill = ""
            notice = "Factura guardada"; screen = Screen.HOME
        } catch (x: DuplicateDetected) {
            duplicate = x; pendingInvoice = draft.copy(sourceReminderId = completingReminderId.orEmpty()) to uris
        }
    }
    fun confirmDuplicate(justification: String) {
        val pending = pendingInvoice ?: return
        duplicate = null; pendingInvoice = null
        run {
            val tx = withContext(Dispatchers.IO) { repo.saveInvoice(pending.first.copy(duplicateJustification = justification), pending.second, true) }
            completingReminderId?.let { repo.completeReminder(it, tx.id) }
            completingReminderId = null; invoicePrefill = ""
            notice = "Factura guardada con revisión de duplicado"; screen = Screen.HOME
        }
    }
    fun dismissDuplicate() { duplicate = null; pendingInvoice = null }

    fun revokeInvoice(transactionId: String, reason: String) = run {
        withContext(Dispatchers.IO) { repo.revokeInvoiceDirect(transactionId, reason) }
        notice = "Factura revocada"
    }

    fun requestInvoiceRevocation(transactionId: String, reason: String) = run {
        withContext(Dispatchers.IO) { repo.requestInvoiceRevocation(transactionId, reason) }
        notice = "Solicitud de revocación enviada a administración"
    }

    fun respondInvoiceRevocation(requestId: String, approve: Boolean, reason: String = "") = run {
        withContext(Dispatchers.IO) { repo.respondInvoiceRevocation(requestId, approve, reason) }
        notice = if (approve) "Factura revocada y saldo corregido" else "Solicitud de revocación rechazada"
    }

    fun startReminderInvoice(id: String, reason: String) { editingInvoiceId = null; completingReminderId = id; invoicePrefill = reason; screen = Screen.INVOICE }
    fun createReminder(reason: String) = run { withContext(Dispatchers.IO) { repo.createReminder(reason) }; notice = "Recordatorio creado" }
    fun postponeReminder(id: String) { repo.postponeReminder(id, snapshot.config.reminderIntervalMinutes); refresh() }
    fun cancelReminder(id: String, reason: String) { repo.cancelReminder(id, reason); refresh() }

    fun requestAmount(recipientId: String, recipientEmail: String, amount: Double, project: String, reason: String, balanceDeclared: Double, uris: List<Uri>, extraFields: Map<String, String> = emptyMap()) = run {
        withContext(Dispatchers.IO) { repo.createAmountRequest(recipientId, recipientEmail, amount, project, reason, balanceDeclared, uris, extraFields).also { runCatching { repo.syncNow() } } }
        notice = "Solicitud enviada"
    }

    fun transfer(recipientId: String, amount: Double, project: String, reason: String, reference: String, transferDate: String, uris: List<Uri>, extraFields: Map<String, String> = emptyMap()) = run {
        withContext(Dispatchers.IO) { repo.createTransfer(recipientId, amount, project, reason, reference, transferDate, uris, extraFields = extraFields).also { runCatching { repo.syncNow() } } }
        notice = "Transferencia enviada"
    }

    fun respondTransfer(id: String, confirm: Boolean, reason: String = "") = run {
        withContext(Dispatchers.IO) { repo.respondTransfer(id, confirm, reason); runCatching { repo.syncNow() } }
        notice = if (confirm) "Transferencia confirmada" else "Transferencia rechazada"
    }

    fun requestSupplier(name: String, ruc: String, email: String) = run {
        val result = withContext(Dispatchers.IO) { repo.requestSupplier(name, ruc, email).also { runCatching { repo.syncNow() } } }
        notice = if (result.messageType == "PROVEEDOR_CREADO") "Proveedor guardado" else "Solicitud enviada a los administradores"
    }

    fun approveSupplierRequest(id: String) = run {
        val supplier = withContext(Dispatchers.IO) { repo.approveSupplierRequest(id).also { runCatching { repo.syncNow() } } }
        notice = "Proveedor aprobado: ${supplier.name}"
    }

    fun rejectSupplierRequest(id: String, reason: String) = run {
        withContext(Dispatchers.IO) { repo.rejectSupplierRequest(id, reason); runCatching { repo.syncNow() } }
        notice = "Solicitud de proveedor rechazada"
    }

    fun editMessage(id: String, amount: Double, reason: String, observations: String = "") = run {
        withContext(Dispatchers.IO) { repo.editMessage(id, amount, reason, observations); runCatching { repo.syncNow() } }
        notice = "Mensaje editado"
    }

    fun deleteConversationMessage(id: String) = run {
        withContext(Dispatchers.IO) { repo.deleteMessageFromConversation(id); runCatching { repo.syncNow() } }
        notice = "Mensaje eliminado"
    }

    fun respondAmountRequest(
        id: String,
        useRequestedAmount: Boolean,
        customAmount: Double = 0.0,
        uris: List<Uri>,
        responseReason: String = ""
    ) = run {
        withContext(Dispatchers.IO) {
            repo.respondAmountRequest(id, useRequestedAmount, customAmount, uris, responseReason)
            runCatching { repo.syncNow() }
        }
        notice = "Solicitud aprobada y transferencia enviada"
    }

    fun adjustment(userId: String, direction: String, amount: Double, reason: String) = run {
        withContext(Dispatchers.IO) { repo.adjustment(userId, direction, amount, reason); runCatching { repo.syncNow() } }; notice = "Corrección creada y sincronizada"
    }
    fun reverseLedger(id: String, reason: String) = run {
        withContext(Dispatchers.IO) { repo.reverseLedger(id, reason) }; notice = "Reverso creado"
    }

    fun visibleTransactions() = repo.visibleTransactions(snapshot)
    fun excelRows() = repo.excelRows(snapshot)
    fun visibleLedger() = repo.visibleLedger(snapshot)
    fun myLedger() = repo.myLedger(snapshot)
    fun visibleMessages() = repo.visibleMessages(snapshot)
    fun visibleReminders() = repo.visibleReminders(snapshot)
    fun visibleAudit() = repo.visibleAudit(snapshot)
    fun storageUsage() = repo.storageUsage(snapshot)

    suspend fun evidenceBytes(attachment: Attachment) = withContext(Dispatchers.IO) { repo.evidenceBytes(attachment) }
    suspend fun evidenceDownload(attachments: List<Attachment>): Pair<String, ByteArray> = withContext(Dispatchers.IO) {
        if (attachments.size == 1) attachments.first().name to repo.evidenceBytes(attachments.first())
        else "evidencias.zip" to FileUtils.zipEvidence(attachments.map { it.name to repo.evidenceBytes(it) })
    }

    fun excelBytes() = ExcelExporter.build(excelRows())
    suspend fun excelDownload(): Pair<String, ByteArray> = withContext(Dispatchers.IO) { repo.invoiceExcelExport() }
    suspend fun invoiceExcelDownload(): Pair<String, ByteArray> = withContext(Dispatchers.IO) { repo.invoiceExcelExport() }
    suspend fun transferExcelDownload(): Pair<String, ByteArray> = withContext(Dispatchers.IO) { repo.transferExcelExport() }

    fun analyzeFormDataFile(uri: Uri) = run {
        val app = getApplication<Application>()
        val workbook = withContext(Dispatchers.IO) {
            val fileName = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }.orEmpty().ifBlank { "Datos_formulario.csv" }
            val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("No se pudo leer el archivo")
            FormDataImporter.parse(bytes, fileName)
        }
        formDataWorkbook = workbook
        notice = "Archivo leído · ${workbook.sheets.size} hoja(s) disponible(s)"
    }

    fun clearFormDataWorkbook() { formDataWorkbook = null }

    fun analyzeExcelTemplate(uri: Uri) = run {
        val app = getApplication<Application>()
        val result = withContext(Dispatchers.IO) {
            val fileName = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }.orEmpty().ifBlank { "Formato_SKC.xlsx" }
            val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("No se pudo leer el archivo")
            bytes to ExcelTemplateEngine.inspect(bytes, fileName)
        }
        pendingExcelTemplateBytes = result.first
        excelInspection = result.second
        notice = "Formato leído. Revisa las referencias antes de activarlo."
    }

    fun saveExcelTemplate(sheetName: String, dataStartRow: Int, mappings: List<ExcelColumnMapping>) = run {
        val bytes = pendingExcelTemplateBytes ?: error("Primero selecciona un archivo Excel")
        val inspection = excelInspection ?: error("Primero analiza el formato Excel")
        val draft = ExcelTemplateConfig(
            enabled = true,
            fileName = inspection.fileName,
            sheetName = sheetName,
            dataStartRow = dataStartRow,
            mappings = mappings
        )
        withContext(Dispatchers.IO) { repo.saveExcelTemplate(bytes, draft) }
        pendingExcelTemplateBytes = null
        excelInspection = null
        notice = "Nuevo formato Excel activado"
    }

    fun cancelExcelTemplateDraft() { pendingExcelTemplateBytes = null; excelInspection = null }
    fun restoreDefaultExcel() = run {
        withContext(Dispatchers.IO) { repo.disableExcelTemplate() }
        pendingExcelTemplateBytes = null
        excelInspection = null
        notice = "Formato Excel predeterminado restaurado"
    }

    fun csvBytes() = CsvExporter.build(visibleTransactions())
    fun exportCatalogs() = repo.exportCatalogs()
    fun importCatalogs(text: String) = run { withContext(Dispatchers.IO) { repo.importCatalogs(text) }; notice = "Catálogos importados" }

    fun exportConfig() = repo.exportConfig()
    fun exportConfig(uri: Uri) = run {
        val resolver = getApplication<Application>().contentResolver
        withContext(Dispatchers.IO) {
            resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(repo.exportConfig()) }
                ?: error("No se pudo crear el archivo JSON")
        }
        notice = "Configuración JSON exportada"
    }
    fun importConfig(text: String) = run {
        withContext(Dispatchers.IO) { repo.importConfig(text) }
        val infra = repo.snapshot().settings.infrastructure
        BackupScheduler.configure(
            getApplication<Application>(),
            infra.dailyDriveBackupEnabled && infra.backupProviders.any { it in setOf(BACKUP_GOOGLE_DRIVE, BACKUP_ONEDRIVE, BACKUP_VPS) },
            infra.dailyBackupHour
        )
        notice = "Configuración importada"
    }
    fun importConfig(uri: Uri) = run {
        val resolver = getApplication<Application>().contentResolver
        val text = withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("No se pudo leer el archivo JSON")
        }
        withContext(Dispatchers.IO) { repo.importConfig(text) }
        val infra = repo.snapshot().settings.infrastructure
        BackupScheduler.configure(
            getApplication<Application>(),
            infra.dailyDriveBackupEnabled && infra.backupProviders.any { it in setOf(BACKUP_GOOGLE_DRIVE, BACKUP_ONEDRIVE, BACKUP_VPS) },
            infra.dailyBackupHour
        )
        notice = "Configuración importada"
    }
    fun exportBackup() = repo.exportBackup()
    fun exportPortable(uri: Uri, includeFiles: Boolean) = run {
        val resolver = getApplication<Application>().contentResolver
        val result = withContext(Dispatchers.IO) {
            resolver.openOutputStream(uri)?.use { repo.exportPortableData(it, includeFiles) }
                ?: error("No se pudo crear el archivo de exportación")
        }
        notice = if (includeFiles)
            "Exportación portable lista · ${result.entities} registros · ${result.filesExported} archivos${if (result.filesFailed > 0) " · ${result.filesFailed} con error" else ""}"
        else
            "Exportación portable de datos lista · ${result.entities} registros"
    }
    fun importBackup(text: String) = run { withContext(Dispatchers.IO) { repo.importBackup(text, true) }; notice = "Respaldo importado" }
    fun resetLocalData() = run { withContext(Dispatchers.IO) { repo.resetLocalData(true) }; notice = "Datos locales reiniciados" }

    private fun startPoller() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                // 3.15: configuración/catálogos deben propagarse prácticamente en tiempo real.
                // Un pull ligero cada 3 s evita que otro dispositivo siga usando un formulario obsoleto.
                delay(3_000L)
                val s = repo.snapshot()
                if (s.session != null && s.settings.supabase.projectUrl.isNotBlank()) {
                    operationMutex.withLock {
                        runCatching { withContext(Dispatchers.IO) { repo.syncNow() } }
                        refresh()
                    }
                }
            }
        }
    }
}

enum class Screen { HOME, INVOICE, HISTORY, MESSAGES, REMINDERS, SETTINGS }
