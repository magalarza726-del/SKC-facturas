package com.skcingenieria.facturas.repository

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.skcingenieria.facturas.data.*
import com.skcingenieria.facturas.network.SupabaseRestClient
import com.skcingenieria.facturas.network.SyncEngine
import com.skcingenieria.facturas.network.StorageTestResult
import com.skcingenieria.facturas.network.ExternalBackupService
import com.skcingenieria.facturas.network.BackendConnectionService
import com.skcingenieria.facturas.network.StorageServiceFactory
import com.skcingenieria.facturas.util.CodeUtils
import com.skcingenieria.facturas.util.ExcelExporter
import com.skcingenieria.facturas.util.ExcelTemplateEngine
import com.skcingenieria.facturas.util.FileUtils
import com.skcingenieria.facturas.util.InvoiceRules
import com.skcingenieria.facturas.util.TimeUtils
import com.skcingenieria.facturas.util.SupplierCsvImporter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

class DuplicateDetected(
    val matches: List<Transaction>,
    val requiresJustification: Boolean = true
) : Exception("Posible factura duplicada")

data class InvoiceDraft(
    val movementType: String = "Gasto",
    val descriptionBase: String = "",
    val project: String = "",
    val description: String = "",
    val area: String = "",
    val liquidationType: String = "",
    val accountUserId: String = "",
    val amount: Double = 0.0,
    val purchaseDate: String = TimeUtils.todayIso(),
    val evidenceStatus: String = "Compra sin comprobante",
    val supplier: String = "",
    val supplierRuc: String = "",
    val supplierEmail: String = "",
    val documentType: String = "FACTURA",
    val invoiceNumber: String = "",
    val costCenter: String = "",
    val secondaryCost: String = "",
    val client: String = "",
    val paymentMethod: String = "",
    val observations: String = "",
    val extraFields: Map<String, String> = emptyMap(),
    val duplicateJustification: String = "",
    val sourceReminderId: String = ""
)

data class BalanceRow(
    val user: UserProfile,
    val balance: Double,
    val syncedBalance: Double,
    val pending: Int
)

data class ReconciliationSummary(
    val transactionsPending: Int,
    val messagesPending: Int,
    val ledgerPending: Int,
    val transfersAwaiting: Int,
    val remindersPending: Int
) {
    val totalPending: Int get() = transactionsPending + messagesPending + ledgerPending
}

data class StorageUsageSummary(
    val totalFiles: Int,
    val totalBytes: Long,
    val supabaseFiles: Int,
    val supabaseBytes: Long,
    val r2Files: Int,
    val r2Bytes: Long,
    val pendingFiles: Int,
    val failedFiles: Int
)

data class AdminSpaceReport(
    val databaseRows: Int,
    val databaseTableBytes: Long,
    val databasePayloadBytes: Long,
    val tombstones: Int,
    val referencedFiles: Int,
    val referencedBytes: Long,
    val remoteFiles: Int,
    val remoteBytes: Long,
    val orphanFiles: Int,
    val orphanBytes: Long,
    val orphanObjects: List<com.skcingenieria.facturas.network.StorageObjectMetadata> = emptyList(),
    val warnings: List<String> = emptyList()
)

data class AdminCleanupResult(
    val recordsDeleted: Int = 0,
    val filesDeleted: Int = 0,
    val bytesFreed: Long = 0,
    val backupFileName: String = "",
    val warnings: List<String> = emptyList()
)

const val CLEANUP_TRANSACTIONS = "transactions"
const val CLEANUP_MESSAGES = "messages"
const val CLEANUP_REMINDERS = "reminders"
const val CLEANUP_AUDIT = "audit"

data class PortableExportResult(
    val entities: Int,
    val filesExported: Int,
    val filesFailed: Int,
    val bytesExported: Long
)

data class InvoiceBackupResult(
    val fileName: String,
    val filesExported: Int,
    val bytesExported: Long
)

data class InvoiceDeleteResult(
    val transactionId: String,
    val filesDeleted: Int,
    val bytesFreed: Long,
    val remoteRecordDeleted: Boolean,
    val backupFileName: String = "",
    val cleanupWarnings: List<String> = emptyList()
)

data class DatabaseDiagnostics(
    val provider: String,
    val projectUrl: String,
    val restEndpoint: String,
    val authEndpoint: String,
    val eventsTable: String,
    val publishableKey: String,
    val localDatabase: String,
    val localEntityTable: String,
    val authenticatedEmail: String,
    val authenticatedUserId: String,
    val autoSync: Boolean,
    val pollSeconds: Int,
    val lastSyncAt: String,
    val storageProvider: String,
    val storageTarget: String,
    val backupApiUrl: String
)

data class AppSnapshot(
    val users: List<UserProfile>,
    val catalogs: Catalogs,
    val config: AppConfig,
    val transactions: List<Transaction>,
    val reminders: List<Reminder>,
    val messages: List<Message>,
    val ledger: List<LedgerEntry>,
    val audit: List<AuditEntry>,
    val settings: LocalSettings,
    val session: AuthSession?
) {
    fun currentUser(): UserProfile? {
        val activeUsers = users.filter { it.active }
        val auth = session
        if (auth != null) {
            val uid = auth.authUserId.trim()
            if (uid.isNotBlank()) {
                activeUsers.firstOrNull { it.authUserId.isNotBlank() && it.authUserId == uid }?.let { return it }
            }
            val mail = auth.email.trim().lowercase()
            if (mail.isNotBlank()) {
                return activeUsers.firstOrNull {
                    it.authUserId.isBlank() && it.email.trim().lowercase() == mail
                }
            }
            return null
        }
        val selected = settings.profileUserId
        return selected.takeIf { it.isNotBlank() }?.let { id -> activeUsers.firstOrNull { it.id == id } }
    }

    fun isAdmin(): Boolean = currentUser()?.role == ROLE_ADMIN
    fun anyAdmin(): Boolean = users.any { it.active && it.role == ROLE_ADMIN }
    fun isIdentityBound(): Boolean {
        val auth = session ?: return false
        val user = currentUser() ?: return false
        val uid = auth.authUserId.trim()
        if (uid.isNotBlank() && user.authUserId.isNotBlank()) return user.authUserId == uid
        return user.authUserId.isBlank() &&
            user.email.trim().equals(auth.email.trim(), ignoreCase = true)
    }

    fun balances(): Map<String, Double> = users.associate { user ->
        val ledgerBalance = ledger
            .filter { it.userId == user.id && it.status == "PUBLICADO" && it.sourceType != "SALDO_INICIAL" }
            .sumOf { it.signedAmount() }
        user.id to (user.initialBalance + ledgerBalance)
    }

    fun balanceRows(): List<BalanceRow> {
        val allBalances = balances()
        return users.map { user ->
            val synced = ledger
                .filter { it.userId == user.id && it.status == "PUBLICADO" && it.syncStatus == SYNCED && it.sourceType != "SALDO_INICIAL" }
                .sumOf { it.signedAmount() } + user.initialBalance
            val pending = transactions.count { it.accountUserId == user.id && it.syncStatus != SYNCED } +
                messages.count { it.recipientUserId == user.id && it.syncStatus != SYNCED }
            BalanceRow(user, allBalances[user.id] ?: 0.0, synced, pending)
        }
    }

    fun reconciliation(): ReconciliationSummary {
        val user = currentUser()
        val tx = if (isAdmin()) transactions else transactions.filter { it.createdByUserId == user?.id || it.accountUserId == user?.id }
        val msg = if (isAdmin()) messages else messages.filter { it.senderUserId == user?.id || it.recipientUserId == user?.id }
        val led = if (isAdmin()) ledger else ledger.filter { it.userId == user?.id }
        val rem = if (isAdmin()) reminders else reminders.filter { it.userId == user?.id }
        return ReconciliationSummary(
            transactionsPending = tx.count { it.syncStatus != SYNCED },
            messagesPending = msg.count { it.syncStatus != SYNCED },
            ledgerPending = led.count { it.syncStatus != SYNCED },
            transfersAwaiting = msg.count { it.messageType == "TRANSFERENCIA" && it.status in listOf("ENVIADO", "RECIBIDO") },
            remindersPending = rem.count { it.status == "PENDIENTE" }
        )
    }

    fun currentUserReconciliation(): ReconciliationSummary {
        val user = currentUser() ?: return ReconciliationSummary(0, 0, 0, 0, 0)
        val tx = transactions.filter { it.createdByUserId == user.id || it.accountUserId == user.id }
        val msg = messages.filter { it.senderUserId == user.id || it.recipientUserId == user.id }
        val led = ledger.filter { it.userId == user.id }
        val rem = reminders.filter { it.userId == user.id }
        return ReconciliationSummary(
            transactionsPending = tx.count { it.syncStatus != SYNCED },
            messagesPending = msg.count { it.syncStatus != SYNCED },
            ledgerPending = led.count { it.syncStatus != SYNCED },
            transfersAwaiting = msg.count { it.messageType == "TRANSFERENCIA" && it.status in listOf("ENVIADO", "RECIBIDO") },
            remindersPending = rem.count { it.status == "PENDIENTE" }
        )
    }
}

class AppRepository(private val context: Context) {
    private val db = LocalDatabase(context)
    private val state = LocalStateStore(db)
    private val supabase = SupabaseRestClient()
    private val sync = SyncEngine(db, state, supabase, context.applicationContext)
    private val externalBackup = ExternalBackupService(context)
    private val backendConnection = BackendConnectionService()

    init { initialize() }

    private fun initialize() {
        val now = TimeUtils.nowIso()
        db.delete(TYPE_USERS, "usuario-demo")
        db.delete(TYPE_USERS, "user-demo")
        if (db.all(TYPE_USERS).isEmpty()) {
            SeedData.users().forEach { db.put(TYPE_USERS, it.id, it.toJson(), SYNC_PENDING, now) }
        } else {

            val adminSeed = SeedData.users().first { it.id == "administracion" }
            if (db.get(TYPE_USERS, adminSeed.id) == null &&
                db.all(TYPE_USERS).none { row ->
                    UserProfile.fromJson(row.payload).email.equals(adminSeed.email, ignoreCase = true)
                }
            ) {
                db.put(TYPE_USERS, adminSeed.id, adminSeed.toJson(), SYNC_PENDING, now)
            }
        }
        migrateKnownUserEmails()
        dedupeUsers()
        if (db.get(TYPE_CATALOGS, "global") == null) {
            officialExcelCatalogs().let { db.put(TYPE_CATALOGS, it.id, it.toJson(), SYNC_PENDING, it.updatedAt) }
            // Fresh installs already received the full bundled directory through officialExcelCatalogs().
            // Mark it immediately so future launches never resurrect suppliers deleted by an ADMIN.
            state.markBundledSuppliersSeeded()
        }
        migrateOfficialExcelCatalogs()
        migrateBundledSuppliers()
        if (db.get(TYPE_APP_CONFIG, "global") == null) {
            SeedData.appConfig().let { db.put(TYPE_APP_CONFIG, it.id, it.toJson(), SYNC_PENDING, it.updatedAt) }
        }
        migrateOfficialExcelForm()
        val settings = state.settings()
        val existingSession = state.session()
        if (existingSession == null) {

            if (settings.profileUserId.isNotBlank()) state.saveSettings(settings.copy(profileUserId = ""))
        } else {

            bindBySession(existingSession)
        }
    }


    private fun officialExcelCatalogs(): Catalogs {
        val baseSeed = SeedData.catalogs()
        val seed = baseSeed.copy(suppliers = mergeSuppliers(baseSeed.suppliers, bundledSuppliers()))
        val extracted = defaultExcelTemplate()?.let { bytes ->
            runCatching { ExcelTemplateEngine.extractCatalogs(bytes) }.getOrNull()
        }
        if (extracted == null || !extracted.isUsable) return seed
        val tree = extracted.secondaryCostsByCenter
        return seed.copy(
            costCenters = extracted.costCenters,
            secondaryCostsByCenter = tree,
            secondaryCosts = tree.values.flatten().distinct(),
            clients = extracted.clients,
            schemaVersion = DATA_SCHEMA_VERSION
        ).normalizedProjectHierarchy()
    }

    private fun bundledSuppliers(): List<SupplierDefinition> = runCatching {
        context.assets.open("providers_skc_4265.json").bufferedReader(Charsets.UTF_8).use { reader ->
            val array = JSONArray(reader.readText())
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(SupplierDefinition::fromJson)
            }.filter { it.name.isNotBlank() }
        }
    }.getOrDefault(emptyList())

    private fun mergeSuppliers(
        current: List<SupplierDefinition>,
        bundled: List<SupplierDefinition>
    ): List<SupplierDefinition> {
        if (bundled.isEmpty()) return current
        val result = current.toMutableList()
        val byRuc = mutableMapOf<String, Int>()
        val byName = mutableMapOf<String, Int>()

        fun indexSupplier(index: Int, supplier: SupplierDefinition) {
            val ruc = supplier.ruc.filter(Char::isDigit)
            if (ruc.isNotBlank()) byRuc.putIfAbsent(ruc, index)
            val name = normalizedCatalogKey(supplier.name)
            if (name.isNotBlank()) byName.putIfAbsent(name, index)
        }

        result.forEachIndexed(::indexSupplier)
        bundled.forEach { supplier ->
            val ruc = supplier.ruc.filter(Char::isDigit)
            val name = normalizedCatalogKey(supplier.name)
            val existingIndex = (if (ruc.isNotBlank()) byRuc[ruc] else null) ?: byName[name]
            if (existingIndex == null) {
                val index = result.size
                result += supplier
                indexSupplier(index, supplier)
            } else {
                val previous = result[existingIndex]
                val merged = previous.copy(
                    name = previous.name.ifBlank { supplier.name },
                    ruc = previous.ruc.ifBlank { supplier.ruc },
                    email = previous.email.ifBlank { supplier.email }
                )
                result[existingIndex] = merged
                indexSupplier(existingIndex, merged)
            }
        }
        return result
    }

    private fun migrateBundledSuppliers() {
        // 3.13.3: the XLS directory is a one-time seed, not a permanent source of truth.
        // After the initial import, Catalogs.suppliers is fully ADMIN-managed. This is what makes
        // add/edit/delete persistent across app restarts and configuration imports.
        if (state.bundledSuppliersSeedVersion() >= 1) return

        val row = db.get(TYPE_CATALOGS, "global") ?: return
        val bundled = bundledSuppliers()
        if (bundled.isEmpty()) return
        val current = Catalogs.fromJson(row.payload)

        // Upgrade compatibility for 3.13.1/3.13.2: those builds already inserted the 1,019
        // bundled suppliers but did not persist a migration marker. Detect an already-seeded
        // directory and only write the marker, preserving any suppliers the ADMIN deleted.
        val currentRucs = current.suppliers.map { it.ruc.filter(Char::isDigit) }.filter(String::isNotBlank).toSet()
        val currentNames = current.suppliers.map { normalizedCatalogKey(it.name) }.filter(String::isNotBlank).toSet()
        val bundledMatches = bundled.count { supplier ->
            val ruc = supplier.ruc.filter(Char::isDigit)
            (ruc.isNotBlank() && ruc in currentRucs) || normalizedCatalogKey(supplier.name) in currentNames
        }
        if (bundledMatches >= minOf(50, bundled.size)) {
            state.markBundledSuppliersSeeded()
            return
        }

        val suppliers = mergeSuppliers(current.suppliers, bundled)
        if (suppliers != current.suppliers) {
            val now = TimeUtils.nowIso()
            val updated = current.copy(
                suppliers = suppliers,
                seeded = false,
                updatedAt = now,
                syncStatus = SYNC_PENDING,
                syncError = ""
            ).normalizedProjectHierarchy()
            db.put(TYPE_CATALOGS, updated.id, updated.toJson(), SYNC_PENDING, now)
        }
        state.markBundledSuppliersSeeded()
    }

    private fun normalizedCatalogKey(value: String): String = java.text.Normalizer
        .normalize(value.trim().uppercase(), java.text.Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .replace("[^A-Z0-9]".toRegex(), "")

    private fun mergeCatalogValues(primary: List<String>, secondary: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return (primary + secondary).map(String::trim).filter(String::isNotBlank).filter { seen.add(normalizedCatalogKey(it)) }
    }

    private fun migrateOfficialExcelCatalogs(force: Boolean = false) {
        val row = db.get(TYPE_CATALOGS, "global") ?: return
        val current = Catalogs.fromJson(row.payload)
        if (!force && current.schemaVersion >= DATA_SCHEMA_VERSION) return
        val official = officialExcelCatalogs()
        val replaceLegacySeed = current.seeded && current.schemaVersion == 0

        val centers: List<String>
        val tree: Map<String, List<String>>
        val clients: List<String>
        if (replaceLegacySeed) {
            centers = official.costCenters
            tree = official.secondaryCostsByCenter
            clients = official.clients
        } else {
            val mergedTree = linkedMapOf<String, List<String>>()
            official.costCenters.forEach { officialCenter ->
                val oldCenter = current.costCenters.firstOrNull {
                    normalizedCatalogKey(it) == normalizedCatalogKey(officialCenter)
                }
                mergedTree[officialCenter] = mergeCatalogValues(
                    official.secondaryCostsByCenter[officialCenter].orEmpty(),
                    oldCenter?.let { current.secondaryCostsByCenter[it] }.orEmpty()
                )
            }
            current.costCenters.forEach { currentCenter ->
                if (mergedTree.keys.none { normalizedCatalogKey(it) == normalizedCatalogKey(currentCenter) }) {
                    mergedTree[currentCenter] = current.secondaryCostsByCenter[currentCenter].orEmpty()
                }
            }
            centers = mergedTree.keys.toList()
            tree = mergedTree
            clients = mergeCatalogValues(official.clients, current.clients)
        }

        val transactionsFromHistory = db.all(TYPE_TRANSACTIONS).mapNotNull { row ->
            runCatching { Transaction.fromJson(row.payload) }.getOrNull()
        }
        val clientsFromHistory = transactionsFromHistory
        val suppliersFromHistory = transactionsFromHistory
            .filter { it.supplier.isNotBlank() }
            .map { tx ->
                SupplierDefinition(
                    id = "supplier-${CodeUtils.slug(tx.supplier)}-${CodeUtils.sha256Text(tx.supplier.trim().lowercase()).take(8)}",
                    name = tx.supplier.trim(),
                    ruc = tx.supplierRuc.trim(),
                    email = tx.supplierEmail.trim()
                )
            }
        val supplierMap = linkedMapOf<String, SupplierDefinition>()
        (current.suppliers + suppliersFromHistory).forEach { supplier ->
            val key = normalizedCatalogKey(supplier.name)
            if (key.isNotBlank()) {
                val previous = supplierMap[key]
                supplierMap[key] = if (previous == null) supplier else previous.copy(
                    ruc = previous.ruc.ifBlank { supplier.ruc },
                    email = previous.email.ifBlank { supplier.email }
                )
            }
        }
        val projects = current.projects.map { project ->
            val historyClients = clientsFromHistory.filter { tx ->
                tx.client.isNotBlank() &&
                    (tx.descriptionBase == project.description || tx.descriptionBase == project.id)
            }.map { it.client }
            project.copy(
                clients = mergeCatalogValues(project.allClients(), historyClients),
                clientsByProject2 = emptyMap()
            )
        }

        val now = TimeUtils.nowIso()
        val updated = current.copy(
            projects = projects,
            costCenters = centers,
            secondaryCosts = tree.values.flatten().let { mergeCatalogValues(it, emptyList()) },
            secondaryCostsByCenter = tree,
            clients = clients,
            suppliers = supplierMap.values.toList(),
            clientDirectory = current.clientDirectory,
            schemaVersion = DATA_SCHEMA_VERSION,
            seeded = false,
            updatedAt = now,
            syncStatus = SYNC_PENDING,
            syncError = ""
        ).normalizedProjectHierarchy()
        db.put(TYPE_CATALOGS, updated.id, updated.toJson(), SYNC_PENDING, now)
    }

    private fun migrateOfficialExcelForm() {
        val row = db.get(TYPE_APP_CONFIG, "global") ?: return
        val current = AppConfig.fromJson(row.payload)
        if (current.formsSchemaVersion >= FORM_SCHEMA_VERSION) return

        // 3.13.2: provider is a linked searchable field. For installs already migrated to
        // the compact 3.13 baseline, change only this built-in field and preserve every
        // other ADMIN customization exactly as saved.
        if (current.formsSchemaVersion >= 500) {
            val now = TimeUtils.nowIso()
            val existingInvoice = current.fields("invoice").map { field ->
                val supplierField = field.key == "supplier" || (field.core && field.label.contains("proveedor", ignoreCase = true))
                when {
                    supplierField -> field.copy(key = "supplier", fieldType = FIELD_LINKED_SELECT, options = emptyList())
                    field.key == "movementType" -> field.copy(
                        label = "¿Es ingreso o un gasto?", visible = true, required = true, defaultValue = "Gasto", fieldType = FIELD_SELECT,
                        options = listOf(FormOptionConfig("Gasto", "Gasto"), FormOptionConfig("Ingreso", "Ingreso"))
                    )
                    else -> field
                }
            }.toMutableList()
            // 3.15: add only the new built-in fields that are missing. ADMIN custom fields/order/visibility remain intact.
            val defaultsByKey = defaultFormLayouts()["invoice"].orEmpty().associateBy { it.key }
            listOf("movementType", "project", "observations").forEach { key ->
                if (existingInvoice.none { it.key == key }) defaultsByKey[key]?.let(existingInvoice::add)
            }
            val invoice = existingInvoice.mapIndexed { index, field -> field.copy(order = index) }
            val updated = current.withFields("invoice", invoice).copy(
                schemaVersion = maxOf(current.schemaVersion, DATA_SCHEMA_VERSION),
                formsSchemaVersion = FORM_SCHEMA_VERSION,
                formsUpdatedAt = now,
                seeded = false,
                updatedAt = now,
                syncStatus = SYNC_PENDING,
                syncError = ""
            )
            db.put(TYPE_APP_CONFIG, updated.id, updated.toJson(), SYNC_PENDING, now)
            return
        }

        // Schema upgrades must never restore the whole form to application defaults. Doing so
        // made a valid ADMIN customization disappear after a pull/restart whenever the stored
        // schema was older than the app. Preserve every saved choice and only add genuinely new
        // fields / apply migrations that are explicitly required by the new version.
        var updated = current
        val legacyBuiltInKeys = setOf(
            "recipientUserId", "recipientEmail", "amount", "reason", "attachments", "project", "balanceDeclared",
            "reference", "transferDate", "movementType", "accountUserId", "purchaseDate", "descriptionBase",
            "description", "area", "liquidationType", "evidenceStatus", "supplier", "supplierRuc", "supplierEmail",
            "documentType", "invoiceNumber", "costCenter", "secondaryCost", "client", "paymentMethod", "observations"
        )
        defaultFormLayouts().forEach { (module, defaultsList) ->
            val currentFields = current.fields(module)
            val defaultKeys = defaultsList.mapTo(mutableSetOf()) { it.key }
            val merged = buildList {
                defaultsList.forEach { modern ->
                    // 3.13 establishes a compact baseline for the built-in fields. Custom fields are
                    // preserved below, but legacy defaults no longer re-introduce old complexity.
                    add(modern)
                }
                // Preserve genuinely custom data fields, but discard the legacy built-in clutter removed
                // from the new minimal forms.
                currentFields.filter { field ->
                    field.key.isNotBlank() && field.key !in defaultKeys && field.key !in legacyBuiltInKeys
                }.forEach { add(it.copy(core = false)) }
            }
            updated = updated.withFields(module, merged.sortedBy { it.order }.mapIndexed { i, field -> field.copy(order = i) })
        }
        val now = TimeUtils.nowIso()
        updated = updated.copy(
            schemaVersion = maxOf(current.schemaVersion, DATA_SCHEMA_VERSION),
            formsSchemaVersion = FORM_SCHEMA_VERSION,
            formsUpdatedAt = now,
            seeded = false,
            updatedAt = now,
            syncStatus = SYNC_PENDING,
            syncError = ""
        )
        db.put(TYPE_APP_CONFIG, updated.id, updated.toJson(), SYNC_PENDING, now)
    }

    fun reloadOfficialExcelCatalogs() {
        require(snapshot().isAdmin() || !snapshot().anyAdmin()) { "Solo ADMIN" }
        migrateOfficialExcelCatalogs(force = true)
        audit("CATALOGOS", "global", "SINCRONIZAR_EXCEL_OFICIAL", "Catálogos sincronizados desde C. Costos")
    }

    private fun migrateKnownUserEmails() {
        val known = mapOf(
            "administracion" to "administracion@gmail.skc",
            "dalton" to "dalton@gmail.skc",
            "evelyn" to "evelyn@gmail.skc",
            "javier" to "javier@gmail.skc",
            "karen" to "karen@gmail.skc",
            "tito" to "tito@gmail.skc"
        )
        val now = TimeUtils.nowIso()
        known.forEach { (id, email) ->
            val row = db.get(TYPE_USERS, id) ?: return@forEach
            val user = UserProfile.fromJson(row.payload)
            val needsEmail = user.email.isBlank()
            val needsDaltonAdmin = id == "dalton" && user.seeded && user.role != ROLE_ADMIN
            val needsAdministrationTransfers = id == "administracion" && !user.transferEnabled
            if (needsEmail || needsDaltonAdmin || needsAdministrationTransfers) {
                val updated = user.copy(
                    email = if (needsEmail) email else user.email,
                    role = if (needsDaltonAdmin) ROLE_ADMIN else user.role,
                    transferEnabled = if (id == "administracion") true else user.transferEnabled,
                    updatedAt = now,
                    syncStatus = SYNC_PENDING,
                    syncError = ""
                )
                db.put(TYPE_USERS, id, updated.toJson(), SYNC_PENDING, now)
            }
        }
    }

    private fun dedupeUsers() {
        val users = db.all(TYPE_USERS).map { UserProfile.fromJson(it.payload) }
        val remaining = users.toMutableList()
        val groups = mutableListOf<List<UserProfile>>()
        while (remaining.isNotEmpty()) {
            val component = mutableListOf(remaining.removeAt(0))
            var changed = true
            while (changed) {
                changed = false
                val names = component.map { CodeUtils.normalize(it.name) }.filter { it.isNotBlank() }.toSet()
                val emails = component.map { it.email.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
                val authIds = component.map { it.authUserId.trim() }.filter { it.isNotBlank() }.toSet()
                val matches = remaining.filter { candidate ->
                    CodeUtils.normalize(candidate.name) in names ||
                        (candidate.email.trim().lowercase().isNotBlank() && candidate.email.trim().lowercase() in emails) ||
                        (candidate.authUserId.isNotBlank() && candidate.authUserId in authIds)
                }
                if (matches.isNotEmpty()) { component.addAll(matches); remaining.removeAll(matches); changed = true }
            }
            if (component.size > 1) groups += component
        }
        if (groups.isEmpty()) return
        var settings = state.settings()
        val referenceFields = listOf("accountUserId", "createdByUserId", "userId", "senderUserId", "recipientUserId", "confirmedByUserId", "actorUserId")
        for (group in groups) {
            val primary = group.firstOrNull { it.role == ROLE_ADMIN }
                ?: group.firstOrNull { it.email.isNotBlank() }
                ?: group.first()
            for (duplicate in group) {
                if (duplicate.id == primary.id) continue
                for (type in listOf(TYPE_TRANSACTIONS, TYPE_REMINDERS, TYPE_MESSAGES, TYPE_LEDGER, TYPE_AUDIT)) {
                    db.all(type).forEach { row ->
                        val payload = JSONObject(row.payload.toString())
                        var changed = false
                        referenceFields.forEach { field ->
                            if (payload.optString(field) == duplicate.id) {
                                payload.put(field, primary.id)
                                changed = true
                            }
                        }
                        if (changed) {
                            if (payload.optString("accountUserId") == primary.id) payload.put("accountUsername", primary.name).put("username", primary.name)
                            if (payload.optString("userId") == primary.id) payload.put("username", primary.name)
                            if (payload.optString("senderUserId") == primary.id) payload.put("senderName", primary.name)
                            if (payload.optString("recipientUserId") == primary.id) payload.put("recipientName", primary.name)
                            if (payload.optString("actorUserId") == primary.id) payload.put("actorName", primary.name)
                            db.put(type, row.id, payload, row.syncStatus, row.updatedAt)
                        }
                    }
                }
                db.delete(TYPE_USERS, duplicate.id)
                db.delete(TYPE_LEDGER, "initial-${duplicate.id}")
                if (settings.profileUserId == duplicate.id) settings = settings.copy(profileUserId = primary.id)
            }
            val merged = primary.copy(
                email = primary.email.ifBlank { group.firstOrNull { it.email.isNotBlank() }?.email.orEmpty() },
                authUserId = primary.authUserId.ifBlank { group.firstOrNull { it.authUserId.isNotBlank() }?.authUserId.orEmpty() },
                role = if (group.any { it.role == ROLE_ADMIN }) ROLE_ADMIN else ROLE_USER,
                transferEnabled = group.any { it.transferEnabled },
                active = group.any { it.active },
                updatedAt = TimeUtils.nowIso()
            )
            db.put(TYPE_USERS, merged.id, merged.toJson(), merged.syncStatus, merged.updatedAt)
        }
        state.saveSettings(settings)
    }

    fun snapshot(): AppSnapshot = AppSnapshot(
        users = db.all(TYPE_USERS).map { UserProfile.fromJson(it.payload) }.sortedBy { it.name },
        catalogs = db.get(TYPE_CATALOGS, "global")?.payload?.let(Catalogs::fromJson) ?: SeedData.catalogs(),
        config = db.get(TYPE_APP_CONFIG, "global")?.payload?.let(AppConfig::fromJson) ?: SeedData.appConfig(),
        transactions = db.all(TYPE_TRANSACTIONS).map { Transaction.fromJson(it.payload) },
        reminders = db.all(TYPE_REMINDERS).map { Reminder.fromJson(it.payload) },
        messages = db.all(TYPE_MESSAGES).map { Message.fromJson(it.payload) },
        ledger = db.all(TYPE_LEDGER).map { LedgerEntry.fromJson(it.payload) },
        audit = db.all(TYPE_AUDIT).map { AuditEntry.fromJson(it.payload) },
        settings = state.settings(),
        session = state.session()
    )

    fun setProfile(userId: String) {
        val snap = snapshot()
        val user = snap.users.firstOrNull { it.id == userId && it.active } ?: error("Usuario no disponible")
        val auth = state.session()
        if (auth != null && state.settings().supabase.lockUserToEmail) {
            require(
                (user.authUserId.isNotBlank() && user.authUserId == auth.authUserId) ||
                    (user.authUserId.isBlank() && user.email.equals(auth.email, ignoreCase = true))
            ) { "La identidad se determina por la cuenta autenticada de Supabase" }
        }
        state.saveSettings(state.settings().copy(profileUserId = userId))
    }

    fun initialSetupOpen(): Boolean = state.initialSetupOpen()

    fun saveSupabase(
        projectUrl: String,
        key: String,
        pollSeconds: Int = 20,
        autoSync: Boolean = true,
        allowSelfSignUp: Boolean = false,
        lockUserToEmail: Boolean = true
    ) {
        val clean = projectUrl.trim().trimEnd('/').removeSuffix("/rest/v1")
        require(clean.startsWith("https://") && key.trim().isNotBlank()) { "URL o Publishable key de Supabase inválida" }
        require(!key.trim().startsWith("sb_secret_", ignoreCase = true)) { "No use una Secret key. Use la Publishable key." }
        val s = state.settings()
        val old = s.supabase.projectUrl.trimEnd('/')
        val changed = old.isNotBlank() && old != clean
        state.saveSettings(
            s.copy(
                supabase = s.supabase.copy(
                    projectUrl = clean,
                    publishableKey = key.trim(),
                    pollSeconds = pollSeconds.coerceIn(10, 3600),
                    autoSync = autoSync,
                    allowSelfSignUp = allowSelfSignUp,
                    lockUserToEmail = lockUserToEmail,
                    lastBackendFingerprint = if (changed) "" else s.supabase.lastBackendFingerprint
                )
            )
        )
        if (changed) {
            state.saveSession(null)
            state.saveSettings(state.settings().copy(profileUserId = ""))
        }
    }

    fun saveNotifications(enabled: Boolean) {
        state.saveSettings(state.settings().copy(notificationsEnabled = enabled))
    }

    fun saveInfrastructureConfig(config: InfrastructureConfig) {
        val current = state.settings()
        val allowedData = setOf(
            DATA_BACKEND_SUPABASE, DATA_BACKEND_VPS_API, DATA_BACKEND_POSTGRES_API,
            DATA_BACKEND_MYSQL_API, DATA_BACKEND_MARIADB_API, DATA_BACKEND_SQLSERVER_API,
            DATA_BACKEND_GENERIC_REST
        )
        val allowedBackup = setOf(BACKUP_GOOGLE_DRIVE, BACKUP_ONEDRIVE, BACKUP_VPS, BACKUP_LOCAL_ZIP)
        val primaries = config.primaryDataProviders.filter { it in allowedData }.distinct().ifEmpty { listOf(DATA_BACKEND_SUPABASE) }
        val backups = config.backupProviders.filter { it in allowedBackup }.distinct()
        val clean = config.copy(
            primaryDataProvider = primaries.first(),
            secondaryDataProvider = primaries.firstOrNull { it != DATA_BACKEND_SUPABASE }.orEmpty(),
            preferredBackupProvider = backups.firstOrNull() ?: BACKUP_NONE,
            primaryDataProviders = primaries,
            backupProviders = backups,
            dailyDriveBackupEnabled = config.dailyDriveBackupEnabled && backups.any { it in setOf(BACKUP_GOOGLE_DRIVE, BACKUP_ONEDRIVE, BACKUP_VPS) },
            dailyBackupHour = config.dailyBackupHour.coerceIn(0, 23)
        )
        state.saveSettings(current.copy(infrastructure = clean))
    }

    fun saveBackendConnection(config: AlternateBackendConfig, bearerToken: String?) {
        require(snapshot().isAdmin()) { "Solo ADMIN" }
        val allowedProviders = setOf(
            DATA_BACKEND_VPS_API, DATA_BACKEND_POSTGRES_API, DATA_BACKEND_MYSQL_API,
            DATA_BACKEND_MARIADB_API, DATA_BACKEND_SQLSERVER_API, DATA_BACKEND_GENERIC_REST
        )
        require(config.provider in allowedProviders) { "Tipo de conector no compatible" }
        val cleanUrl = config.apiBaseUrl.trim().trimEnd('/')
        if (cleanUrl.isNotBlank()) require(cleanUrl.startsWith("https://")) { "El conector debe usar HTTPS" }
        val health = config.healthPath.trim().ifBlank { "/health" }.let { if (it.startsWith('/')) it else "/$it" }
        val clean = config.copy(apiBaseUrl = cleanUrl, healthPath = health, databaseName = config.databaseName.trim())
        val current = state.settings()
        val previous = current.backendConnections.firstOrNull { it.provider == clean.provider }
        val updated = current.backendConnections.filterNot { it.provider == clean.provider } + clean
        state.saveSettings(current.copy(
            alternateBackend = if (current.alternateBackend.provider == clean.provider || current.backendConnections.isEmpty()) clean else current.alternateBackend,
            backendConnections = updated.sortedBy { it.provider }
        ))
        when {
            bearerToken != null -> state.saveBackendApiToken(clean.provider, bearerToken)
            cleanUrl != previous?.apiBaseUrl.orEmpty().trimEnd('/') -> state.saveBackendApiToken(clean.provider, "")
        }
    }

    fun hasBackendApiToken(provider: String): Boolean = state.backendApiToken(provider).isNotBlank()

    fun testBackendConnection(provider: String): String {
        val snap = snapshot()
        require(snap.isAdmin()) { "Solo ADMIN" }
        val cfg = snap.settings.backendConnections.firstOrNull { it.provider == provider }
            ?: if (snap.settings.alternateBackend.provider == provider) snap.settings.alternateBackend else error("Conector no configurado")
        val message = backendConnection.test(cfg.apiBaseUrl, cfg.healthPath, state.backendApiToken(provider))
        val now = TimeUtils.nowIso()
        val latest = state.settings()
        val updated = latest.backendConnections.map { if (it.provider == provider) it.copy(lastTestAt = now, lastTestMessage = message) else it }
        state.saveSettings(latest.copy(
            backendConnections = updated,
            alternateBackend = if (latest.alternateBackend.provider == provider) latest.alternateBackend.copy(lastTestAt = now, lastTestMessage = message) else latest.alternateBackend
        ))
        return message
    }

    fun saveAlternateBackend(config: AlternateBackendConfig, bearerToken: String?) = saveBackendConnection(config, bearerToken)

    fun hasBackendApiToken(): Boolean = state.settings().backendConnections.any { state.backendApiToken(it.provider).isNotBlank() } || state.backendApiToken().isNotBlank()

    fun testAlternateBackend(): String {
        val cfg = state.settings().alternateBackend
        return testBackendConnection(cfg.provider)
    }

    fun connectExternalFolder(provider: String, uri: Uri) {
        require(snapshot().isAdmin()) { "Solo ADMIN" }
        externalBackup.persistTreePermission(uri)
        try {
            // Validate write access immediately. A successful selection is therefore already a
            // real connection test and the UI does not need a second redundant “Probar” button.
            externalBackup.testTree(uri.toString())
            val settings = state.settings()
            val current = settings.externalBackup
            val updated = when (provider) {
                BACKUP_GOOGLE_DRIVE -> current.copy(googleDriveTreeUri = uri.toString())
                BACKUP_ONEDRIVE -> current.copy(oneDriveTreeUri = uri.toString())
                else -> error("Proveedor de respaldo no válido")
            }
            state.saveSettings(settings.copy(externalBackup = updated))
        } catch (e: Exception) {
            externalBackup.releaseTreePermission(uri.toString())
            throw e
        }
    }

    fun disconnectExternalFolder(provider: String) {
        require(snapshot().isAdmin()) { "Solo ADMIN" }
        val settings = state.settings()
        val current = settings.externalBackup
        val oldUri = when (provider) {
            BACKUP_GOOGLE_DRIVE -> current.googleDriveTreeUri
            BACKUP_ONEDRIVE -> current.oneDriveTreeUri
            else -> error("Proveedor de respaldo no válido")
        }
        externalBackup.releaseTreePermission(oldUri)
        val updated = when (provider) {
            BACKUP_GOOGLE_DRIVE -> current.copy(googleDriveTreeUri = "")
            BACKUP_ONEDRIVE -> current.copy(oneDriveTreeUri = "")
            else -> error("Proveedor de respaldo no válido")
        }
        state.saveSettings(settings.copy(externalBackup = updated))
    }

    fun saveExternalBackupOptions(includeFiles: Boolean, vpsBaseUrl: String, vpsToken: String?) {
        require(snapshot().isAdmin()) { "Solo ADMIN" }
        val cleanUrl = vpsBaseUrl.trim().trimEnd('/')
        if (cleanUrl.isNotBlank()) {
            require(cleanUrl.startsWith("https://")) { "La URL de una futura API/VPS debe usar https://" }
        }
        val settings = state.settings()
        val previousUrl = settings.externalBackup.vpsBaseUrl.trim().trimEnd('/')
        state.saveSettings(settings.copy(externalBackup = settings.externalBackup.copy(includeFiles = includeFiles, vpsBaseUrl = cleanUrl)))
        when {
            vpsToken != null -> state.saveVpsBackupToken(vpsToken)
            cleanUrl != previousUrl -> state.saveVpsBackupToken("")
        }
    }

    fun saveExternalBackupIncludeFiles(includeFiles: Boolean) {
        require(snapshot().isAdmin()) { "Solo ADMIN" }
        val settings = state.settings()
        state.saveSettings(settings.copy(externalBackup = settings.externalBackup.copy(includeFiles = includeFiles)))
    }

    fun hasVpsBackupToken(): Boolean = state.vpsBackupToken().isNotBlank()

    fun testExternalBackup(provider: String): String {
        val cfg = state.settings().externalBackup
        return when (provider) {
            BACKUP_GOOGLE_DRIVE -> externalBackup.testTree(cfg.googleDriveTreeUri)
            BACKUP_ONEDRIVE -> externalBackup.testTree(cfg.oneDriveTreeUri)
            BACKUP_VPS -> externalBackup.testVps(cfg.vpsBaseUrl, state.vpsBackupToken())
            else -> error("Proveedor de respaldo no válido")
        }
    }

    fun createExternalBackup(provider: String): PortableExportResult {
        val snap = snapshot()
        require(snap.isAdmin()) { "Solo ADMIN puede crear respaldos externos" }
        val cfg = state.settings().externalBackup
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))
        val fileName = "SKC_Facturas_$timestamp.zip"
        val result = when (provider) {
            BACKUP_GOOGLE_DRIVE -> externalBackup.createTreeBackup(cfg.googleDriveTreeUri, fileName) { out -> exportPortableData(out, cfg.includeFiles) }
            BACKUP_ONEDRIVE -> externalBackup.createTreeBackup(cfg.oneDriveTreeUri, fileName) { out -> exportPortableData(out, cfg.includeFiles) }
            BACKUP_VPS -> externalBackup.uploadVpsBackup(cfg.vpsBaseUrl, state.vpsBackupToken(), fileName) { out -> exportPortableData(out, cfg.includeFiles) }
            else -> error("Proveedor de respaldo no válido")
        }
        val now = TimeUtils.nowIso()
        val latest = state.settings()
        state.saveSettings(latest.copy(externalBackup = latest.externalBackup.copy(lastBackupAt = now, lastBackupTarget = provider)))
        audit("RESPALDO", provider, "EXPORTAR", "$fileName · ${result.entities} registros · ${result.filesExported} archivos")
        return result
    }

    fun createAutomaticBackup(): PortableExportResult {
        val settings = state.settings()
        val cfg = settings.externalBackup
        require(settings.infrastructure.dailyDriveBackupEnabled) { "El respaldo automático está desactivado" }
        val providers = settings.infrastructure.backupProviders.filter { it in setOf(BACKUP_GOOGLE_DRIVE, BACKUP_ONEDRIVE, BACKUP_VPS) }
        require(providers.isNotEmpty()) { "No hay destinos automáticos configurados" }
        val today = LocalDate.now().toString()
        if (cfg.lastAutomaticBackupAt.startsWith(today)) return PortableExportResult(0, 0, 0, 0)

        var entities = 0
        var exported = 0
        var failed = 0
        var bytes = 0L
        providers.forEach { provider ->
            val fileName = "SKC_Facturas_Diario_${today}.zip"
            val result = when (provider) {
                BACKUP_GOOGLE_DRIVE -> externalBackup.createTreeBackup(cfg.googleDriveTreeUri, fileName) { out -> exportPortableData(out, cfg.includeFiles) }
                BACKUP_ONEDRIVE -> externalBackup.createTreeBackup(cfg.oneDriveTreeUri, fileName) { out -> exportPortableData(out, cfg.includeFiles) }
                BACKUP_VPS -> externalBackup.uploadVpsBackup(cfg.vpsBaseUrl, state.vpsBackupToken(), fileName) { out -> exportPortableData(out, cfg.includeFiles) }
                else -> return@forEach
            }
            entities = maxOf(entities, result.entities)
            exported += result.filesExported
            failed += result.filesFailed
            bytes += result.bytesExported
            audit("RESPALDO", provider, "EXPORTAR_DIARIO", "$fileName · ${result.entities} registros · ${result.filesExported} archivos")
        }
        val now = TimeUtils.nowIso()
        val latest = state.settings()
        state.saveSettings(latest.copy(externalBackup = latest.externalBackup.copy(
            lastBackupAt = now,
            lastBackupTarget = providers.joinToString(","),
            lastAutomaticBackupAt = now
        )))
        return PortableExportResult(entities, exported, failed, bytes)
    }

    /**
     * Creates a self-contained ZIP for one invoice when the administrator selected
     * a remote backup destination. BACKUP_NONE and LOCAL_ZIP intentionally do not
     * block deletion because backup is optional in 3.12.
     */
    fun backupInvoiceToPreferredDestination(transactionId: String): InvoiceBackupResult? {
        val snap = snapshot()
        require(snap.isAdmin()) { "Solo ADMIN puede respaldar facturas" }
        val providers = snap.settings.infrastructure.backupProviders.filter { it in setOf(BACKUP_GOOGLE_DRIVE, BACKUP_ONEDRIVE, BACKUP_VPS) }
        if (providers.isEmpty()) return null

        val row = db.get(TYPE_TRANSACTIONS, transactionId) ?: error("La factura ya no existe")
        val transaction = Transaction.fromJson(row.payload)
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))
        val safeCode = transaction.code.replace("[^A-Za-z0-9._-]".toRegex(), "_").take(60).ifBlank { transaction.id.take(12) }
        val fileName = "SKC_Factura_${safeCode}_$timestamp.zip"
        val cfg = snap.settings.externalBackup
        var files = 0
        var bytes = 0L
        providers.forEach { provider ->
            val result = when (provider) {
                BACKUP_GOOGLE_DRIVE -> externalBackup.createTreeBackup(cfg.googleDriveTreeUri, fileName) { out -> exportSingleInvoiceBackup(out, transaction) }
                BACKUP_ONEDRIVE -> externalBackup.createTreeBackup(cfg.oneDriveTreeUri, fileName) { out -> exportSingleInvoiceBackup(out, transaction) }
                BACKUP_VPS -> externalBackup.uploadVpsBackup(cfg.vpsBaseUrl, state.vpsBackupToken(), fileName) { out -> exportSingleInvoiceBackup(out, transaction) }
                else -> return@forEach
            }
            files += result.filesExported
            bytes += result.bytesExported
            audit("RESPALDO", transaction.id, "RESPALDAR_FACTURA", "$provider · $fileName · ${result.filesExported} archivos")
        }
        val now = TimeUtils.nowIso()
        val latest = state.settings()
        state.saveSettings(latest.copy(externalBackup = latest.externalBackup.copy(lastBackupAt = now, lastBackupTarget = providers.joinToString(","))))
        return InvoiceBackupResult(fileName, files, bytes)
    }

    private fun exportSingleInvoiceBackup(output: OutputStream, transaction: Transaction): PortableExportResult {
        fun safeName(value: String): String = value.replace("[^A-Za-z0-9._-]".toRegex(), "_").take(120).ifBlank { "archivo" }
        fun putText(zip: ZipOutputStream, name: String, text: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        fun portableTransaction(tx: Transaction): JSONObject {
            val payload = tx.toJson()
            val attachments = payload.optJSONArray("attachments") ?: JSONArray()
            for (i in 0 until attachments.length()) attachments.optJSONObject(i)?.put("localPath", "")
            payload.put("attachments", attachments)
            return payload
        }

        var exported = 0
        var bytesExported = 0L
        ZipOutputStream(output.buffered()).use { zip ->
            putText(zip, "manifest.json", JSONObject().apply {
                put("format", "skc-invoice-backup")
                put("schemaVersion", 1)
                put("appVersion", APP_VERSION)
                put("createdAt", TimeUtils.nowIso())
                put("invoiceId", transaction.id)
                put("invoiceCode", transaction.code)
                put("includesEvidence", true)
                put("source", "SUPABASE")
            }.toString(2))
            putText(zip, "data/invoice.json", portableTransaction(transaction).toString(2))
            transaction.ledgerEntryId.takeIf { it.isNotBlank() }
                ?.let { db.get(TYPE_LEDGER, it)?.payload }
                ?.let { putText(zip, "data/ledger.json", it.toString(2)) }

            transaction.attachments.distinctBy { it.id }.forEach { attachment ->
                val temp = File.createTempFile("skc-invoice-backup-", ".bin", context.cacheDir)
                try {
                    temp.outputStream().use { sync.downloadEvidenceTo(attachment, it) }
                    val actualHash = FileUtils.sha256(temp)
                    require(attachment.sha256.isBlank() || attachment.sha256.equals(actualHash, true)) {
                        "La evidencia ${attachment.name} no pasó la verificación de integridad"
                    }
                    val entry = "evidencias/${safeName(attachment.id)}_${safeName(attachment.name)}"
                    zip.putNextEntry(ZipEntry(entry))
                    temp.inputStream().use { it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                    exported++
                    bytesExported += temp.length()
                } catch (e: Exception) {
                    throw IllegalStateException(
                        "No se pudo respaldar la evidencia '${attachment.name}' en el destino configurado. La factura no se eliminó. ${e.message.orEmpty()}".trim()
                    )
                } finally {
                    temp.delete()
                }
            }
            putText(zip, "README.txt", "Respaldo individual de SKC Facturas. Contiene los datos de la factura, su movimiento contable relacionado y todas las evidencias disponibles al momento del respaldo.")
        }
        return PortableExportResult(1, exported, 0, bytesExported)
    }

    fun backupAndDeleteInvoice(transactionId: String): InvoiceDeleteResult {
        // A permanent cleanup is intentionally online-only. Syncing first guarantees the
        // transaction and its ledger are already durable in Supabase before the document is removed.
        runCatching { sync.sync() }.getOrElse { e ->
            throw IllegalStateException("No se pudo sincronizar con Supabase antes de eliminar. Revisa la conexión e inténtalo de nuevo. ${e.message.orEmpty()}".trim())
        }
        val row = db.get(TYPE_TRANSACTIONS, transactionId) ?: error("La factura ya no existe")
        require(row.syncStatus == SYNCED) { "La factura todavía no está confirmada en Supabase. Sincroniza antes de eliminarla." }
        val transaction = Transaction.fromJson(row.payload)
        transaction.ledgerEntryId.takeIf { it.isNotBlank() }?.let { ledgerId ->
            val ledgerRow = db.get(TYPE_LEDGER, ledgerId)
            require(ledgerRow == null || ledgerRow.syncStatus == SYNCED) {
                "El movimiento contable de esta factura todavía no está confirmado en Supabase. Sincroniza antes de eliminarla."
            }
        }
        val backup = backupInvoiceToPreferredDestination(transactionId)
        return deleteInvoicePermanently(transactionId).copy(backupFileName = backup?.fileName.orEmpty())
    }

    fun signIn(email: String, password: String) {
        val config = state.settings().supabase
        val session = supabase.signIn(config, email, password)
        state.saveSession(session)
        state.completeInitialSetup()
        bindBySession(session)

        runCatching { supabase.bindMyProfile(config, session) }

        runCatching { sync.sync() }
        bindBySession(state.session() ?: session)
    }

    fun signUp(email: String, password: String) {
        val config = state.settings().supabase
        val normalized = email.trim().lowercase()
        val target = snapshot().users.firstOrNull { it.active && it.email.trim().lowercase() == normalized }
        val bootstrapSharedAdmin = target?.role == ROLE_ADMIN && target.authUserId.isBlank()
        require(config.allowSelfSignUp || !snapshot().anyAdmin() || snapshot().isAdmin() || bootstrapSharedAdmin) {
            "La creación de cuentas desde la app está deshabilitada"
        }
        val session = supabase.signUp(config, email, password)
        if (session != null) {
            state.saveSession(session)
            state.completeInitialSetup()
            bindBySession(session)
            runCatching { supabase.bindMyProfile(config, session) }
            runCatching { sync.sync() }
            bindBySession(state.session() ?: session)
        }
    }

    fun signOut() {
        state.saveSession(null)
        state.saveSettings(state.settings().copy(profileUserId = ""))
    }

    private fun bindBySession(session: AuthSession) {
        val snap = snapshot()
        val uid = session.authUserId.trim()
        val byUid = uid.takeIf { it.isNotBlank() }?.let { id ->
            snap.users.firstOrNull { it.active && it.authUserId.isNotBlank() && it.authUserId == id }
        }
        val normalized = session.email.trim().lowercase()
        val byEmail = snap.users.firstOrNull {
            it.active && it.authUserId.isBlank() && normalized.isNotBlank() && it.email.trim().lowercase() == normalized
        }
        val user = byUid ?: byEmail
        state.saveSettings(state.settings().copy(profileUserId = user?.id.orEmpty()))
    }

    fun bootstrapAdmin(userId: String, email: String) {
        val snap = snapshot()
        require(!snap.anyAdmin() || snap.isAdmin()) { "Ya existe un administrador" }
        val user = snap.users.firstOrNull { it.id == userId } ?: error("Usuario inexistente")
        saveUser(user.copy(role = ROLE_ADMIN, email = email.trim(), seeded = false))
        state.saveSettings(state.settings().copy(profileUserId = userId))
    }

    private fun audit(entityType: String, entityId: String, action: String, detail: String = "") {
        val actor = snapshot().currentUser() ?: return
        val now = TimeUtils.nowIso()
        val item = AuditEntry(CodeUtils.uuid(), entityType, entityId, action, actor.id, actor.name, detail.trim(), now, now)
        db.put(TYPE_AUDIT, item.id, item.toJson(), SYNC_PENDING, now)
    }

    fun saveUser(user: UserProfile) {
        val snap = snapshot()
        require(!snap.anyAdmin() || snap.isAdmin()) { "Solo ADMIN puede modificar usuarios" }
        require(user.name.trim().isNotBlank()) { "El nombre es obligatorio" }
        val normalizedEmail = user.email.trim().lowercase()
        require(
            snap.users.none { other ->
                other.id != user.id && (
                    CodeUtils.normalize(other.name) == CodeUtils.normalize(user.name) ||
                        (normalizedEmail.isNotBlank() && other.email.trim().lowercase() == normalizedEmail) ||
                        (user.authUserId.isNotBlank() && other.authUserId == user.authUserId)
                )
            }
        ) { "Ya existe un usuario con ese nombre, correo o identidad Supabase" }
        val now = TimeUtils.nowIso()
        val clean = user.copy(
            name = user.name.trim(),
            email = user.email.trim(),
            initialBalance = if (user.initialBalance.isFinite()) user.initialBalance else 0.0,
            updatedAt = now,
            createdAt = user.createdAt.ifBlank { now },
            seeded = false,
            syncStatus = SYNC_PENDING,
            syncError = ""
        )
        db.transaction {
            db.put(TYPE_USERS, clean.id, clean.toJson(), SYNC_PENDING, now)
            val initialId = "initial-${clean.id}"
            val initial = LedgerEntry(
                id = initialId,
                sourceType = "SALDO_INICIAL",
                sourceId = clean.id,
                sourceCode = "SALDO-INICIAL",
                userId = clean.id,
                username = clean.name,
                direction = if (clean.initialBalance >= 0) "INGRESO" else "EGRESO",
                amount = abs(clean.initialBalance),
                occurredAt = now,
                createdAt = now,
                updatedAt = now,
                createdByUserId = snap.currentUser()?.id.orEmpty(),
                createdBy = snap.currentUser()?.name.orEmpty(),
                reason = "Saldo inicial",
                status = if (clean.initialBalance == 0.0) "ANULADO" else "PUBLICADO",
                syncStatus = SYNC_PENDING
            )
            db.put(TYPE_LEDGER, initialId, initial.toJson(), SYNC_PENDING, now)
        }
        audit("USUARIO", clean.id, "GUARDAR", "${clean.name} · ${clean.role}")
    }

    private fun latestCatalogs(): Catalogs =
        db.get(TYPE_CATALOGS, "global")?.payload?.let(Catalogs::fromJson) ?: snapshot().catalogs

    fun saveCatalogs(catalogs: Catalogs) {
        val snap = snapshot()
        require(snap.isAdmin() || !snap.anyAdmin()) { "Solo ADMIN" }
        val now = TimeUtils.nowIso()
        val clean = catalogs.copy(
            schemaVersion = maxOf(catalogs.schemaVersion, latestCatalogs().schemaVersion, DATA_SCHEMA_VERSION),
            seeded = false, updatedAt = now, syncStatus = SYNC_PENDING, syncError = ""
        ).normalizedProjectHierarchy()
        db.put(TYPE_CATALOGS, "global", clean.toJson(), SYNC_PENDING, now)
        audit("CATALOGOS", "global", "GUARDAR", "Catálogos actualizados")
    }

    fun requestSupplier(name: String, ruc: String, email: String): Message {
        val snap = snapshot()
        val actor = snap.currentUser() ?: error("Usuario")
        val cleanName = name.trim()
        val cleanRuc = ruc.trim()
        val cleanEmail = email.trim()
        require(cleanName.length >= 2) { "Ingrese el nombre del proveedor" }
        require(cleanRuc.length >= 5) { "Ingrese el RUC del proveedor" }
        require(cleanEmail.contains("@") && cleanEmail.substringAfter("@").contains(".")) { "Ingrese un correo válido" }
        if (snap.isAdmin()) {
            val current = latestCatalogs()
            val duplicate = current.suppliers.firstOrNull {
                it.name.equals(cleanName, true) || (cleanRuc.isNotBlank() && it.ruc.equals(cleanRuc, true))
            }
            val saved = SupplierDefinition(duplicate?.id ?: "supplier-${CodeUtils.uuid()}", cleanName, cleanRuc, cleanEmail)
            saveCatalogs(current.copy(suppliers = current.suppliers.filterNot { it.id == saved.id }.plus(saved).sortedBy { it.name.lowercase() }))
            audit("PROVEEDOR", saved.id, if (duplicate == null) "CREAR" else "EDITAR", "$cleanName · $cleanRuc")
            val now = TimeUtils.nowIso()
            return Message(saved.id, "PROV-${saved.id.takeLast(6).uppercase()}", "PROVEEDOR_CREADO", actor.id, actor.name, actor.id, actor.name,
                reason = cleanName, observations = "$cleanRuc · $cleanEmail", status = "COMPLETADA", createdAt = now, updatedAt = now)
        }
        val admins = snap.users.filter { it.active && it.role == ROLE_ADMIN }
        require(admins.isNotEmpty()) { "No existe un administrador activo" }
        val now = TimeUtils.nowIso()
        val groupId = "supplier-request-${CodeUtils.uuid()}"
        var first: Message? = null
        admins.forEach { admin ->
            val id = CodeUtils.uuid()
            val notificationText = "${actor.name} ha creado el proveedor: $cleanName, con RUC: ($cleanRuc) y correo: ($cleanEmail)."
            val message = Message(
                id = id, code = "PROV-${id.take(6).uppercase()}", messageType = "SOLICITUD_PROVEEDOR",
                senderUserId = actor.id, senderName = actor.name, recipientUserId = admin.id, recipientName = admin.name,
                reason = notificationText, observations = "", status = "PENDIENTE",
                createdAt = now, updatedAt = now, senderEmail = actor.email, recipientEmail = admin.email,
                extraFields = mapOf("supplierName" to cleanName, "supplierRuc" to cleanRuc, "supplierEmail" to cleanEmail, "supplierRequestGroup" to groupId)
            )
            db.put(TYPE_MESSAGES, id, message.toJson(), SYNC_PENDING, now)
            if (first == null) first = message
        }
        audit("PROVEEDOR", groupId, "SOLICITAR_ALTA", "$cleanName · $cleanRuc · admins=${admins.size}")
        return first!!
    }

    fun approveSupplierRequest(messageId: String): SupplierDefinition {
        val snap = snapshot()
        require(snap.isAdmin()) { "Solo ADMIN puede crear proveedores" }
        val row = db.get(TYPE_MESSAGES, messageId) ?: error("Solicitud no encontrada")
        val request = Message.fromJson(row.payload)
        require(request.messageType == "SOLICITUD_PROVEEDOR") { "La solicitud no corresponde a un proveedor" }
        require(request.status != "COMPLETADA") { "La solicitud ya fue procesada" }
        val name = request.extraFields["supplierName"].orEmpty().ifBlank { request.reason }.trim()
        val ruc = request.extraFields["supplierRuc"].orEmpty().trim()
        val email = request.extraFields["supplierEmail"].orEmpty().trim()
        val current = latestCatalogs()
        val existing = current.suppliers.firstOrNull { it.name.equals(name, true) || (ruc.isNotBlank() && it.ruc.equals(ruc, true)) }
        val supplier = SupplierDefinition(existing?.id ?: "supplier-${CodeUtils.uuid()}", name, ruc, email)
        saveCatalogs(current.copy(suppliers = current.suppliers.filterNot { it.id == supplier.id }.plus(supplier).sortedBy { it.name.lowercase() }))
        val now = TimeUtils.nowIso()
        val groupId = request.extraFields["supplierRequestGroup"].orEmpty()
        val siblings = snapshot().messages.filter {
            it.messageType == "SOLICITUD_PROVEEDOR" && (it.id == request.id || (groupId.isNotBlank() && it.extraFields["supplierRequestGroup"] == groupId))
        }
        siblings.forEach { pending ->
            db.put(TYPE_MESSAGES, pending.id, pending.copy(status = "COMPLETADA", updatedAt = now, syncStatus = SYNC_PENDING).toJson(), SYNC_PENDING, now)
        }
        audit("PROVEEDOR", supplier.id, "APROBAR_ALTA", "${request.code} · $name")
        return supplier
    }

    fun rejectSupplierRequest(messageId: String, reason: String) {
        val snap = snapshot(); require(snap.isAdmin()) { "Solo ADMIN puede rechazar proveedores" }
        val row = db.get(TYPE_MESSAGES, messageId) ?: error("Solicitud no encontrada")
        val request = Message.fromJson(row.payload)
        require(request.messageType == "SOLICITUD_PROVEEDOR") { "Solicitud inválida" }
        val now = TimeUtils.nowIso()
        db.put(TYPE_MESSAGES, request.id, request.copy(status = "RECHAZADO", rejectedReason = reason.trim(), updatedAt = now, syncStatus = SYNC_PENDING).toJson(), SYNC_PENDING, now)
        audit("PROVEEDOR", request.id, "RECHAZAR_ALTA", reason.trim())
    }

    fun importSuppliersCsv(text: String): SupplierCsvImporter.Result {
        val snap = snapshot()
        require(snap.isAdmin()) { "Solo ADMIN puede cargar proveedores" }
        val parsed = SupplierCsvImporter.parse(text)
        require(parsed.suppliers.isNotEmpty()) { "El archivo no contiene proveedores válidos" }
        val current = latestCatalogs()
        val merged = linkedMapOf<String, SupplierDefinition>()
        (current.suppliers + parsed.suppliers).forEach { supplier ->
            val key = normalizedCatalogKey(supplier.name)
            if (key.isNotBlank()) merged[key] = supplier.copy(
                name = supplier.name.trim(), ruc = supplier.ruc.trim(), email = supplier.email.trim()
            )
        }
        saveCatalogs(current.copy(suppliers = merged.values.toList()))
        audit("CATALOGOS", "suppliers", "IMPORTAR_PROVEEDORES", "${parsed.suppliers.size} proveedor(es)")
        return parsed
    }

    private fun latestAppConfig(): AppConfig =
        db.get(TYPE_APP_CONFIG, "global")?.payload?.let(AppConfig::fromJson) ?: snapshot().config

    fun saveAppConfig(config: AppConfig) {
        val snap = snapshot()
        require(snap.isAdmin() || !snap.anyAdmin()) { "Solo ADMIN" }
        val now = TimeUtils.nowIso()
        val latest = latestAppConfig()
        val formsChanged = formsSignature(config) != formsSignature(latest) || config.revocationKey != latest.revocationKey
        val clean = config.copy(
            schemaVersion = maxOf(config.schemaVersion, latest.schemaVersion, DATA_SCHEMA_VERSION),
            formsSchemaVersion = maxOf(config.formsSchemaVersion, latest.formsSchemaVersion, FORM_SCHEMA_VERSION),
            formsUpdatedAt = config.formsUpdatedAt.ifBlank {
                if (formsChanged) now else latest.formsUpdatedAt
            },
            seeded = false, updatedAt = now, syncStatus = SYNC_PENDING, syncError = ""
        )
        db.put(TYPE_APP_CONFIG, "global", clean.toJson(), SYNC_PENDING, now)
        audit("CONFIGURACION", "global", "GUARDAR", "Configuración actualizada")
    }

    /** Changes transfer confirmation mode without overwriting unrelated remote configuration. */
    fun saveTransferConfirmationMode(required: Boolean) {
        saveAppConfig(latestAppConfig().copy(transferRequiresConfirmation = required))
    }

    /** Updates only the Excel flag against the newest locally stored configuration. */
    fun saveExcelOptions(includeInternalTransfersInExcel: Boolean) {
        val current = latestAppConfig()
        saveAppConfig(current.copy(includeInternalTransfersInExcel = includeInternalTransfersInExcel))
    }

    /** Updates only storage settings so a stale screen cannot overwrite Forms/Excel changes. */
    fun saveStorageConfig(storage: StorageConfig) {
        val current = latestAppConfig()
        saveAppConfig(current.copy(storage = storage))
    }

    /**
     * Saves the complete Forms editor draft against the latest configuration stored locally.
     * Using the latest row avoids replacing unrelated settings with an older ViewModel snapshot
     * when auto-sync refreshes while an administrator is editing the forms.
     */
    private fun formsSignature(config: AppConfig): String = defaultFormLayouts().keys.sorted().joinToString("||") { module ->
        config.fields(module).sortedBy { it.order }.joinToString("|") { field ->
            listOf(
                field.key,
                field.label,
                field.visible.toString(),
                field.required.toString(),
                field.defaultValue,
                field.order.toString(),
                field.core.toString(),
                field.fieldType,
                field.parentKey,
                field.options.joinToString(";") { option -> option.value + ":" + option.label + ":" + option.parentValue + ":" + option.linkedData.toSortedMap().entries.joinToString(",") { it.key + "=" + it.value } },
                field.linkedTable.toJson().toString()
            ).joinToString("~")
        }
    }

    fun saveFormSettings(forms: Map<String, List<FormFieldConfig>>, revocationKey: String) {
        require(snapshot().isAdmin() || !snapshot().anyAdmin()) { "Solo ADMIN" }
        val current = latestAppConfig()
        var updated = current
        defaultFormLayouts().keys.forEach { module ->
            val draft = forms[module] ?: current.fields(module)
            val normalized = draft.sortedBy { it.order }.mapIndexed { index, field ->
                field.copy(key = field.key.trim(), label = field.label.trim(), order = index)
            }
            require(normalized.all { it.key.isNotBlank() && it.label.isNotBlank() }) { "Todos los campos necesitan nombre e identificador" }
            require(normalized.map { it.key.lowercase() }.distinct().size == normalized.size) { "No puede haber dos campos con el mismo identificador" }
            normalized.forEach { field ->
                val table = field.linkedTable
                if (table.enabled) {
                    require(field.fieldType == FIELD_LINKED_SELECT) { "${field.label}: el archivo vinculado requiere una celda desplegable vinculada" }
                    require(table.rows.isNotEmpty()) { "${field.label}: el archivo vinculado no contiene datos" }
                    require(table.columns.count { it.role == LINKED_COLUMN_PRIMARY } == 1) { "${field.label}: selecciona exactamente una columna principal" }
                    val duplicatedTargets = (table.subcategories() + table.linked())
                        .map { it.targetKey.trim() }
                        .filter(String::isNotBlank)
                        .groupingBy { it }
                        .eachCount()
                        .filterValues { it > 1 }
                    require(duplicatedTargets.isEmpty()) { "${field.label}: un mismo campo de destino no puede usarse en dos columnas" }
                    val primary = table.primary()!!
                    val keyColumns = listOf(primary) + table.subcategories()
                    fun lookup(value: String): String = java.text.Normalizer
                        .normalize(value.trim().uppercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
                        .replace("\\p{Mn}+".toRegex(), "")
                    val ambiguous = table.rows
                        .filter { it[primary.sourceColumn].orEmpty().isNotBlank() }
                        .groupingBy { row -> keyColumns.joinToString("\u001F") { column -> lookup(row[column.sourceColumn].orEmpty()) } }
                        .eachCount()
                        .any { (_, count) -> count > 1 }
                    require(!ambiguous) { "${field.label}: agrega otra subcategoría para distinguir combinaciones repetidas" }
                }
            }
            updated = updated.withFields(module, normalized)
        }
        val now = TimeUtils.nowIso()
        val expected = AppConfig.fromJson(
            updated.copy(
                revocationKey = "",
                formsUpdatedAt = now,
                formsSchemaVersion = FORM_SCHEMA_VERSION,
                schemaVersion = maxOf(updated.schemaVersion, DATA_SCHEMA_VERSION)
            ).toJson()
        )
        saveAppConfig(expected)

        // Never report "Formularios guardados" unless the exact form data can be read back from
        // SQLite. This turns silent resets into an explicit error and guarantees persistence on
        // the device before any background sync starts.
        val persisted = latestAppConfig()
        require(formsSignature(persisted) == formsSignature(expected)) {
            "No se pudieron guardar los formularios. Inténtalo nuevamente."
        }
    }

    fun stageEvidence(entityId: String, uris: List<Uri>): List<Attachment> {
        val storage = snapshot().config.storage
        val items = uris.map { FileUtils.stageUri(context, entityId, it, storage) }
        require(items.map { it.sha256 }.distinct().size == items.size) { "Seleccionaste la misma evidencia más de una vez" }
        return items
    }

    fun duplicateMatches(draft: InvoiceDraft, attachmentHash: String = "", excludeTransactionId: String = ""): List<Transaction> {
        val window = snapshot().config.duplicateWindowDays.toLong()
        val date = runCatching { LocalDate.parse(draft.purchaseDate) }.getOrNull()
        val supplierKey = CodeUtils.normalize(draft.supplier)
        return snapshot().transactions.filter { old ->
            if (old.id == excludeTransactionId) return@filter false
            if (old.recordStatus == "REVOCADA") return@filter false
            val sameSupplier = supplierKey.isNotBlank() && supplierKey == CodeUtils.normalize(old.supplier)
            val exactDocument = draft.invoiceNumber.isNotBlank() && old.invoiceNumber.isNotBlank() && sameSupplier &&
                CodeUtils.normalize(draft.invoiceNumber) == CodeUtils.normalize(old.invoiceNumber)
            val sameFile = attachmentHash.isNotBlank() && old.attachmentHash == attachmentHash
            val nearby = date != null && runCatching {
                abs(ChronoUnit.DAYS.between(LocalDate.parse(old.purchaseDate), date)) <= window
            }.getOrDefault(false)
            val similarWithoutDocument = draft.invoiceNumber.isBlank() && old.invoiceNumber.isBlank() && sameSupplier && nearby &&
                abs(old.amount - draft.amount) < 0.001
            exactDocument || sameFile || similarWithoutDocument
        }
    }

    fun saveInvoice(draft: InvoiceDraft, uris: List<Uri>, forceDuplicate: Boolean = false): Transaction {
        val snap = snapshot()
        val actor = snap.currentUser() ?: error("Seleccione un usuario")
        val account = snap.users.firstOrNull { it.id == draft.accountUserId } ?: actor
        val supplier = snap.catalogs.supplier(draft.supplier)
        val normalizedDraft = draft.copy(
            supplier = supplier?.name ?: draft.supplier.trim(),
            supplierRuc = supplier?.ruc?.ifBlank { draft.supplierRuc.trim() } ?: draft.supplierRuc.trim(),
            supplierEmail = supplier?.email?.ifBlank { draft.supplierEmail.trim() } ?: draft.supplierEmail.trim(),
            documentType = InvoiceRules.normalizeDocumentType(draft.documentType)
        )
        validateInvoice(normalizedDraft, snap.config)
        val id = CodeUtils.uuid()
        val code = CodeUtils.transactionCode(account.name, normalizedDraft.purchaseDate)
        val attachments = stageEvidence(id, uris)
        if (snap.config.fields("invoice").any { it.visible && it.required && (it.key == "attachments" || it.fieldType == FIELD_EVIDENCE) }) {
            require(attachments.isNotEmpty()) { "Evidencias es obligatorio" }
        }
        // La evidencia solo es obligatoria si el administrador marcó el campo como requerido.
        // El estado seleccionado no debe imponer una segunda obligatoriedad oculta.
        val hash = FileUtils.combinedHash(attachments)
        val matches = duplicateMatches(normalizedDraft, hash)
        val noProof = normalizedDraft.evidenceStatus.contains("sin comprobante", true)
        if (matches.isNotEmpty() && !forceDuplicate) {
            // The duplicate dialog retries the save later. Remove this first staging pass so
            // evidence is not orphaned every time a user reviews a possible duplicate.
            attachments.forEach { attachment ->
                attachment.localPath.takeIf { it.isNotBlank() }?.let(::File)?.let { file ->
                    runCatching { file.delete() }
                    runCatching { file.parentFile?.takeIf { dir -> dir.isDirectory && dir.list().isNullOrEmpty() }?.delete() }
                }
            }
            throw DuplicateDetected(matches, !noProof)
        }

        val now = TimeUtils.nowIso()
        val review = if (matches.isNotEmpty()) {
            JSONObject().put("reviewedByUserId", actor.id).put("reviewedByName", actor.name)
                .put("reviewedAt", now)
                .put("justification", draft.duplicateJustification.ifBlank { if (noProof) "Compra sin comprobante" else "Confirmado manualmente" })
                .put("matchedTransactionIds", JSONArray(matches.map { it.id })).toString()
        } else ""
        val ledgerId = "formulario-$id"
        val baseDef = snap.catalogs.projects.firstOrNull { it.description == normalizedDraft.descriptionBase || it.id == normalizedDraft.descriptionBase }
        val tx = Transaction(
            id = id,
            code = code,
            movementType = if (normalizedDraft.movementType.equals("Ingreso", true)) "Ingreso" else "Gasto",
            purchaseDate = normalizedDraft.purchaseDate,
            descriptionBase = baseDef?.description ?: normalizedDraft.descriptionBase,
            project = normalizedDraft.project,
            description = normalizedDraft.description,
            area = normalizedDraft.area,
            liquidationType = normalizedDraft.liquidationType,
            costCenter = normalizedDraft.costCenter,
            secondaryCost = normalizedDraft.secondaryCost,
            client = normalizedDraft.client,
            supplier = normalizedDraft.supplier,
            supplierRuc = normalizedDraft.supplierRuc,
            supplierEmail = normalizedDraft.supplierEmail,
            documentType = normalizedDraft.documentType,
            invoiceNumber = normalizedDraft.invoiceNumber,
            paymentMethod = normalizedDraft.paymentMethod,
            evidenceStatus = normalizedDraft.evidenceStatus,
            observations = normalizedDraft.observations,
            amount = normalizedDraft.amount,
            accountUserId = account.id,
            accountUsername = account.name,
            createdByUserId = actor.id,
            createdByName = actor.name,
            groupName = snap.config.groupName,
            attachments = attachments,
            attachmentHash = hash,
            duplicateReview = review,
            createdAt = now,
            updatedAt = now,
            userEmail = account.email,
            descriptionBaseId = baseDef?.id.orEmpty(),
            reviewStatus = if (matches.isEmpty()) "NUEVO" else "DUPLICADO_REVISADO",
            ledgerEntryId = ledgerId,
            invoiceKey = CodeUtils.normalize("${normalizedDraft.supplier}|${normalizedDraft.invoiceNumber}"),
            extraFields = normalizedDraft.extraFields
        )
        val ledger = LedgerEntry(
            id = ledgerId,
            sourceType = "FORMULARIO",
            sourceId = id,
            sourceCode = code,
            userId = account.id,
            username = account.name,
            direction = if (tx.movementType == "Ingreso") "INGRESO" else "EGRESO",
            amount = abs(normalizedDraft.amount),
            occurredAt = normalizedDraft.purchaseDate,
            createdAt = now,
            updatedAt = now,
            createdByUserId = actor.id,
            createdBy = actor.name,
            reason = normalizedDraft.description.ifBlank { normalizedDraft.descriptionBase.ifBlank { normalizedDraft.supplier } }
        )
        db.transaction {
            db.put(TYPE_TRANSACTIONS, id, tx.toJson(), SYNC_PENDING, now)
            db.put(TYPE_LEDGER, ledgerId, ledger.toJson(), SYNC_PENDING, now)
        }
        audit("TRANSACCION", id, "CREAR", code)
        return tx
    }

    /**
     * Edits an existing active invoice without changing its identity or creation audit trail.
     * Existing evidence is preserved; new evidence can be appended. The related ledger row is
     * updated in the same local transaction so amount/user/direction changes keep balances correct.
     */
    fun canEditInvoice(transactionId: String): Boolean {
        val snap = snapshot()
        val actor = snap.currentUser() ?: return false
        val tx = snap.transactions.firstOrNull { it.id == transactionId } ?: return false
        if (tx.recordStatus != "ACTIVA") return false
        if (snap.isAdmin()) return true
        val owner = tx.accountUserId == actor.id || tx.createdByUserId == actor.id
        val created = instantOrEpoch(tx.createdAt)
        val within24Hours = created != Instant.EPOCH && ChronoUnit.HOURS.between(created, Instant.now()) < 24
        return owner && within24Hours
    }

    fun updateInvoice(transactionId: String, draft: InvoiceDraft, newUris: List<Uri>): Transaction {
        val snap = snapshot()
        val actor = snap.currentUser() ?: error("Usuario")
        val row = db.get(TYPE_TRANSACTIONS, transactionId) ?: error("La factura ya no existe")
        val current = Transaction.fromJson(row.payload)
        require(current.recordStatus == "ACTIVA") { "Solo se pueden modificar facturas activas" }
        val owner = current.accountUserId == actor.id || current.createdByUserId == actor.id
        val created = instantOrEpoch(current.createdAt)
        val within24Hours = created != Instant.EPOCH && ChronoUnit.HOURS.between(created, Instant.now()) < 24
        require(snap.isAdmin() || (owner && within24Hours)) {
            "Puedes editar únicamente tus facturas durante las primeras 24 horas. Después debe corregirla un administrador."
        }

        val account = snap.users.firstOrNull { it.id == draft.accountUserId } ?: actor
        val supplier = snap.catalogs.supplier(draft.supplier)
        val normalizedDraft = draft.copy(
            supplier = supplier?.name ?: draft.supplier.trim(),
            supplierRuc = supplier?.ruc?.ifBlank { draft.supplierRuc.trim() } ?: draft.supplierRuc.trim(),
            supplierEmail = supplier?.email?.ifBlank { draft.supplierEmail.trim() } ?: draft.supplierEmail.trim(),
            documentType = InvoiceRules.normalizeDocumentType(draft.documentType)
        )
        validateInvoice(normalizedDraft, snap.config)

        val staged = stageEvidence(current.id, newUris)
        try {
            val attachments = current.attachments + staged
            if (snap.config.fields("invoice").any { it.visible && it.required && (it.key == "attachments" || it.fieldType == FIELD_EVIDENCE) }) {
                require(attachments.isNotEmpty()) { "Evidencias es obligatorio" }
            }
            // Respetar el ajuste de evidencia opcional también al editar facturas.
            val hash = FileUtils.combinedHash(attachments)
            val duplicates = duplicateMatches(normalizedDraft, hash, current.id)
            val now = TimeUtils.nowIso()
            val duplicateReview = if (duplicates.isNotEmpty()) {
                JSONObject().put("reviewedByUserId", actor.id).put("reviewedByName", actor.name)
                    .put("reviewedAt", now)
                    .put("justification", "Edición administrativa")
                    .put("matchedTransactionIds", JSONArray(duplicates.map { it.id })).toString()
            } else current.duplicateReview
            val baseDef = snap.catalogs.projects.firstOrNull {
                it.description == normalizedDraft.descriptionBase || it.id == normalizedDraft.descriptionBase
            }
            val updated = current.copy(
                movementType = if (normalizedDraft.movementType.equals("Ingreso", true)) "Ingreso" else "Gasto",
                purchaseDate = normalizedDraft.purchaseDate,
                descriptionBase = baseDef?.description ?: normalizedDraft.descriptionBase,
                descriptionBaseId = baseDef?.id.orEmpty(),
                project = normalizedDraft.project,
                description = normalizedDraft.description,
                area = normalizedDraft.area,
                liquidationType = normalizedDraft.liquidationType,
                costCenter = normalizedDraft.costCenter,
                secondaryCost = normalizedDraft.secondaryCost,
                client = normalizedDraft.client,
                supplier = normalizedDraft.supplier,
                supplierRuc = normalizedDraft.supplierRuc,
                supplierEmail = normalizedDraft.supplierEmail,
                documentType = normalizedDraft.documentType,
                invoiceNumber = normalizedDraft.invoiceNumber,
                paymentMethod = normalizedDraft.paymentMethod,
                evidenceStatus = normalizedDraft.evidenceStatus,
                observations = normalizedDraft.observations,
                amount = normalizedDraft.amount,
                accountUserId = account.id,
                accountUsername = account.name,
                userEmail = account.email,
                attachments = attachments,
                attachmentHash = hash,
                duplicateReview = duplicateReview,
                reviewStatus = if (duplicates.isNotEmpty()) "DUPLICADO_REVISADO" else current.reviewStatus,
                invoiceKey = CodeUtils.normalize("${normalizedDraft.supplier}|${normalizedDraft.invoiceNumber}"),
                extraFields = normalizedDraft.extraFields,
                updatedAt = now,
                syncStatus = SYNC_PENDING,
                syncError = ""
            )

            val ledger = current.ledgerEntryId.takeIf { it.isNotBlank() }
                ?.let { db.get(TYPE_LEDGER, it)?.payload }
                ?.let(LedgerEntry::fromJson)
            val updatedLedger = ledger?.copy(
                userId = account.id,
                username = account.name,
                direction = if (updated.movementType == "Ingreso") "INGRESO" else "EGRESO",
                amount = abs(updated.amount),
                occurredAt = updated.purchaseDate,
                reason = updated.description.ifBlank { updated.descriptionBase.ifBlank { updated.supplier } },
                updatedAt = now,
                syncStatus = SYNC_PENDING,
                syncError = ""
            )

            db.transaction {
                db.put(TYPE_TRANSACTIONS, updated.id, updated.toJson(), SYNC_PENDING, now)
                if (updatedLedger != null) db.put(TYPE_LEDGER, updatedLedger.id, updatedLedger.toJson(), SYNC_PENDING, now)
            }
            audit(
                "TRANSACCION",
                updated.id,
                "EDITAR",
                "${updated.code} · monto=${updated.amount} · usuario=${updated.accountUsername} · evidencias=${updated.attachments.size}"
            )
            return updated
        } catch (e: Exception) {
            staged.forEach { attachment ->
                attachment.localPath.takeIf { it.isNotBlank() }?.let(::File)?.let { file ->
                    runCatching { file.delete() }
                    runCatching { file.parentFile?.takeIf { dir -> dir.isDirectory && dir.list().isNullOrEmpty() }?.delete() }
                }
            }
            throw e
        }
    }

    private fun validateInvoice(draft: InvoiceDraft, config: AppConfig) {
        require(draft.amount > 0 && draft.amount.isFinite()) { "Ingrese un monto mayor a cero" }
        require(draft.description.length <= config.descriptionLimit) { "La descripción supera ${config.descriptionLimit} caracteres" }
        InvoiceRules.documentError(draft.documentType, draft.invoiceNumber)?.let { error(it) }
        val values = mapOf(
            "movementType" to draft.movementType,
            "descriptionBase" to draft.descriptionBase,
            "project" to draft.project,
            "description" to draft.description,
            "area" to draft.area,
            "liquidationType" to draft.liquidationType,
            "accountUserId" to draft.accountUserId,
            "amount" to if (draft.amount > 0) "ok" else "",
            "purchaseDate" to draft.purchaseDate,
            "evidenceStatus" to draft.evidenceStatus,
            "supplier" to draft.supplier,
            "invoiceNumber" to draft.invoiceNumber,
            "costCenter" to draft.costCenter,
            "secondaryCost" to draft.secondaryCost,
            "client" to draft.client,
            "paymentMethod" to draft.paymentMethod,
            "observations" to draft.observations
        )
        config.fields("invoice").filter { it.visible && it.required && it.key !in listOf("attachments", "recentRecords") && it.fieldType != FIELD_EVIDENCE }
            .forEach { field ->
                val value = values[field.key] ?: draft.extraFields[field.key].orEmpty()
                require(value.isNotBlank()) { "${field.label} es obligatorio" }
            }
    }

    fun revokeInvoiceDirect(transactionId: String, reason: String) {
        val snap = snapshot()
        val actor = snap.currentUser() ?: error("Usuario")
        val txRow = db.get(TYPE_TRANSACTIONS, transactionId) ?: error("Factura")
        val tx = Transaction.fromJson(txRow.payload)
        val ownsInvoice = tx.createdByUserId == actor.id || tx.accountUserId == actor.id
        require(snap.isAdmin() || ownsInvoice) { "Solo puedes revocar tus propias facturas" }
        require(tx.recordStatus == "ACTIVA") { if (tx.recordStatus == "REVOCADA") "La factura ya fue revocada" else "La factura no está activa" }
        val cleanReason = reason.trim()
        require(cleanReason.length >= 3) { "Indica el motivo de la revocación" }
        val now = TimeUtils.nowIso()
        val updatedTx = tx.copy(
            recordStatus = "REVOCADA",
            reviewStatus = "REVOCADA",
            revocationRequestId = "",
            revocationReason = cleanReason,
            revokedAt = now,
            revokedByUserId = actor.id,
            updatedAt = now,
            syncStatus = SYNC_PENDING,
            syncError = ""
        )
        db.transaction {
            db.put(TYPE_TRANSACTIONS, tx.id, updatedTx.toJson(), SYNC_PENDING, now)
            if (tx.ledgerEntryId.isNotBlank()) {
                val old = db.get(TYPE_LEDGER, tx.ledgerEntryId)?.payload?.let(LedgerEntry::fromJson)
                if (old != null && old.status == "PUBLICADO") {
                    val reversalId = "reverso-${old.id}"
                    if (db.get(TYPE_LEDGER, reversalId) == null) {
                        val reversal = LedgerEntry(
                            id = reversalId,
                            sourceType = "REVERSO",
                            sourceId = old.id,
                            sourceCode = "REV-${old.sourceCode}",
                            userId = old.userId,
                            username = old.username,
                            direction = if (old.direction == "INGRESO") "EGRESO" else "INGRESO",
                            amount = old.amount,
                            occurredAt = now,
                            createdAt = now,
                            updatedAt = now,
                            createdByUserId = actor.id,
                            createdBy = actor.name,
                            reason = cleanReason,
                            reversalOf = old.id,
                            syncStatus = SYNC_PENDING
                        )
                        db.put(TYPE_LEDGER, reversalId, reversal.toJson(), SYNC_PENDING, now)
                    }
                }
            }
        }
        audit("TRANSACCION", tx.id, "REVOCAR", cleanReason)
    }

    fun requestInvoiceRevocation(transactionId: String, reason: String): Message {
        val snap = snapshot()
        val actor = snap.currentUser() ?: error("Usuario")
        val tx = snap.transactions.firstOrNull { it.id == transactionId } ?: error("Factura")
        require(tx.createdByUserId == actor.id || tx.accountUserId == actor.id || snap.isAdmin()) { "Solo puedes solicitar revocación de tus propios registros" }
        require(tx.recordStatus == "ACTIVA") {
            if (tx.recordStatus == "REVOCACION_SOLICITADA") "La revocación ya fue solicitada" else "La factura ya fue revocada"
        }
        require(reason.trim().length >= 3) { "Indica por qué debe revocarse la factura" }
        val admin = snap.users.firstOrNull { it.active && it.role == ROLE_ADMIN && it.id != actor.id }
            ?: snap.users.firstOrNull { it.active && it.role == ROLE_ADMIN }
            ?: error("No hay un administrador activo para recibir la solicitud")
        val now = TimeUtils.nowIso()
        val id = CodeUtils.uuid()
        val request = Message(
            id = id,
            code = CodeUtils.messageCode("REV", actor.name),
            messageType = "SOLICITUD_REVOCACION",
            senderUserId = actor.id,
            senderName = actor.name,
            recipientUserId = admin.id,
            recipientName = admin.name,
            reason = reason.trim(),
            status = "PENDIENTE",
            createdAt = now,
            updatedAt = now,
            senderEmail = actor.email,
            recipientEmail = admin.email,
            relatedTransactionId = tx.id
        )
        val updatedTx = tx.copy(
            recordStatus = "REVOCACION_SOLICITADA",
            revocationRequestId = id,
            revocationReason = reason.trim(),
            updatedAt = now,
            syncStatus = SYNC_PENDING,
            syncError = ""
        )
        db.transaction {
            db.put(TYPE_MESSAGES, id, request.toJson(), SYNC_PENDING, now)
            db.put(TYPE_TRANSACTIONS, tx.id, updatedTx.toJson(), SYNC_PENDING, now)
        }
        audit("TRANSACCION", tx.id, "SOLICITAR_REVOCACION", reason.trim())
        return request
    }

    fun respondInvoiceRevocation(requestId: String, approve: Boolean, rejectReason: String = "") {
        val snap = snapshot()
        val admin = snap.currentUser() ?: error("Usuario")
        require(snap.isAdmin()) { "Solo ADMIN puede resolver revocaciones" }
        val row = db.get(TYPE_MESSAGES, requestId) ?: error("Solicitud")
        val request = Message.fromJson(row.payload)
        require(request.messageType == "SOLICITUD_REVOCACION") { "La solicitud no es de revocación" }
        require(request.status == "PENDIENTE") { "La solicitud ya fue procesada" }
        val txRow = db.get(TYPE_TRANSACTIONS, request.relatedTransactionId) ?: error("La factura ya no existe")
        val tx = Transaction.fromJson(txRow.payload)
        require(tx.recordStatus == "REVOCACION_SOLICITADA") { "La factura no está pendiente de revocación" }
        val now = TimeUtils.nowIso()
        if (!approve) require(rejectReason.trim().length >= 3) { "Indica el motivo del rechazo" }

        val updatedRequest = request.copy(
            status = if (approve) "APROBADA" else "RECHAZADA",
            confirmedByUserId = if (approve) admin.id else "",
            confirmedAt = if (approve) now else "",
            rejectedReason = if (approve) "" else rejectReason.trim(),
            respondedByUserId = admin.id,
            respondedAt = now,
            updatedAt = now,
            syncStatus = SYNC_PENDING,
            syncError = ""
        )
        val updatedTx = if (approve) tx.copy(
            recordStatus = "REVOCADA",
            reviewStatus = "REVOCADA",
            revokedAt = now,
            revokedByUserId = admin.id,
            revocationReason = request.reason,
            updatedAt = now,
            syncStatus = SYNC_PENDING,
            syncError = ""
        ) else tx.copy(
            recordStatus = "ACTIVA",
            revocationRequestId = "",
            revocationReason = "",
            updatedAt = now,
            syncStatus = SYNC_PENDING,
            syncError = ""
        )

        db.transaction {
            db.put(TYPE_MESSAGES, request.id, updatedRequest.toJson(), SYNC_PENDING, now)
            db.put(TYPE_TRANSACTIONS, tx.id, updatedTx.toJson(), SYNC_PENDING, now)
            if (approve && tx.ledgerEntryId.isNotBlank()) {
                val old = db.get(TYPE_LEDGER, tx.ledgerEntryId)?.payload?.let(LedgerEntry::fromJson)
                if (old != null && old.status == "PUBLICADO" && db.all(TYPE_LEDGER).none { ledRow ->
                        val candidate = LedgerEntry.fromJson(ledRow.payload)
                        candidate.sourceType == "REVERSO" && candidate.reversalOf == old.id
                    }) {
                    // Deterministic ID makes approval idempotent across two ADMIN devices:
                    // both converge on the same reversal instead of double-reversing the balance.
                    val reversalId = "reverso-${old.id}"
                    val reversal = LedgerEntry(
                        id = reversalId,
                        sourceType = "REVERSO",
                        sourceId = old.id,
                        sourceCode = "REV-${old.sourceCode}",
                        userId = old.userId,
                        username = old.username,
                        direction = if (old.direction == "INGRESO") "EGRESO" else "INGRESO",
                        amount = old.amount,
                        occurredAt = now,
                        createdAt = now,
                        updatedAt = now,
                        createdByUserId = admin.id,
                        createdBy = admin.name,
                        reason = "Revocación ${tx.code}: ${request.reason}",
                        reversalOf = old.id
                    )
                    db.put(TYPE_LEDGER, reversalId, reversal.toJson(), SYNC_PENDING, now)
                }
            }
        }
        audit("TRANSACCION", tx.id, if (approve) "REVOCAR" else "RECHAZAR_REVOCACION", if (approve) request.reason else rejectReason.trim())
    }

    fun createReminder(reason: String): Reminder {
        val user = snapshot().currentUser() ?: error("Usuario")
        require(reason.trim().length >= 3) { "Indique qué se compró o qué debe registrar" }
        val now = TimeUtils.nowIso()
        val reminder = Reminder(
            id = CodeUtils.uuid(), userId = user.id, username = user.name, reason = reason.trim(),
            nextNotifyAt = TimeUtils.plusMinutesIso(snapshot().config.reminderIntervalMinutes),
            createdAt = now, updatedAt = now
        )
        db.put(TYPE_REMINDERS, reminder.id, reminder.toJson(), SYNC_PENDING, now)
        audit("RECORDATORIO", reminder.id, "CREAR", reminder.reason)
        return reminder
    }

    fun postponeReminder(id: String, minutes: Int = snapshot().config.reminderIntervalMinutes) {
        val reminder = db.get(TYPE_REMINDERS, id)?.payload?.let(Reminder::fromJson) ?: return
        require(reminder.status == "PENDIENTE") { "El recordatorio ya no está pendiente" }
        val user = snapshot().currentUser()
        require(snapshot().isAdmin() || reminder.userId == user?.id) { "No puede posponer el recordatorio de otro usuario" }
        val now = TimeUtils.nowIso()
        val updated = reminder.copy(nextNotifyAt = TimeUtils.plusMinutesIso(minutes.coerceAtLeast(10)), updatedAt = now, syncStatus = SYNC_PENDING)
        db.put(TYPE_REMINDERS, id, updated.toJson(), SYNC_PENDING, now)
        audit("RECORDATORIO", id, "POSPONER", "$minutes minutos")
    }

    fun cancelReminder(id: String, reason: String) {
        val reminder = db.get(TYPE_REMINDERS, id)?.payload?.let(Reminder::fromJson) ?: return
        require(reminder.status == "PENDIENTE") { "El recordatorio ya no está pendiente" }
        val user = snapshot().currentUser()
        require(snapshot().isAdmin() || reminder.userId == user?.id) { "No puede cancelar el recordatorio de otro usuario" }
        val now = TimeUtils.nowIso()
        val updated = reminder.copy(status = "CANCELADO", cancelReason = reason.trim(), updatedAt = now, syncStatus = SYNC_PENDING)
        db.put(TYPE_REMINDERS, id, updated.toJson(), SYNC_PENDING, now)
        audit("RECORDATORIO", id, "CANCELAR", updated.cancelReason)
    }

    fun completeReminder(id: String, transactionId: String) {
        val reminder = db.get(TYPE_REMINDERS, id)?.payload?.let(Reminder::fromJson) ?: return
        val now = TimeUtils.nowIso()
        val updated = reminder.copy(status = "COMPLETADO", linkedTransactionId = transactionId, updatedAt = now, syncStatus = SYNC_PENDING)
        db.put(TYPE_REMINDERS, id, updated.toJson(), SYNC_PENDING, now)
        audit("RECORDATORIO", id, "COMPLETAR", transactionId)
    }

    fun createAmountRequest(
        recipientId: String,
        recipientEmail: String,
        amount: Double,
        project: String,
        reason: String,
        balanceDeclared: Double,
        uris: List<Uri>,
        extraFields: Map<String, String> = emptyMap()
    ): Message = createMessage(
        type = "SOLICITUD_MONTO",
        recipientId = recipientId,
        recipientEmail = recipientEmail,
        amount = amount,
        project = project,
        reason = reason,
        balanceDeclared = balanceDeclared,
        reference = "",
        transferDate = "",
        uris = uris,
        sourceRequestId = "",
        extraFields = extraFields
    )

    fun createTransfer(
        recipientId: String,
        amount: Double,
        project: String,
        reason: String,
        reference: String,
        transferDate: String,
        uris: List<Uri>,
        sourceRequestId: String = "",
        extraFields: Map<String, String> = emptyMap()
    ): Message = createMessage(
        type = "TRANSFERENCIA",
        recipientId = recipientId,
        recipientEmail = "",
        amount = amount,
        project = project,
        reason = reason,
        balanceDeclared = 0.0,
        reference = reference,
        transferDate = transferDate,
        uris = uris,
        sourceRequestId = sourceRequestId,
        extraFields = extraFields
    )

    private fun createMessage(
        type: String,
        recipientId: String,
        recipientEmail: String,
        amount: Double,
        project: String,
        reason: String,
        balanceDeclared: Double,
        reference: String,
        transferDate: String,
        uris: List<Uri>,
        sourceRequestId: String = "",
        extraFields: Map<String, String> = emptyMap()
    ): Message {
        val snap = snapshot()
        val from = snap.currentUser() ?: error("Usuario")
        val to = snap.users.firstOrNull { it.id == recipientId && it.active }
        require(amount > 0 && amount.isFinite()) { "Monto inválido" }
        val module = if (type == "TRANSFERENCIA") "transfer" else "fundRequest"
        if (snap.config.fields(module).any { it.visible && it.required && it.key == "reason" }) {
            require(reason.trim().isNotBlank()) { "Motivo obligatorio" }
        }
        if (type == "TRANSFERENCIA") {
            require(from.transferEnabled || snap.isAdmin()) { "Transferencias no habilitadas" }
            requireNotNull(to) { "Seleccione un beneficiario válido" }
            require(to.id != from.id) { "El beneficiario debe ser otra persona" }
        } else {
            require(to != null || recipientEmail.trim().isNotBlank()) { "Seleccione un destinatario o indique un correo externo" }
        }
        val values = mapOf(
            "amount" to if (amount > 0) "ok" else "",
            "recipientUserId" to recipientId,
            "recipientEmail" to recipientEmail,
            "project" to project,
            "reason" to reason,
            "balanceDeclared" to balanceDeclared.toString(),
            "reference" to reference,
            "transferDate" to transferDate
        )
        snap.config.fields(module).filter { it.visible && it.required && it.key != "attachments" && it.fieldType != FIELD_EVIDENCE }.forEach { field ->
            val value = values[field.key] ?: extraFields[field.key].orEmpty()
            require(value.trim().isNotBlank()) { "${field.label} es obligatorio" }
        }
        val id = CodeUtils.uuid()
        val prefix = if (type == "TRANSFERENCIA") "TRF" else "SOL"
        val now = TimeUtils.nowIso()
        val code = CodeUtils.messageCode(prefix, from.name)
        val attachments = stageEvidence(id, uris)
        if (snap.config.fields(module).any { it.visible && it.required && (it.key == "attachments" || it.fieldType == FIELD_EVIDENCE) }) {
            require(attachments.isNotEmpty()) { "Adjuntar evidencia es obligatorio" }
        }
        // En modo directo el emisor acredita inmediatamente al receptor.
        // La confirmación manual sigue aplicando a transferencias anteriores pendientes.
        val automaticTransfer = type == "TRANSFERENCIA" && !snap.config.transferRequiresConfirmation
        val ledgerId = if (automaticTransfer) "transferencia-$id" else ""
        val message = Message(
            id = id,
            code = code,
            messageType = type,
            senderUserId = from.id,
            senderName = from.name,
            recipientUserId = to?.id.orEmpty(),
            recipientName = to?.name ?: "Finanzas",
            amount = amount,
            project = project.trim(),
            reason = reason.trim(),
            status = when {
                type == "SOLICITUD_MONTO" -> "PENDIENTE"
                automaticTransfer -> "CONFIRMADO"
                else -> "ENVIADO"
            },
            confirmedByUserId = if (automaticTransfer) from.id else "",
            confirmedAt = if (automaticTransfer) now else "",
            ledgerEntryId = ledgerId,
            attachments = attachments,
            createdAt = now,
            updatedAt = now,
            senderEmail = from.email,
            recipientEmail = to?.email ?: recipientEmail.trim(),
            balanceDeclared = balanceDeclared,
            transferReference = reference.trim(),
            transferDate = if (type == "TRANSFERENCIA") transferDate.ifBlank { TimeUtils.todayIso() } else "",
            sourceRequestId = sourceRequestId,
            extraFields = extraFields
        )
        db.transaction {
            db.put(TYPE_MESSAGES, id, message.toJson(), SYNC_PENDING, now)
            if (automaticTransfer) {
                val beneficiary = requireNotNull(to)
                val ledger = LedgerEntry(
                    id = ledgerId,
                    sourceType = "TRANSFERENCIA",
                    sourceId = id,
                    sourceCode = code,
                    userId = beneficiary.id,
                    username = beneficiary.name,
                    direction = "INGRESO",
                    amount = amount,
                    occurredAt = message.transferDate,
                    createdAt = now,
                    updatedAt = now,
                    createdByUserId = from.id,
                    createdBy = from.name,
                    reason = reason.trim().ifBlank { "Transferencia directa" }
                )
                db.put(TYPE_LEDGER, ledgerId, ledger.toJson(), SYNC_PENDING, now)
                if (sourceRequestId.isNotBlank()) {
                    db.get(TYPE_MESSAGES, sourceRequestId)?.payload?.let(Message::fromJson)?.let { request ->
                        db.put(TYPE_MESSAGES, request.id, request.copy(
                            status = "COMPLETADA", updatedAt = now, syncStatus = SYNC_PENDING
                        ).toJson(), SYNC_PENDING, now)
                    }
                }
            }
        }
        audit("MENSAJE", id,
            if (automaticTransfer) "TRANSFERENCIA_DIRECTA" else if (type == "TRANSFERENCIA") "ENVIAR_TRANSFERENCIA" else "SOLICITAR_MONTO",
            code
        )
        return message
    }

    fun editMessage(id: String, amount: Double, reason: String, observations: String = ""): Message {
        val snap = snapshot(); val actor = snap.currentUser() ?: error("Usuario")
        val row = db.get(TYPE_MESSAGES, id) ?: error("Mensaje")
        val message = Message.fromJson(row.payload)
        require(message.deletedAt.isBlank() && message.status != "ELIMINADO") { "El mensaje fue eliminado" }
        require(message.senderUserId == actor.id || snap.isAdmin()) { "Solo el remitente o un administrador puede editar este mensaje" }
        require(message.status !in listOf("CONFIRMADO", "COMPLETADA")) { "Un movimiento ya confirmado no puede editarse" }
        if (message.messageType != "SOLICITUD_PROVEEDOR") require(amount > 0 && amount.isFinite()) { "Ingrese un monto válido" }
        val now = TimeUtils.nowIso()
        val updated = message.copy(amount = if (message.messageType == "SOLICITUD_PROVEEDOR") message.amount else amount, reason = reason.trim(), observations = observations.trim(), editedAt = now, editedByUserId = actor.id, updatedAt = now, syncStatus = SYNC_PENDING)
        db.put(TYPE_MESSAGES, id, updated.toJson(), SYNC_PENDING, now)
        audit("MENSAJE", id, "EDITAR", updated.code)
        return updated
    }

    fun deleteMessageFromConversation(id: String): Message {
        val snap = snapshot(); val actor = snap.currentUser() ?: error("Usuario")
        val row = db.get(TYPE_MESSAGES, id) ?: error("Mensaje")
        val message = Message.fromJson(row.payload)
        require(message.senderUserId == actor.id || snap.isAdmin()) { "Solo el remitente o un administrador puede borrar este mensaje" }
        require(message.status !in listOf("CONFIRMADO", "COMPLETADA")) { "Un movimiento confirmado debe corregirse desde el historial administrativo" }
        if (message.messageType == "SOLICITUD_MONTO") {
            require(message.responseTransferId.isBlank()) { "Elimina primero la transferencia creada como respuesta" }
        }

        // 3.15.2: borrar un mensaje significa borrarlo físicamente. No se guarda una fila
        // ELIMINADO/tombstone en skc_events. Como SyncEngine reconcilia las filas SYNCED que
        // desaparecieron del servidor, el otro participante también lo elimina en su siguiente pull.
        runCatching { sync.sync() }.getOrElse { e ->
            throw IllegalStateException("No se pudo sincronizar antes de borrar el mensaje. ${e.message.orEmpty()}".trim())
        }
        val (supabaseConfig, session) = sync.ensureSession()
        runCatching { supabase.deleteMessagePhysically(supabaseConfig, session, message.id) }.getOrElse { e ->
            throw IllegalStateException(
                "No se pudo borrar el mensaje en Supabase. Ejecuta supabase/migration-3.15.2.sql si aún no la instalaste. ${e.message.orEmpty()}".trim()
            )
        }

        cleanupAttachments(message.attachments, snap.config.storage, supabaseConfig, session)
        val now = TimeUtils.nowIso()
        db.transaction {
            db.delete(TYPE_MESSAGES, id)
            if (message.messageType == "TRANSFERENCIA" && message.sourceRequestId.isNotBlank()) {
                db.get(TYPE_MESSAGES, message.sourceRequestId)?.payload?.let(Message::fromJson)?.let { request ->
                    if (request.status != "COMPLETADA") {
                        val reopened = request.copy(
                            status = "PENDIENTE",
                            responseTransferId = "",
                            respondedByUserId = "",
                            respondedAt = "",
                            updatedAt = now,
                            syncStatus = SYNC_PENDING
                        )
                        db.put(TYPE_MESSAGES, request.id, reopened.toJson(), SYNC_PENDING, now)
                    }
                }
            }
        }
        return message
    }

    fun respondAmountRequest(
        id: String,
        useRequestedAmount: Boolean,
        customAmount: Double = 0.0,
        uris: List<Uri>,
        responseReason: String = ""
    ): Message {
        val snap = snapshot()
        val actor = snap.currentUser() ?: error("Usuario")
        val row = db.get(TYPE_MESSAGES, id) ?: error("Solicitud")
        val request = Message.fromJson(row.payload)
        require(request.messageType == "SOLICITUD_MONTO") { "No se encontró la solicitud" }
        require(request.recipientUserId == actor.id) { "Solo el destinatario puede responder esta solicitud" }
        require(request.status !in listOf("RESPONDIDA", "COMPLETADA")) { "La solicitud ya fue respondida" }
        require(request.responseTransferId.isBlank()) { "La solicitud ya tiene una transferencia asociada" }
        val amount = if (useRequestedAmount) request.amount else customAmount
        require(amount > 0 && amount.isFinite()) { "Ingrese un monto válido" }
        val transfer = createTransfer(
            recipientId = request.senderUserId,
            amount = amount,
            project = request.project,
            reason = responseReason.trim().ifBlank { request.reason.ifBlank { "Respuesta a ${request.code}" } },
            reference = "",
            transferDate = TimeUtils.todayIso(),
            uris = uris,
            sourceRequestId = request.id
        )
        val now = TimeUtils.nowIso()
        val updated = request.copy(
            status = if (transfer.status == "CONFIRMADO") "COMPLETADA" else "RESPONDIDA",
            responseTransferId = transfer.id,
            respondedByUserId = actor.id,
            respondedAt = now,
            updatedAt = now,
            syncStatus = SYNC_PENDING
        )
        db.put(TYPE_MESSAGES, request.id, updated.toJson(), SYNC_PENDING, now)
        audit("MENSAJE", request.id, "RESPONDER_SOLICITUD", transfer.code)
        return transfer
    }

    fun respondTransfer(id: String, confirm: Boolean, rejectReason: String = "") {
        val snap = snapshot()
        val actor = snap.currentUser() ?: error("Usuario")
        val row = db.get(TYPE_MESSAGES, id) ?: error("Mensaje")
        val message = Message.fromJson(row.payload)
        require(message.messageType == "TRANSFERENCIA") { "No se encontró la transferencia" }
        require(message.recipientUserId == actor.id || snap.isAdmin()) { "Solo el destinatario o un administrador puede procesar esta transferencia" }
        require(message.status !in listOf("CONFIRMADO", "RECHAZADO")) { "La transferencia ya fue procesada" }
        val now = TimeUtils.nowIso()
        if (confirm) {
            val ledgerId = "transferencia-${message.id}"
            require(snapshot().ledger.none { it.sourceType == "TRANSFERENCIA" && it.sourceId == message.id }) { "La transferencia ya fue confirmada" }
            val updated = message.copy(status = "CONFIRMADO", confirmedByUserId = actor.id, confirmedAt = now, ledgerEntryId = ledgerId, updatedAt = now, syncStatus = SYNC_PENDING)
            val ledger = LedgerEntry(
                id = ledgerId,
                sourceType = "TRANSFERENCIA",
                sourceId = message.id,
                sourceCode = message.code,
                userId = message.recipientUserId,
                username = message.recipientName,
                direction = "INGRESO",
                amount = message.amount,
                occurredAt = message.transferDate.ifBlank { now },
                createdAt = now,
                updatedAt = now,
                createdByUserId = actor.id,
                createdBy = actor.name,
                reason = message.reason.ifBlank { "Transferencia confirmada" }
            )
            db.transaction {
                db.put(TYPE_MESSAGES, id, updated.toJson(), SYNC_PENDING, now)
                db.put(TYPE_LEDGER, ledgerId, ledger.toJson(), SYNC_PENDING, now)
                if (message.sourceRequestId.isNotBlank()) {
                    db.get(TYPE_MESSAGES, message.sourceRequestId)?.payload?.let(Message::fromJson)?.let { request ->
                        val completed = request.copy(status = "COMPLETADA", updatedAt = now, syncStatus = SYNC_PENDING)
                        db.put(TYPE_MESSAGES, request.id, completed.toJson(), SYNC_PENDING, now)
                    }
                }
            }
            audit("MENSAJE", id, "CONFIRMAR_TRANSFERENCIA", message.code)
        } else {
            require(rejectReason.trim().length >= 3) { "Indique el motivo del rechazo" }
            val updated = message.copy(status = "RECHAZADO", rejectedReason = rejectReason.trim(), updatedAt = now, syncStatus = SYNC_PENDING)
            db.transaction {
                db.put(TYPE_MESSAGES, id, updated.toJson(), SYNC_PENDING, now)
                if (message.sourceRequestId.isNotBlank()) {
                    db.get(TYPE_MESSAGES, message.sourceRequestId)?.payload?.let(Message::fromJson)?.let { request ->
                        val reopened = request.copy(
                            status = "PENDIENTE",
                            responseTransferId = "",
                            respondedByUserId = "",
                            respondedAt = "",
                            updatedAt = now,
                            syncStatus = SYNC_PENDING
                        )
                        db.put(TYPE_MESSAGES, request.id, reopened.toJson(), SYNC_PENDING, now)
                    }
                }
            }
            audit("MENSAJE", id, "RECHAZAR_TRANSFERENCIA", updated.rejectedReason)
        }
    }

    fun adjustment(userId: String, direction: String, amount: Double, reason: String) {
        val snap = snapshot()
        require(snap.isAdmin()) { "Solo ADMIN" }
        val user = snap.users.firstOrNull { it.id == userId } ?: error("Usuario")
        require(amount > 0 && amount.isFinite()) { "Monto inválido" }
        require(reason.trim().length >= 3) { "Indique el motivo" }
        val now = TimeUtils.nowIso()
        val id = CodeUtils.uuid()
        val ledger = LedgerEntry(
            id, "AJUSTE", id, "AJU-${id.take(6).uppercase()}", user.id, user.name,
            if (direction == "INGRESO") "INGRESO" else "EGRESO", amount, now, now, now,
            snap.currentUser()!!.id, snap.currentUser()!!.name, reason.trim()
        )
        db.put(TYPE_LEDGER, id, ledger.toJson(), SYNC_PENDING, now)
        audit("LIBRO", id, "AJUSTAR", ledger.reason)
    }

    fun reverseLedger(entryId: String, reason: String) {
        val snap = snapshot()
        require(snap.isAdmin()) { "Solo ADMIN" }
        val old = snap.ledger.firstOrNull { it.id == entryId } ?: error("Movimiento")
        require(old.status == "PUBLICADO" && old.sourceType != "REVERSO") { "No reversible" }
        require(snap.ledger.none { it.sourceType == "REVERSO" && it.sourceId == old.id }) { "Ese movimiento ya fue reversado" }
        require(reason.trim().length >= 3) { "Indique el motivo" }
        val now = TimeUtils.nowIso()
        val id = "reverso-${old.id}-${CodeUtils.uuid().take(6)}"
        val ledger = LedgerEntry(
            id, "REVERSO", old.id, "REV-${old.sourceCode}", old.userId, old.username,
            if (old.direction == "INGRESO") "EGRESO" else "INGRESO", old.amount, now, now, now,
            snap.currentUser()!!.id, snap.currentUser()!!.name, reason.trim(), reversalOf = old.id
        )
        db.put(TYPE_LEDGER, id, ledger.toJson(), SYNC_PENDING, now)
        audit("LIBRO", old.id, "REVERSAR", ledger.reason)
    }

    fun deleteLedgerPermanently(entryId: String): Boolean {
        val snap = snapshot(); require(snap.isAdmin()) { "Solo ADMIN puede borrar movimientos contables" }
        val row = db.get(TYPE_LEDGER, entryId) ?: error("Movimiento no encontrado")
        val entry = LedgerEntry.fromJson(row.payload)
        val (supabaseConfig, session) = sync.ensureSession()
        require(supabase.canPhysicalCleanup3152(supabaseConfig, session)) {
            "Ejecuta supabase/migration-3.15.2.sql para habilitar el borrado físico"
        }
        val remote = supabase.adminDeleteEntity(supabaseConfig, session, TYPE_LEDGER, entry.id)
        db.delete(TYPE_LEDGER, entry.id)
        return remote
    }

    fun syncNow(): SyncEngine.Result {
        val result = sync.sync()
        dedupeUsers()
        migrateOfficialExcelCatalogs()
        migrateOfficialExcelForm()
        state.session()?.let { bindBySession(it) }
        return result
    }

    fun testConnection() = sync.testConnection()
    fun testStorageConnection(): StorageTestResult = sync.testStorageConnection(snapshot().config.storage.provider)
    fun evidenceBytes(attachment: Attachment) = sync.downloadEvidence(attachment)

    fun retryFailedStorage(): SyncEngine.Result {
        val snap = snapshot()
        require(snap.currentUser() != null) { "Inicia sesión para reintentar evidencias" }
        val now = TimeUtils.nowIso()
        var queued = 0
        listOf(TYPE_TRANSACTIONS, TYPE_MESSAGES).forEach { type ->
            db.all(type).forEach rowLoop@{ row ->
                val payload = JSONObject(row.payload.toString())
                val attachments = payload.optJSONArray("attachments") ?: return@rowLoop
                var changed = false
                for (i in 0 until attachments.length()) {
                    val item = attachments.optJSONObject(i) ?: continue
                    if (item.optString("storageStatus") != STORAGE_FAILED) continue
                    val path = item.optString("localPath")
                    if (path.isBlank() || !File(path).exists()) continue
                    item.put("storageStatus", STORAGE_PENDING)
                    item.put("storageError", "")
                    changed = true
                    queued++
                }
                if (changed) {
                    payload.put("attachments", attachments)
                    payload.put("syncError", "")
                    db.put(type, row.id, payload, SYNC_PENDING, now)
                }
            }
        }
        require(queued > 0) { "No hay evidencias fallidas con copia local disponible para reintentar" }
        return syncNow()
    }

    fun storageUsage(s: AppSnapshot = snapshot()): StorageUsageSummary {
        val attachments = buildList {
            s.transactions.forEach { addAll(it.attachments) }
            s.messages.forEach { addAll(it.attachments) }
        }.distinctBy { it.id }
        val supabase = attachments.filter { it.storageProvider.ifBlank { if (it.storageKey.isNotBlank() || it.remotePath.isNotBlank()) STORAGE_SUPABASE else "" } == STORAGE_SUPABASE }
        val r2 = attachments.filter { it.storageProvider == STORAGE_R2 }
        return StorageUsageSummary(
            totalFiles = attachments.size,
            totalBytes = attachments.sumOf { it.size.coerceAtLeast(0) },
            supabaseFiles = supabase.size,
            supabaseBytes = supabase.sumOf { it.size.coerceAtLeast(0) },
            r2Files = r2.size,
            r2Bytes = r2.sumOf { it.size.coerceAtLeast(0) },
            pendingFiles = attachments.count { it.storageStatus == STORAGE_PENDING || it.storageStatus == STORAGE_UPLOADING },
            failedFiles = attachments.count { it.storageStatus == STORAGE_FAILED }
        )
    }


    private data class AttachmentCleanup(
        val filesDeleted: Int,
        val bytesFreed: Long,
        val warnings: List<String>
    )

    private fun cleanupAttachments(
        attachments: List<Attachment>,
        storageConfig: StorageConfig,
        supabaseConfig: SupabaseConfig,
        session: AuthSession
    ): AttachmentCleanup {
        var filesDeleted = 0
        var bytesFreed = 0L
        val warnings = mutableListOf<String>()
        val remoteObjects = mutableSetOf<String>()

        attachments.forEach { attachment ->
            val key = attachment.storageKey.ifBlank { attachment.remotePath }.trim()
            if (key.isBlank()) return@forEach
            val provider = attachment.storageProvider.ifBlank { STORAGE_SUPABASE }
            if (!remoteObjects.add("$provider:$key")) return@forEach
            val service = StorageServiceFactory.create(provider, supabase, supabaseConfig, session, storageConfig)
            try {
                service.delete(key)
                filesDeleted++
                bytesFreed += attachment.size.coerceAtLeast(0)
            } catch (e: Exception) {
                warnings += "${attachment.name}: ${e.message ?: "no se pudo liberar el archivo remoto"}"
            }
        }

        attachments.filter { it.localPath.isNotBlank() }.distinctBy { it.localPath }.forEach { attachment ->
            val file = File(attachment.localPath)
            if (file.exists()) {
                if (attachment.storageKey.isBlank() && attachment.remotePath.isBlank()) {
                    bytesFreed += file.length().coerceAtLeast(0)
                    filesDeleted++
                }
                runCatching { file.delete() }
                runCatching { file.parentFile?.takeIf { dir -> dir.isDirectory && dir.list().isNullOrEmpty() }?.delete() }
            }
        }
        return AttachmentCleanup(filesDeleted, bytesFreed, warnings)
    }

    fun adminSpaceReport(): AdminSpaceReport {
        val snap = snapshot()
        require(snap.isAdmin()) { "Solo ADMIN puede analizar el espacio" }
        val (supabaseConfig, session) = sync.ensureSession()
        require(supabase.canAdminCleanup(supabaseConfig, session)) {
            "La administración de espacio no está habilitada. Ejecuta supabase/migration-3.10.0.sql."
        }

        val database = supabase.adminSpaceReport(supabaseConfig, session)
        val storageConfig = snap.config.storage
        val references = linkedSetOf<String>()
        val referencedAttachments = buildList {
            snap.transactions.forEach { addAll(it.attachments) }
            snap.messages.forEach { addAll(it.attachments) }
        }.distinctBy { it.id }

        referencedAttachments.forEach { attachment ->
            val key = attachment.storageKey.ifBlank { attachment.remotePath }.trim()
            if (key.isNotBlank()) {
                val provider = attachment.storageProvider.ifBlank { STORAGE_SUPABASE }
                references += "$provider:$key"
            }
        }
        snap.config.excelTemplate.takeIf { it.enabled }?.let { template ->
            val key = template.storageKey.ifBlank { template.remotePath }.trim()
            if (key.isNotBlank()) references += "${template.storageProvider.ifBlank { STORAGE_SUPABASE }}:$key"
        }

        val remote = mutableListOf<com.skcingenieria.facturas.network.StorageObjectMetadata>()
        val warnings = mutableListOf<String>()
        StorageServiceFactory.supportedProviders().forEach { provider ->
            runCatching {
                val service = StorageServiceFactory.create(provider, supabase, supabaseConfig, session, storageConfig)
                val prefix = if (provider == STORAGE_R2) "" else storageConfig.basePrefix
                service.list(prefix)
            }.onSuccess { remote += it }
                .onFailure { e ->
                    val label = if (provider == STORAGE_R2) "Cloudflare R2" else "Supabase Storage"
                    warnings += "$label: ${e.message ?: "no se pudo inventariar"}"
                }
        }

        val uniqueRemote = remote.distinctBy { "${it.provider}:${it.key}" }
        val orphans = uniqueRemote.filter { objectMeta ->
            "${objectMeta.provider}:${objectMeta.key}" !in references
        }
        return AdminSpaceReport(
            databaseRows = database.optInt("rows", 0),
            databaseTableBytes = database.optLong("tableBytes", 0L),
            databasePayloadBytes = database.optLong("payloadBytes", 0L),
            tombstones = database.optInt("tombstones", 0),
            referencedFiles = referencedAttachments.size,
            referencedBytes = referencedAttachments.sumOf { it.size.coerceAtLeast(0) },
            remoteFiles = uniqueRemote.size,
            remoteBytes = uniqueRemote.sumOf { it.sizeBytes.coerceAtLeast(0) },
            orphanFiles = orphans.size,
            orphanBytes = orphans.sumOf { it.sizeBytes.coerceAtLeast(0) },
            orphanObjects = orphans,
            warnings = warnings
        )
    }

    fun deleteOrphanedStorage(): AdminCleanupResult {
        val snap = snapshot()
        require(snap.isAdmin()) { "Solo ADMIN puede eliminar archivos huérfanos" }
        val report = adminSpaceReport()
        if (report.orphanObjects.isEmpty()) return AdminCleanupResult(warnings = report.warnings)

        val (supabaseConfig, session) = sync.ensureSession()
        val storageConfig = snap.config.storage
        var files = 0
        var bytes = 0L
        val warnings = report.warnings.toMutableList()
        report.orphanObjects.forEach { objectMeta ->
            try {
                StorageServiceFactory.create(objectMeta.provider, supabase, supabaseConfig, session, storageConfig)
                    .delete(objectMeta.key)
                files++
                bytes += objectMeta.sizeBytes.coerceAtLeast(0)
            } catch (e: Exception) {
                warnings += "${objectMeta.provider}:${objectMeta.key}: ${e.message ?: "no se pudo eliminar"}"
            }
        }
        audit("ALMACENAMIENTO", "orphan-cleanup", "LIMPIAR_HUERFANOS", "archivos=$files · bytes=$bytes · advertencias=${warnings.size}")
        return AdminCleanupResult(filesDeleted = files, bytesFreed = bytes, warnings = warnings)
    }

    private fun createPreferredSafetyBackupIfConfigured(): String {
        val providers = state.settings().infrastructure.backupProviders.filter { it in setOf(BACKUP_GOOGLE_DRIVE, BACKUP_ONEDRIVE, BACKUP_VPS) }
        if (providers.isEmpty()) return ""
        val labels = providers.map { provider ->
            val backup = createExternalBackup(provider)
            "$provider · ${backup.entities} registros"
        }
        return labels.joinToString(" + ")
    }

    fun backupAndDeleteMessage(messageId: String): AdminCleanupResult {
        val snap = snapshot()
        require(snap.isAdmin()) { "Solo ADMIN puede eliminar transferencias o solicitudes" }
        // La sincronización previa es útil, pero una foto fallida o un registro local pendiente
        // no debe impedir que ADMIN elimine el movimiento. La RPC física decide el estado remoto.
        runCatching { sync.sync() }
        db.get(TYPE_MESSAGES, messageId) ?: error("El registro ya no existe")
        val backupLabel = createPreferredSafetyBackupIfConfigured()
        val result = deleteMessagePermanently(messageId)
        return result.copy(backupFileName = backupLabel)
    }

    fun deleteMessagePermanently(messageId: String): AdminCleanupResult {
        val snap = snapshot()
        require(snap.isAdmin()) { "Solo ADMIN puede eliminar transferencias o solicitudes" }
        val row = db.get(TYPE_MESSAGES, messageId) ?: error("El registro ya no existe")
        val message = Message.fromJson(row.payload)
        val (supabaseConfig, session) = sync.ensureSession()
        require(supabase.canPhysicalCleanup3152(supabaseConfig, session)) {
            "Ejecuta supabase/migration-3.15.2.sql para habilitar el borrado físico"
        }

        // Usa la RPC específica para garantizar DELETE real incluso si una instalación antigua
        // todavía conserva skc_admin_delete_entity con comportamiento de tombstone.
        val remoteDeleted = supabase.deleteMessagePhysically(supabaseConfig, session, message.id)
        val cleaned = cleanupAttachments(message.attachments, snap.config.storage, supabaseConfig, session)
        db.delete(TYPE_MESSAGES, message.id)
        return AdminCleanupResult(
            recordsDeleted = 1,
            filesDeleted = cleaned.filesDeleted,
            bytesFreed = cleaned.bytesFreed,
            warnings = cleaned.warnings
        )
    }

    fun deleteHistorySelection(transactionIds: Set<String>, messageIds: Set<String>): AdminCleanupResult {
        val snap = snapshot()
        require(snap.isAdmin()) { "Solo ADMIN puede eliminar movimientos" }
        require(transactionIds.isNotEmpty() || messageIds.isNotEmpty()) { "Selecciona al menos un movimiento" }
        val preSyncWarning = runCatching { sync.sync() }.exceptionOrNull()?.message
        val (supabaseConfig, session) = sync.ensureSession()
        require(supabase.canPhysicalCleanup3152(supabaseConfig, session)) {
            "Ejecuta supabase/migration-3.15.2.sql para habilitar el borrado físico del historial"
        }
        // No se exige SYNCED: un movimiento local pendiente también puede eliminarse. Si nunca
        // llegó al servidor la RPC devuelve false y la copia local/evidencias igualmente se limpian.
        val backupLabel = createPreferredSafetyBackupIfConfigured()
        var records = 0
        var files = 0
        var bytes = 0L
        val warnings = mutableListOf<String>().apply {
            if (!preSyncWarning.isNullOrBlank()) add("Sincronización previa: $preSyncWarning")
        }
        transactionIds.forEach { id ->
            val ledgerId = db.get(TYPE_TRANSACTIONS, id)?.payload?.let(Transaction::fromJson)?.ledgerEntryId.orEmpty()
            runCatching { deleteInvoicePermanently(id) }
                .onSuccess { r ->
                    records++; files += r.filesDeleted; bytes += r.bytesFreed; warnings += r.cleanupWarnings
                    if (ledgerId.isNotBlank()) runCatching { deleteLedgerPermanently(ledgerId) }
                        .onSuccess { records++ }
                        .onFailure { warnings += "Libro $ledgerId: ${it.message ?: "no se pudo eliminar"}" }
                }
                .onFailure { warnings += it.message ?: id }
        }
        messageIds.forEach { id ->
            val ledgerId = db.get(TYPE_MESSAGES, id)?.payload?.let(Message::fromJson)?.ledgerEntryId.orEmpty()
            runCatching { deleteMessagePermanently(id) }
                .onSuccess { r ->
                    records += r.recordsDeleted; files += r.filesDeleted; bytes += r.bytesFreed; warnings += r.warnings
                    if (ledgerId.isNotBlank()) runCatching { deleteLedgerPermanently(ledgerId) }
                        .onSuccess { records++ }
                        .onFailure { warnings += "Libro $ledgerId: ${it.message ?: "no se pudo eliminar"}" }
                }
                .onFailure { warnings += it.message ?: id }
        }
        return AdminCleanupResult(records, files, bytes, backupLabel, warnings)
    }

    private fun instantOrEpoch(value: String): Instant = runCatching { OffsetDateTime.parse(value).toInstant() }
        .recoverCatching { Instant.parse(value) }
        .recoverCatching { LocalDate.parse(value.take(10)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant() }
        .getOrDefault(Instant.EPOCH)

    fun cleanupOldRecords(category: String, olderThanDays: Int): AdminCleanupResult {
        val snap = snapshot()
        require(snap.isAdmin()) { "Solo ADMIN puede hacer limpieza masiva" }
        require(olderThanDays >= 30) { "La limpieza masiva requiere al menos 30 días de antigüedad" }
        runCatching { sync.sync() }.getOrElse { e ->
            throw IllegalStateException("No se pudo sincronizar antes de limpiar. ${e.message.orEmpty()}".trim())
        }
        val cutoff = Instant.now().minus(olderThanDays.toLong(), ChronoUnit.DAYS)
        fun older(value: String): Boolean = instantOrEpoch(value) < cutoff

        val needsBackup = category == CLEANUP_TRANSACTIONS || category == CLEANUP_MESSAGES
        var backupLabel = ""
        if (needsBackup) {
            backupLabel = createPreferredSafetyBackupIfConfigured()
        }

        var records = 0
        var files = 0
        var bytes = 0L
        val warnings = mutableListOf<String>()
        when (category) {
            CLEANUP_TRANSACTIONS -> {
                snapshot().transactions.filter { older(it.updatedAt.ifBlank { it.createdAt }) }.forEach { tx ->
                    runCatching { deleteInvoicePermanently(tx.id) }
                        .onSuccess { result ->
                            records++
                            files += result.filesDeleted
                            bytes += result.bytesFreed
                            warnings += result.cleanupWarnings
                        }
                        .onFailure { e -> warnings += "${tx.code}: ${e.message ?: "no se pudo eliminar"}" }
                }
            }
            CLEANUP_MESSAGES -> {
                snapshot().messages
                    .filter { older(it.updatedAt.ifBlank { it.createdAt }) }
                    .filter { it.status.uppercase() !in setOf("ENVIADO", "PENDIENTE") }
                    .forEach { msg ->
                        runCatching { deleteMessagePermanently(msg.id) }
                            .onSuccess { result ->
                                records += result.recordsDeleted
                                files += result.filesDeleted
                                bytes += result.bytesFreed
                                warnings += result.warnings
                            }
                            .onFailure { e -> warnings += "${msg.code}: ${e.message ?: "no se pudo eliminar"}" }
                    }
            }
            CLEANUP_REMINDERS, CLEANUP_AUDIT -> {
                val type = if (category == CLEANUP_REMINDERS) TYPE_REMINDERS else TYPE_AUDIT
                val (supabaseConfig, session) = sync.ensureSession()
                require(supabase.canAdminCleanup(supabaseConfig, session)) {
                    "Ejecuta supabase/migration-3.10.0.sql antes de usar la limpieza administrativa"
                }
                db.all(type).filter { row ->
                    older(row.updatedAt) && (type != TYPE_REMINDERS || row.payload.optString("status").uppercase() != "PENDIENTE")
                }.forEach { row ->
                    runCatching {
                        supabase.adminDeleteEntity(supabaseConfig, session, type, row.id)
                        db.delete(type, row.id)
                    }.onSuccess { records++ }
                        .onFailure { e -> warnings += "${row.id}: ${e.message ?: "no se pudo eliminar"}" }
                }
                audit("MANTENIMIENTO", category, "LIMPIEZA_MASIVA", "registros=$records · antigüedad=${olderThanDays}d · advertencias=${warnings.size}")
            }
            else -> error("Categoría de limpieza no válida")
        }
        return AdminCleanupResult(records, files, bytes, backupLabel, warnings)
    }

    fun purgeTombstones(olderThanDays: Int): Int {
        val snap = snapshot()
        require(snap.isAdmin()) { "Solo ADMIN puede vaciar la papelera técnica" }
        require(olderThanDays >= 30) { "Conserva las eliminaciones técnicas al menos 30 días" }
        val (supabaseConfig, session) = sync.ensureSession()
        val count = supabase.purgeAdminTombstones(supabaseConfig, session, olderThanDays)
        audit("MANTENIMIENTO", "tombstones", "PURGAR_PAPELERA", "eliminados=$count · antigüedad=${olderThanDays}d")
        return count
    }

    fun databaseDiagnostics(): DatabaseDiagnostics {
        val snap = snapshot()
        val cfg = snap.settings.supabase
        val base = cfg.projectUrl.trim().trimEnd('/').removeSuffix("/rest/v1")
        val storage = snap.config.storage
        val storageTarget = when (storage.provider) {
            STORAGE_R2 -> "Edge Function: ${storage.r2FunctionName}"
            else -> "Bucket: ${storage.supabaseBucket}"
        }
        return DatabaseDiagnostics(
            provider = snap.settings.infrastructure.primaryDataProvider,
            projectUrl = base,
            restEndpoint = base.takeIf { it.isNotBlank() }?.plus("/rest/v1").orEmpty(),
            authEndpoint = base.takeIf { it.isNotBlank() }?.plus("/auth/v1").orEmpty(),
            eventsTable = "public.skc_events",
            publishableKey = cfg.publishableKey,
            localDatabase = "skc_facturas.db",
            localEntityTable = "entities",
            authenticatedEmail = snap.session?.email.orEmpty(),
            authenticatedUserId = snap.session?.authUserId.orEmpty(),
            autoSync = cfg.autoSync,
            pollSeconds = cfg.pollSeconds,
            lastSyncAt = cfg.lastSyncAt,
            storageProvider = storage.provider,
            storageTarget = storageTarget,
            backupApiUrl = snap.settings.externalBackup.vpsBaseUrl
        )
    }

    /**
     * Permanent invoice cleanup for ADMIN maintenance.
     *
     * The invoice record and its evidence are removed, while the ledger entry is kept
     * so deleting old documents does not rewrite historical balances.
     */
    fun deleteInvoicePermanently(transactionId: String): InvoiceDeleteResult {
        val snap = snapshot()
        require(snap.isAdmin()) { "Solo ADMIN puede eliminar facturas definitivamente" }
        val row = db.get(TYPE_TRANSACTIONS, transactionId) ?: error("La factura ya no existe")
        val transaction = Transaction.fromJson(row.payload)
        val (supabaseConfig, session) = sync.ensureSession()
        val storageConfig = snap.config.storage

        val deletionReady = try {
            supabase.canPhysicalCleanup3152(supabaseConfig, session)
        } catch (e: Exception) {
            throw IllegalStateException(
                "La limpieza física 3.15.2 todavía no está habilitada en Supabase. Ejecuta supabase/migration-3.15.2.sql. ${e.message.orEmpty()}".trim()
            )
        }
        require(deletionReady) { "El servidor no autorizó la limpieza física para esta cuenta ADMIN" }

        var filesDeleted = 0
        var bytesFreed = 0L
        val cleanupWarnings = mutableListOf<String>()
        val remoteObjects = mutableSetOf<String>()

        // 3.15.2 elimina primero el registro remoto de forma física. El motor de sincronización
        // reconcilia después por ausencia. El respaldo remoto opcional se crea antes de llegar aquí.
        val remoteRecordDeleted = supabase.deleteInvoice(supabaseConfig, session, transaction.id)

        // Once the database deletion is durable, free evidence storage. A storage cleanup failure
        // does not resurrect the invoice; it is reported explicitly so it can be handled later.
        transaction.attachments.forEach { attachment ->
            val key = attachment.storageKey.ifBlank { attachment.remotePath }.trim()
            if (key.isBlank()) return@forEach
            val provider = attachment.storageProvider.ifBlank { storageConfig.provider }
            val identity = "$provider:$key"
            if (!remoteObjects.add(identity)) return@forEach

            val service = StorageServiceFactory.create(provider, supabase, supabaseConfig, session, storageConfig)
            try {
                service.delete(key)
                filesDeleted++
                bytesFreed += attachment.size.coerceAtLeast(0)
            } catch (e: Exception) {
                cleanupWarnings += "${attachment.name}: ${e.message ?: "no se pudo liberar el archivo remoto"}"
            }
        }

        transaction.attachments
            .filter { it.localPath.isNotBlank() }
            .distinctBy { it.localPath }
            .forEach { attachment ->
                val file = File(attachment.localPath)
                if (file.exists()) {
                    if (attachment.storageKey.isBlank() && attachment.remotePath.isBlank()) {
                        bytesFreed += file.length().coerceAtLeast(0)
                        filesDeleted++
                    }
                    runCatching { file.delete() }
                    runCatching { file.parentFile?.takeIf { dir -> dir.isDirectory && dir.list().isNullOrEmpty() }?.delete() }
                }
            }

        db.delete(TYPE_TRANSACTIONS, transaction.id)
        return InvoiceDeleteResult(transaction.id, filesDeleted, bytesFreed, remoteRecordDeleted, cleanupWarnings = cleanupWarnings)
    }

    private fun excelTemplateFile(config: ExcelTemplateConfig): File {
        val key = config.updatedAt.ifBlank { config.remotePath.ifBlank { config.fileName } }.hashCode().toUInt().toString(16)
        return File(context.filesDir, "excel-templates/$key.xlsx")
    }

    fun saveExcelTemplate(bytes: ByteArray, draft: ExcelTemplateConfig) {
        val snap = snapshot()
        require(snap.isAdmin()) { "Solo ADMIN puede cambiar el formato Excel" }
        require(bytes.isNotEmpty()) { "El archivo Excel está vacío" }
        ExcelTemplateEngine.validate(draft)

        val inspection = ExcelTemplateEngine.inspect(bytes, draft.fileName.ifBlank { "formato.xlsx" })
        require(inspection.sheets.any { it.name == draft.sheetName }) { "La hoja seleccionada no existe en el archivo" }

        val now = TimeUtils.nowIso()
        var storageKey = ""
        var storageProvider = ""
        if (snap.settings.supabase.projectUrl.isNotBlank()) {
            require(snap.session != null) { "Inicia sesión en Supabase antes de publicar un formato Excel compartido" }
            val safeStamp = now.replace(Regex("[^0-9A-Za-z]"), "-")
            storageProvider = snap.config.storage.provider
            val owner = snap.session.authUserId
            require(owner.isNotBlank()) { "La sesión de Supabase no contiene un UID válido" }
            storageKey = "${snap.config.storage.basePrefix.trim('/')}/$owner/excel-templates/$safeStamp.xlsx"
            val tempUpload = File.createTempFile("skc-excel-", ".xlsx", context.cacheDir)
            try {
                tempUpload.writeBytes(bytes)
                sync.uploadStorageFile(
                    tempUpload, storageKey,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    FileUtils.sha256(tempUpload), storageProvider
                )
            } finally {
                tempUpload.delete()
            }
        }

        val active = draft.copy(enabled = true, remotePath = storageKey, storageProvider = storageProvider, storageKey = storageKey, updatedAt = now)
        val file = excelTemplateFile(active)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeBytes(bytes)
        if (file.exists()) file.delete()
        require(temp.renameTo(file)) { "No se pudo guardar el formato Excel en el dispositivo" }

        // Rebase on the latest config because uploading a template can take long enough for
        // another setting (for example Forms) to change while this operation is in progress.
        saveAppConfig(latestAppConfig().copy(excelTemplate = active))
        audit("CONFIGURACION", "excelTemplate", "CAMBIAR_FORMATO_EXCEL", "${active.fileName} · ${active.sheetName}")
    }

    fun disableExcelTemplate() {
        val snap = snapshot()
        require(snap.isAdmin()) { "Solo ADMIN puede cambiar el formato Excel" }
        saveAppConfig(latestAppConfig().copy(excelTemplate = ExcelTemplateConfig()))
        audit("CONFIGURACION", "excelTemplate", "RESTAURAR_FORMATO_EXCEL", "Formato predeterminado")
    }

    private fun loadExcelTemplate(config: ExcelTemplateConfig): ByteArray? {
        if (!config.enabled) return null
        val file = excelTemplateFile(config)
        if (file.exists()) return file.readBytes()
        val key = config.storageKey.ifBlank { config.remotePath }
        require(key.isNotBlank()) { "El formato Excel activo no está disponible en este dispositivo. Vuelve a subirlo desde Configuración." }
        val provider = config.storageProvider.ifBlank { STORAGE_SUPABASE }
        val bytes = if (provider == STORAGE_SUPABASE && key.startsWith("formatos/")) {
            sync.downloadLegacySupabaseObject("skc-excel-templates", key)
        } else {
            sync.downloadStorage(key, provider)
        }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return bytes
    }

    private fun defaultExcelTemplate(): ByteArray? = runCatching {
        context.assets.open("RE-S11.01_FLUJO_FM_2026.xlsx").use { it.readBytes() }
    }.getOrNull()

    fun excelRows(s: AppSnapshot = snapshot()): List<Transaction> {
        val rows = visibleTransactions(s).filter { it.recordStatus != "REVOCADA" }.toMutableList()
        if (!s.isAdmin() || !s.config.includeInternalTransfersInExcel) return rows
        val knownIds = rows.map { it.id }.toHashSet()
        s.ledger.asSequence()
            .filter { it.sourceType == "TRANSFERENCIA" && it.direction == "INGRESO" && it.status == "PUBLICADO" }
            .filter { ledger -> rows.none { it.extraFields["sourceTransferId"] == ledger.sourceId } }
            .map { ledger ->
                val message = s.messages.firstOrNull { it.id == ledger.sourceId }
                val recipient = s.users.firstOrNull { it.id == ledger.userId }
                val date = message?.transferDate?.takeIf { it.isNotBlank() }
                    ?: ledger.occurredAt.take(10).takeIf { it.length == 10 }
                    ?: TimeUtils.todayIso()
                Transaction(
                    id = "excel-transfer-${ledger.id}",
                    code = ledger.sourceCode,
                    movementType = "Ingreso",
                    purchaseDate = date,
                    descriptionBase = "Transferencia interna",
                    project = message?.project.orEmpty(),
                    description = ledger.reason.ifBlank { "Transferencia interna" },
                    area = "",
                    liquidationType = "Transferencia interna",
                    amount = ledger.amount,
                    accountUserId = ledger.userId,
                    accountUsername = ledger.username,
                    createdByUserId = message?.senderUserId ?: ledger.createdByUserId,
                    createdByName = message?.senderName ?: ledger.createdBy,
                    groupName = s.config.groupName,
                    createdAt = ledger.createdAt,
                    updatedAt = ledger.updatedAt,
                    userEmail = recipient?.email.orEmpty(),
                    extraFields = mapOf(
                        "sourceTransferId" to ledger.sourceId,
                        "transferSender" to (message?.senderName ?: ledger.createdBy),
                        "transferReference" to message?.transferReference.orEmpty()
                    )
                )
            }
            .filter { knownIds.add(it.id) }
            .forEach(rows::add)
        return rows
    }

    fun invoiceExcelExport(): Pair<String, ByteArray> {
        val snap = snapshot()
        require(snap.currentUser() != null) { "Inicia sesión" }
        val rows = visibleTransactions(snap).filter { it.recordStatus != "REVOCADA" }
        val bytes = ExcelExporter.buildInvoicesDynamic(rows, snap.config.fields("invoice"))
        return "SKC_Facturas_${TimeUtils.todayIso()}.xlsx" to bytes
    }

    fun transferExcelExport(): Pair<String, ByteArray> {
        val snap = snapshot()
        require(snap.currentUser() != null) { "Inicia sesión" }
        val bytes = ExcelExporter.buildTransfersDynamic(
            visibleMessages(snap).filter { it.messageType == "TRANSFERENCIA" },
            snap.config.fields("transfer")
        )
        return "SKC_Transferencias_${TimeUtils.todayIso()}.xlsx" to bytes
    }

    fun excelExport(): Pair<String, ByteArray> = invoiceExcelExport()

    fun visibleTransactions(s: AppSnapshot = snapshot()): List<Transaction> {
        val user = s.currentUser() ?: return emptyList()
        return if (s.isAdmin()) s.transactions else s.transactions.filter { it.createdByUserId == user.id || it.accountUserId == user.id }
    }

    fun visibleLedger(s: AppSnapshot = snapshot()): List<LedgerEntry> {
        val user = s.currentUser() ?: return emptyList()
        return if (s.isAdmin()) s.ledger else s.ledger.filter { it.userId == user.id }
    }

    fun myLedger(s: AppSnapshot = snapshot()): List<LedgerEntry> {
        val user = s.currentUser() ?: return emptyList()
        return s.ledger.filter { it.userId == user.id }
    }

    fun visibleMessages(s: AppSnapshot = snapshot()): List<Message> {
        val user = s.currentUser() ?: return emptyList()
        return if (s.isAdmin()) s.messages else s.messages.filter { it.senderUserId == user.id || it.recipientUserId == user.id }
    }

    fun visibleReminders(s: AppSnapshot = snapshot()): List<Reminder> {
        val user = s.currentUser() ?: return emptyList()
        return if (s.isAdmin()) s.reminders else s.reminders.filter { it.userId == user.id }
    }

    fun visibleAudit(s: AppSnapshot = snapshot()): List<AuditEntry> = if (s.isAdmin()) s.audit else emptyList()

    fun exportCatalogs(): String {
        val snap = snapshot()
        require(snap.isAdmin()) { "Solo ADMIN puede exportar catálogos" }
        return JSONObject().apply {
            put("format", "skc-catalogs")
            put("schemaVersion", DATA_SCHEMA_VERSION)
            put("appVersion", APP_VERSION)
            put("createdAt", TimeUtils.nowIso())
            put("catalogs", snap.catalogs.toJson())
        }.toString(2)
    }

    fun importCatalogs(text: String) {
        val snap = snapshot()
        require(snap.isAdmin()) { "Solo ADMIN puede importar catálogos" }
        val root = JSONObject(text)
        val payload = root.optJSONObject("catalogs") ?: root
        saveCatalogs(Catalogs.fromJson(payload))
        migrateOfficialExcelCatalogs()
        migrateBundledSuppliers()
    }

    fun exportConfig(): String {
        val snap = snapshot()
        val providerTokens = JSONObject().apply {
            listOf(
                DATA_BACKEND_VPS_API,
                DATA_BACKEND_POSTGRES_API,
                DATA_BACKEND_MYSQL_API,
                DATA_BACKEND_MARIADB_API,
                DATA_BACKEND_SQLSERVER_API,
                DATA_BACKEND_GENERIC_REST
            ).forEach { provider -> put(provider, state.backendApiToken(provider)) }
        }
        val assets = JSONObject()
        val template = snap.config.excelTemplate
        val templateFile = excelTemplateFile(template)
        if (template.enabled && templateFile.exists() && templateFile.isFile) {
            assets.put("excelTemplateFile", JSONObject().apply {
                put("fileName", template.fileName.ifBlank { templateFile.name })
                put("dataBase64", Base64.encodeToString(templateFile.readBytes(), Base64.NO_WRAP))
            })
        }

        return JSONObject().apply {
            put("format", "skc-app-settings")
            put("schemaVersion", 5)
            put("appVersion", APP_VERSION)
            put("createdAt", TimeUtils.nowIso())

            // Copias completas para que cualquier campo de configuración actual sea portable.
            put("appConfig", snap.config.toJson())
            put("localSettings", snap.settings.toJson())
            put("catalogs", snap.catalogs.toJson())
            put("credentials", JSONObject().apply {
                put("vpsBackupToken", state.vpsBackupToken())
                put("backendApiToken", state.backendApiToken())
                put("backendApiTokens", providerTokens)
            })
            put("assets", assets)

            // Secciones legibles conservadas para compatibilidad con configuraciones 3.9-3.12.
            put("primaryData", JSONObject().apply {
                put("provider", snap.settings.infrastructure.primaryDataProvider)
                put("providers", snap.settings.infrastructure.primaryDataProviders.toJsonArray())
                put("secondaryProvider", snap.settings.infrastructure.secondaryDataProvider)
                put("supabase", snap.settings.supabase.toJson())
                put("alternateBackend", snap.settings.alternateBackend.toJson())
                put("backendConnections", JSONArray().also { array ->
                    snap.settings.backendConnections.forEach { cfg -> array.put(cfg.toJson()) }
                })
            })
            put("evidenceStorage", snap.config.storage.toJson())
            put("backup", JSONObject().apply {
                put("preferredProvider", snap.settings.infrastructure.preferredBackupProvider)
                put("providers", snap.settings.infrastructure.backupProviders.toJsonArray())
                put("automaticBackupEnabled", snap.settings.infrastructure.dailyDriveBackupEnabled)
                put("dailyBackupHour", snap.settings.infrastructure.dailyBackupHour)
                put("externalBackup", snap.settings.externalBackup.toJson())
            })
            put("application", snap.config.toJson())
        }.toString(2)
    }

    private fun saveImportedAppConfig(config: AppConfig) {
        val now = TimeUtils.nowIso()
        val current = latestAppConfig()
        val clean = config.copy(
            id = "global",
            schemaVersion = maxOf(config.schemaVersion, current.schemaVersion, DATA_SCHEMA_VERSION),
            formsSchemaVersion = maxOf(config.formsSchemaVersion, current.formsSchemaVersion, FORM_SCHEMA_VERSION),
            formsUpdatedAt = config.formsUpdatedAt.ifBlank { now },
            seeded = false,
            createdAt = current.createdAt.ifBlank { now },
            updatedAt = now,
            syncStatus = SYNC_PENDING,
            syncError = ""
        )
        db.put(TYPE_APP_CONFIG, "global", clean.toJson(), SYNC_PENDING, now)
    }

    fun importConfig(text: String) {
        val root = JSONObject(text)
        val format = root.optString("format")
        when (format) {
            "skc-app-settings" -> {
                val schemaVersion = root.optInt("schemaVersion", 0)
                if (schemaVersion >= 5 && root.has("appConfig") && root.has("localSettings")) {
                    val importedConfig = AppConfig.fromJson(root.getJSONObject("appConfig"))
                    val importedSettingsRaw = LocalSettings.fromJson(root.getJSONObject("localSettings"))
                    val importedSettings = importedSettingsRaw.copy(
                        supabase = importedSettingsRaw.supabase.copy(
                            lastSyncAt = "",
                            lastBackendFingerprint = ""
                        )
                    )

                    saveImportedAppConfig(importedConfig)
                    state.saveSettings(importedSettings)

                    root.optJSONObject("catalogs")?.let { catalogsJson ->
                        val now = TimeUtils.nowIso()
                        val catalogs = Catalogs.fromJson(catalogsJson).copy(
                            schemaVersion = maxOf(Catalogs.fromJson(catalogsJson).schemaVersion, DATA_SCHEMA_VERSION),
                            seeded = false,
                            updatedAt = now,
                            syncStatus = SYNC_PENDING,
                            syncError = ""
                        ).normalizedProjectHierarchy()
                        db.put(TYPE_CATALOGS, "global", catalogs.toJson(), SYNC_PENDING, now)
                    }

                    root.optJSONObject("credentials")?.let { credentials ->
                        state.saveVpsBackupToken(credentials.optString("vpsBackupToken"))
                        state.saveBackendApiToken(credentials.optString("backendApiToken"))
                        credentials.optJSONObject("backendApiTokens")?.let { tokens ->
                            listOf(
                                DATA_BACKEND_VPS_API,
                                DATA_BACKEND_POSTGRES_API,
                                DATA_BACKEND_MYSQL_API,
                                DATA_BACKEND_MARIADB_API,
                                DATA_BACKEND_SQLSERVER_API,
                                DATA_BACKEND_GENERIC_REST
                            ).forEach { provider ->
                                state.saveBackendApiToken(provider, tokens.optString(provider))
                            }
                        }
                    }

                    root.optJSONObject("assets")?.optJSONObject("excelTemplateFile")?.let { asset ->
                        val bytes = runCatching { Base64.decode(asset.optString("dataBase64"), Base64.NO_WRAP) }.getOrNull()
                        if (importedConfig.excelTemplate.enabled && bytes != null && bytes.isNotEmpty()) {
                            val file = excelTemplateFile(importedConfig.excelTemplate)
                            file.parentFile?.mkdirs()
                            file.writeBytes(bytes)
                        }
                    }
                    migrateBundledSuppliers()
                    return
                }

                // Compatibilidad con el esquema 3.9-3.12 (schemaVersion <= 4).
                val primary = root.optJSONObject("primaryData") ?: JSONObject()
                val provider = primary.optString("provider", DATA_BACKEND_SUPABASE).uppercase()
                val supabaseJson = primary.optJSONObject("supabase")
                if (supabaseJson != null) {
                    val cfg = SupabaseConfig.fromJson(supabaseJson)
                    if (cfg.projectUrl.isNotBlank() || cfg.publishableKey.isNotBlank()) {
                        require(cfg.projectUrl.isNotBlank() && cfg.publishableKey.isNotBlank()) { "El JSON de Supabase está incompleto" }
                        saveSupabase(cfg.projectUrl, cfg.publishableKey, cfg.pollSeconds, cfg.autoSync, cfg.allowSelfSignUp, cfg.lockUserToEmail)
                    }
                }

                val currentSettings = state.settings()
                val backup = root.optJSONObject("backup") ?: JSONObject()
                val allowedData = setOf(DATA_BACKEND_SUPABASE, DATA_BACKEND_VPS_API, DATA_BACKEND_POSTGRES_API, DATA_BACKEND_MYSQL_API, DATA_BACKEND_MARIADB_API, DATA_BACKEND_SQLSERVER_API, DATA_BACKEND_GENERIC_REST)
                val allowedBackup = setOf(BACKUP_GOOGLE_DRIVE, BACKUP_ONEDRIVE, BACKUP_VPS, BACKUP_LOCAL_ZIP)
                val primaryProviders = primary.optJSONArray("providers")?.toStringList()?.map { it.uppercase() }?.filter { it in allowedData }?.distinct().orEmpty()
                    .ifEmpty { listOf(provider.takeIf { it in allowedData } ?: DATA_BACKEND_SUPABASE) }
                val backupProviders = backup.optJSONArray("providers")?.toStringList()?.map { it.uppercase() }?.filter { it in allowedBackup }?.distinct().orEmpty().ifEmpty {
                    val legacy = backup.optString("preferredProvider", BACKUP_NONE).uppercase()
                    if (legacy in allowedBackup) listOf(legacy) else emptyList()
                }
                val secondary = primary.optString("secondaryProvider").uppercase().takeIf { it in allowedData && it != DATA_BACKEND_SUPABASE }.orEmpty()
                val infrastructure = currentSettings.infrastructure.copy(
                    primaryDataProvider = primaryProviders.firstOrNull() ?: DATA_BACKEND_SUPABASE,
                    secondaryDataProvider = secondary,
                    preferredBackupProvider = backupProviders.firstOrNull() ?: BACKUP_NONE,
                    primaryDataProviders = primaryProviders,
                    backupProviders = backupProviders,
                    dailyDriveBackupEnabled = if (backup.has("automaticBackupEnabled")) backup.optBoolean("automaticBackupEnabled") else backup.optBoolean("dailyGoogleDriveEnabled", false),
                    dailyBackupHour = backup.optInt("dailyBackupHour", 23).coerceIn(0, 23)
                )
                val app = root.optJSONObject("application") ?: JSONObject()
                val currentConfig = latestAppConfig()
                val configJson = JSONObject(currentConfig.toJson().toString()).apply {
                    if (app.has("groupName")) put("groupName", app.optString("groupName"))
                    app.optJSONObject("rules")?.let { put("rules", it) }
                    app.optJSONObject("forms")?.let { put("forms", it) }
                    app.optJSONObject("excelTemplate")?.let { put("excelTemplate", it) }
                    if (app.has("includeInternalTransfersInExcel")) put("includeInternalTransfersInExcel", app.optBoolean("includeInternalTransfersInExcel"))
                    root.optJSONObject("evidenceStorage")?.let { put("storage", it) }
                }
                saveImportedAppConfig(AppConfig.fromJson(configJson))
                val importedAlternate = primary.optJSONObject("alternateBackend")?.let { AlternateBackendConfig.fromJson(it) }
                    ?: state.settings().alternateBackend
                val importedConnections = primary.optJSONArray("backendConnections")?.let { array ->
                    (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(AlternateBackendConfig::fromJson) }
                }.orEmpty()
                state.saveSettings(
                    state.settings().copy(
                        notificationsEnabled = app.optBoolean("notificationsEnabled", state.settings().notificationsEnabled),
                        infrastructure = infrastructure,
                        alternateBackend = importedAlternate,
                        backendConnections = importedConnections.ifEmpty { state.settings().backendConnections },
                        externalBackup = state.settings().externalBackup.copy(
                            includeFiles = backup.optBoolean("includeFiles", true),
                            vpsBaseUrl = backup.optString("vpsBaseUrl", state.settings().externalBackup.vpsBaseUrl)
                        )
                    )
                )
            }
            "skc-app-configuration" -> {
                // Compatibilidad con los JSON 3.8.x.
                val settingsJson = root.optJSONObject("settings") ?: JSONObject()
                val currentConfig = latestAppConfig()
                val configJson = JSONObject(currentConfig.toJson().toString()).apply {
                    if (settingsJson.has("groupName")) put("groupName", settingsJson.optString("groupName"))
                    settingsJson.optJSONObject("rules")?.let { put("rules", it) }
                    settingsJson.optJSONObject("forms")?.let { put("forms", it) }
                    settingsJson.optJSONObject("excelTemplate")?.let { put("excelTemplate", it) }
                    if (settingsJson.has("includeInternalTransfersInExcel")) put("includeInternalTransfersInExcel", settingsJson.optBoolean("includeInternalTransfersInExcel"))
                    settingsJson.optJSONObject("storage")?.let { put("storage", it) }
                }
                saveImportedAppConfig(AppConfig.fromJson(configJson))
                settingsJson.optJSONObject("sync")?.let {
                    val cfg = SupabaseConfig.fromJson(it)
                    if (cfg.projectUrl.isNotBlank() && cfg.publishableKey.isNotBlank()) {
                        saveSupabase(cfg.projectUrl, cfg.publishableKey, cfg.pollSeconds, cfg.autoSync, cfg.allowSelfSignUp, cfg.lockUserToEmail)
                    }
                }
                if (settingsJson.has("notificationsEnabled")) saveNotifications(settingsJson.optBoolean("notificationsEnabled", true))
            }
            else -> error("El archivo no es una configuración SKC compatible")
        }
        migrateBundledSuppliers()
    }

    fun exportBackup(): String {
        val root = JSONObject().apply {
            put("format", "skc-facturas-android-backup")
            put("version", APP_VERSION)
            put("createdAt", TimeUtils.nowIso())
            put("settings", state.settings().copy(externalBackup = ExternalBackupConfig()).toJson())
            put("entities", JSONArray().also { array ->
                db.allEntities().forEach { row ->
                    array.put(JSONObject().apply {
                        put("type", row.type); put("id", row.id); put("payload", row.payload); put("syncStatus", row.syncStatus); put("updatedAt", row.updatedAt)
                    })
                }
            })
            put("files", JSONArray().also { array ->
                db.allEntities().filter { it.type == TYPE_TRANSACTIONS || it.type == TYPE_MESSAGES }.forEach { row ->
                    val attachments = row.payload.optJSONArray("attachments") ?: JSONArray()
                    for (i in 0 until attachments.length()) {
                        val item = attachments.optJSONObject(i) ?: continue
                        val path = item.optString("localPath")
                        val file = path.takeIf { it.isNotBlank() }?.let(::File)?.takeIf(File::exists) ?: continue
                        array.put(JSONObject().apply {
                            put("type", row.type); put("entityId", row.id); put("attachmentId", item.optString("id")); put("name", item.optString("filename", item.optString("name")))
                            put("mime", item.optString("mime")); put("data", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
                        })
                    }
                }
            })
            val template = snapshot().config.excelTemplate
            val templateFile = excelTemplateFile(template)
            if (template.enabled && templateFile.exists()) {
                put("excelTemplateFile", JSONObject().apply {
                    put("name", template.fileName)
                    put("updatedAt", template.updatedAt)
                    put("data", Base64.encodeToString(templateFile.readBytes(), Base64.NO_WRAP))
                })
            }
        }
        return root.toString(2)
    }

    fun importBackup(text: String, replace: Boolean = true) {
        val root = JSONObject(text)
        require(root.optString("format") == "skc-facturas-android-backup") { "El archivo no es un respaldo Android SKC válido" }
        if (replace) db.clearAllEntities()
        root.optJSONArray("entities")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val type = item.optString("type")
                if (type !in SYNC_ENTITY_TYPES) continue
                val payload = item.optJSONObject("payload") ?: continue
                db.put(type, item.optString("id"), payload, item.optString("syncStatus", SYNC_PENDING), item.optString("updatedAt", TimeUtils.nowIso()))
            }
        }
        root.optJSONObject("settings")?.let { state.saveSettings(LocalSettings.fromJson(it)) }
        root.optJSONObject("excelTemplateFile")?.let { item ->
            val config = db.get(TYPE_APP_CONFIG, "global")?.payload?.let(AppConfig::fromJson)?.excelTemplate
            val bytes = runCatching { Base64.decode(item.optString("data"), Base64.NO_WRAP) }.getOrNull()
            if (config != null && config.enabled && bytes != null && bytes.isNotEmpty()) {
                val file = excelTemplateFile(config)
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
            }
        }
        root.optJSONArray("files")?.let { files ->
            for (i in 0 until files.length()) {
                val item = files.optJSONObject(i) ?: continue
                val type = item.optString("type")
                val entityId = item.optString("entityId")
                val attachmentId = item.optString("attachmentId")
                val row = db.get(type, entityId) ?: continue
                val bytes = runCatching { Base64.decode(item.optString("data"), Base64.NO_WRAP) }.getOrNull() ?: continue
                val dir = File(context.filesDir, "evidence/$entityId").apply { mkdirs() }
                val file = File(dir, "backup_${attachmentId}_${item.optString("name").replace("[^A-Za-z0-9._-]".toRegex(), "_")}")
                file.writeBytes(bytes)
                val payload = JSONObject(row.payload.toString())
                val attachments = payload.optJSONArray("attachments") ?: JSONArray()
                for (a in 0 until attachments.length()) {
                    val attachment = attachments.optJSONObject(a) ?: continue
                    if (attachment.optString("id") == attachmentId) attachment.put("localPath", file.absolutePath)
                }
                payload.put("attachments", attachments)
                db.put(type, entityId, payload, row.syncStatus, row.updatedAt)
            }
        }
        initialize()
    }

    fun exportPortableData(output: OutputStream, includeFiles: Boolean = true): PortableExportResult {
        val snap = snapshot()
        require(snap.isAdmin()) { "Solo ADMIN puede exportar todos los datos" }

        data class FileRef(val type: String, val entityId: String, val attachment: Attachment)
        val rows = db.allEntities()
        val fileRefs = buildList {
            rows.filter { it.type == TYPE_TRANSACTIONS || it.type == TYPE_MESSAGES }.forEach rowLoop@{ row ->
                val array = row.payload.optJSONArray("attachments") ?: return@rowLoop
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { add(FileRef(row.type, row.id, Attachment.fromJson(it))) }
                }
            }
        }.distinctBy { it.attachment.id }

        fun portablePayload(source: JSONObject): JSONObject {
            val payload = JSONObject(source.toString())
            val attachments = payload.optJSONArray("attachments")
            if (attachments != null) {
                for (i in 0 until attachments.length()) attachments.optJSONObject(i)?.put("localPath", "")
                payload.put("attachments", attachments)
            }
            return payload
        }

        fun safeName(value: String): String = value.replace("[^A-Za-z0-9._-]".toRegex(), "_").take(120).ifBlank { "archivo" }
        fun putText(zip: ZipOutputStream, name: String, text: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        val providerSet = fileRefs.mapNotNull { ref ->
            ref.attachment.storageProvider.ifBlank {
                if (ref.attachment.storageKey.isNotBlank() || ref.attachment.remotePath.isNotBlank()) STORAGE_SUPABASE else ""
            }.takeIf { it.isNotBlank() }
        }.toSortedSet()

        var exported = 0
        var failed = 0
        var exportedBytes = 0L
        val errors = JSONArray()

        ZipOutputStream(output.buffered()).use { zip ->
            val manifest = JSONObject().apply {
                put("format", "skc-portable-export")
                put("schemaVersion", 1)
                put("appVersion", APP_VERSION)
                put("createdAt", TimeUtils.nowIso())
                put("includesFiles", includeFiles)
                put("entityCount", rows.size)
                put("attachmentCount", fileRefs.size)
                put("storageProviders", JSONArray(providerSet.toList()))
                put("dataBackend", "SUPABASE")
                put("lastSyncAt", snap.settings.supabase.lastSyncAt)
                put("notes", "Los registros usan claves lógicas y proveedor por archivo; no se incluyen sesiones ni secretos. Sincroniza antes de exportar para obtener la copia local más reciente.")
            }
            putText(zip, "manifest.json", manifest.toString(2))

            val entities = JSONArray().also { array ->
                rows.forEach { row ->
                    array.put(JSONObject().apply {
                        put("type", row.type)
                        put("id", row.id)
                        put("payload", portablePayload(row.payload))
                        put("syncStatus", row.syncStatus)
                        put("updatedAt", row.updatedAt)
                    })
                }
            }
            putText(zip, "data/entities.json", entities.toString(2))
            putText(zip, "data/configuration.json", exportConfig())

            val attachmentManifest = JSONArray().also { array ->
                fileRefs.forEach { ref ->
                    val a = ref.attachment
                    array.put(JSONObject().apply {
                        put("entityType", ref.type)
                        put("entityId", ref.entityId)
                        put("attachmentId", a.id)
                        put("originalFilename", a.name)
                        put("mimeType", a.mime)
                        put("sizeBytes", a.size)
                        put("sha256", a.sha256)
                        put("storageProvider", a.storageProvider)
                        put("storageKey", a.storageKey.ifBlank { a.remotePath })
                        put("storageObjectId", a.storageObjectId)
                        put("storageStatus", a.storageStatus)
                    })
                }
            }
            putText(zip, "data/attachments.json", attachmentManifest.toString(2))

            if (includeFiles) {
                val templateConfig = snap.config.excelTemplate
                runCatching {
                    val templateBytes = loadExcelTemplate(templateConfig) ?: defaultExcelTemplate()
                    if (templateBytes != null && templateBytes.isNotEmpty()) {
                        zip.putNextEntry(ZipEntry("templates/active_excel_template.xlsx"))
                        zip.write(templateBytes)
                        zip.closeEntry()
                    }
                }.onFailure { e ->
                    errors.put(JSONObject().apply {
                        put("entityType", "appConfig")
                        put("entityId", "excelTemplate")
                        put("filename", templateConfig.fileName.ifBlank { "RE-S11.01_FLUJO_FM_2026.xlsx" })
                        put("error", e.message ?: "No se pudo exportar la plantilla Excel")
                    })
                }

                fileRefs.forEach { ref ->
                    val a = ref.attachment
                    val temp = File.createTempFile("skc-portable-", ".bin", context.cacheDir)
                    try {
                        temp.outputStream().use { sync.downloadEvidenceTo(a, it) }
                        val actualSize = temp.length()
                        val actualHash = FileUtils.sha256(temp)
                        require(a.sha256.isBlank() || a.sha256.equals(actualHash, true)) { "Checksum distinto para ${a.name}" }
                        val provider = a.storageProvider.ifBlank { if (a.storageKey.isNotBlank() || a.remotePath.isNotBlank()) STORAGE_SUPABASE else "LOCAL" }.lowercase()
                        val entryName = "files/$provider/${safeName(ref.type)}/${safeName(ref.entityId)}/${safeName(a.id)}_${safeName(a.name)}"
                        zip.putNextEntry(ZipEntry(entryName))
                        temp.inputStream().use { it.copyTo(zip, 64 * 1024) }
                        zip.closeEntry()
                        exported++
                        exportedBytes += actualSize
                    } catch (e: Exception) {
                        failed++
                        errors.put(JSONObject().apply {
                            put("entityType", ref.type)
                            put("entityId", ref.entityId)
                            put("attachmentId", a.id)
                            put("filename", a.name)
                            put("storageProvider", a.storageProvider)
                            put("storageKey", a.storageKey.ifBlank { a.remotePath })
                            put("error", e.message ?: "No se pudo exportar")
                        })
                    } finally {
                        temp.delete()
                    }
                }
            }

            putText(zip, "reports/export-report.json", JSONObject().apply {
                put("filesExported", exported)
                put("filesFailed", failed)
                put("bytesExported", exportedBytes)
                put("errors", errors)
            }.toString(2))
        }
        return PortableExportResult(rows.size, exported, failed, exportedBytes)
    }

    fun resetLocalData(preserveConnection: Boolean = true) {
        val oldSettings = state.settings()
        val oldSession = state.session()
        db.clearAllEntities()
        val now = TimeUtils.nowIso()
        SeedData.users().forEach { db.put(TYPE_USERS, it.id, it.toJson(), SYNC_PENDING, now) }
        officialExcelCatalogs().let { db.put(TYPE_CATALOGS, it.id, it.toJson(), SYNC_PENDING, it.updatedAt) }
        state.markBundledSuppliersSeeded()
        SeedData.appConfig().let { db.put(TYPE_APP_CONFIG, it.id, it.toJson(), SYNC_PENDING, it.updatedAt) }
        if (preserveConnection) {
            state.saveSettings(oldSettings.copy(profileUserId = ""))
            state.saveSession(oldSession)
            oldSession?.let { bindBySession(it) }
        } else {
            state.saveSettings(LocalSettings())
            state.saveSession(null)
        }
    }
}
