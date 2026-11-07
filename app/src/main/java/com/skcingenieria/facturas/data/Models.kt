package com.skcingenieria.facturas.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

const val SYNC_PENDING = "PENDIENTE"
const val SYNCED = "SINCRONIZADO"
const val SYNC_ERROR = "ERROR"
const val ROLE_ADMIN = "ADMIN"
const val ROLE_USER = "USUARIO"
const val STORAGE_SUPABASE = "SUPABASE"
const val STORAGE_R2 = "R2"
const val BACKUP_NONE = "NONE"
const val BACKUP_GOOGLE_DRIVE = "GOOGLE_DRIVE"
const val BACKUP_ONEDRIVE = "ONEDRIVE"
const val BACKUP_VPS = "VPS"
const val BACKUP_LOCAL_ZIP = "LOCAL_ZIP"
const val DATA_BACKEND_SUPABASE = "SUPABASE"
const val DATA_BACKEND_VPS_API = "VPS_API"
const val DATA_BACKEND_POSTGRES_API = "POSTGRES_API"
const val DATA_BACKEND_MYSQL_API = "MYSQL_API"
const val DATA_BACKEND_MARIADB_API = "MARIADB_API"
const val DATA_BACKEND_SQLSERVER_API = "SQLSERVER_API"
const val DATA_BACKEND_GENERIC_REST = "GENERIC_REST"
const val STORAGE_PENDING = "pending"
const val STORAGE_UPLOADING = "uploading"
const val STORAGE_AVAILABLE = "available"
const val STORAGE_FAILED = "failed"
const val STORAGE_UNAVAILABLE = "unavailable"
const val STORAGE_DELETING = "deleting"
/** Número de versión comercial visible; no alterar al reiniciar el contador los esquemas de datos. */
const val APP_VERSION = "1.5.1"
const val DATA_SCHEMA_VERSION = 351
const val FORM_SCHEMA_VERSION = 610
const val FIELD_EVIDENCE = "EVIDENCE"
const val FIELD_TEXT = "TEXT"
const val FIELD_SELECT = "SELECT"
const val FIELD_LINKED_SELECT = "LINKED_SELECT"
const val FIELD_COMPOSITE_SELECT = "COMPOSITE_SELECT"
const val LINKED_COLUMN_PRIMARY = "PRIMARY"
const val LINKED_COLUMN_SUBCATEGORY = "SUBCATEGORY"
const val LINKED_COLUMN_LINKED = "LINKED"
const val LINKED_COLUMN_IGNORE = "IGNORE"

private fun JSONObject.s(key: String, fallback: String = ""): String =
    if (has(key) && !isNull(key)) optString(key, fallback) else fallback
private fun JSONObject.d(key: String, fallback: Double = 0.0): Double =
    if (has(key) && !isNull(key)) optDouble(key, fallback) else fallback
private fun JSONObject.b(key: String, fallback: Boolean = false): Boolean =
    if (has(key) && !isNull(key)) optBoolean(key, fallback) else fallback

fun Iterable<String>.toJsonArray(): JSONArray = JSONArray().also { arr -> forEach { arr.put(it) } }
fun JSONArray.toStringList(): List<String> = (0 until length()).mapNotNull { i -> optString(i, "").takeIf(String::isNotBlank) }

data class UserProfile(
    val id: String,
    val name: String,
    val email: String = "",
    val role: String = ROLE_USER,
    val authUserId: String = "",
    val transferEnabled: Boolean = true,
    val initialBalance: Double = 0.0,
    val active: Boolean = true,
    val seeded: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = "",
    val syncStatus: String = SYNC_PENDING,
    val syncError: String = ""
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("email", email); put("role", role); put("authUserId", authUserId)
        put("transferEnabled", transferEnabled); put("initialBalance", initialBalance); put("active", active)
        put("seeded", seeded); put("createdAt", createdAt); put("updatedAt", updatedAt)
        put("syncStatus", syncStatus); put("syncError", syncError)
    }
    companion object {
        fun fromJson(j: JSONObject) = UserProfile(
            id = j.s("id"), name = j.s("name"), email = j.s("email"), role = j.s("role", ROLE_USER),
            authUserId = j.s("authUserId", j.s("auth_user_id")),
            transferEnabled = j.b("transferEnabled", true), initialBalance = j.d("initialBalance"), active = j.b("active", true),
            seeded = j.b("seeded"), createdAt = j.s("createdAt"), updatedAt = j.s("updatedAt"),
            syncStatus = j.s("syncStatus", SYNC_PENDING), syncError = j.s("syncError")
        )
    }
}

const val GENERAL_PROJECT_ID = "general"
const val GENERAL_PROJECT_NAME = "General"

data class ProjectDefinition(
    val id: String,
    val description: String,
    val project2Options: List<String> = emptyList(),
    val clients: List<String> = emptyList(),
    val clientsByProject2: Map<String, List<String>> = emptyMap()
) {
    fun allClients(): List<String> {
        val source = clients.ifEmpty { clientsByProject2.values.flatten() }
        val seen = mutableSetOf<String>()
        return source.map(String::trim).filter(String::isNotBlank).filter { seen.add(it.lowercase()) }
    }

    fun clientsFor(project2: String = ""): List<String> = allClients()

    fun toJson() = JSONObject().apply {
        put("id", id)
        put("description", description)
        put("project2Options", project2Options.toJsonArray())
        put("clients", allClients().toJsonArray())
        put("clientsByProject2", JSONObject().also { root ->
            clientsByProject2.forEach { (project2, clients) -> root.put(project2, clients.toJsonArray()) }
        })
    }

    companion object {
        fun fromJson(j: JSONObject): ProjectDefinition {
            val legacy = j.optJSONObject("clientsByProject2")?.let { root ->
                buildMap { root.keys().forEach { key -> put(key, root.optJSONArray(key)?.toStringList().orEmpty()) } }
            }.orEmpty()
            val direct = j.optJSONArray("clients")?.toStringList().orEmpty()
            return ProjectDefinition(
                id = j.s("id"),
                description = j.s("description"),
                project2Options = j.optJSONArray("project2Options")?.toStringList().orEmpty(),
                clients = direct.ifEmpty { legacy.values.flatten().distinct() },
                clientsByProject2 = legacy
            )
        }
    }
}



data class SupplierDefinition(
    val id: String,
    val name: String,
    val ruc: String = "",
    val email: String = ""
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("ruc", ruc); put("email", email)
    }

    companion object {
        fun fromJson(j: JSONObject) = SupplierDefinition(
            id = j.s("id"), name = j.s("name"), ruc = j.s("ruc"), email = j.s("email")
        )
    }
}

/**
 * Índice in-memory para búsquedas de proveedores.
 *
 * Normaliza el catálogo una sola vez por instancia de [Catalogs], evitando repetir
 * Normalizer/regex para miles de proveedores en cada tecla. La búsqueda conserva
 * solo los mejores N candidatos, por lo que tampoco ordena una lista masiva.
 */
private class SupplierLookup(suppliers: List<SupplierDefinition>) {
    private data class Entry(
        val supplier: SupplierDefinition,
        val name: String,
        val ruc: String,
        val email: String,
        val sortName: String
    )

    private data class Scored(val entry: Entry, val rank: Int)

    private val entries = suppliers.map { supplier ->
        Entry(
            supplier = supplier,
            name = normalizeSupplierSearch(supplier.name),
            ruc = normalizeSupplierSearch(supplier.ruc),
            email = normalizeSupplierSearch(supplier.email),
            sortName = supplier.name.lowercase(Locale.ROOT)
        )
    }

    private val exactById = suppliers.associateBy { it.id.trim().lowercase(Locale.ROOT) }
    private val exactByName = suppliers.associateBy { it.name.trim().lowercase(Locale.ROOT) }
    private val alphabetical by lazy(LazyThreadSafetyMode.NONE) {
        suppliers.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    fun exact(value: String): SupplierDefinition? {
        val key = value.trim().lowercase(Locale.ROOT)
        if (key.isBlank()) return null
        return exactById[key] ?: exactByName[key]
    }

    fun preview(limit: Int): List<SupplierDefinition> = alphabetical.take(limit.coerceAtLeast(0))

    fun search(query: String, limit: Int): List<SupplierDefinition> {
        val max = limit.coerceAtLeast(0)
        if (max == 0) return emptyList()
        val q = normalizeSupplierSearch(query)
        if (q.isBlank()) return emptyList()

        val best = ArrayList<Scored>(max)
        for (entry in entries) {
            val rank = minOf(
                rank(entry.name, q),
                rank(entry.ruc, q),
                rank(entry.email, q)
            )
            if (rank > 2) continue

            val candidate = Scored(entry, rank)
            var insertAt = 0
            while (insertAt < best.size && compare(best[insertAt], candidate) <= 0) insertAt++
            if (insertAt < max) {
                best.add(insertAt, candidate)
                if (best.size > max) best.removeAt(best.lastIndex)
            } else if (best.size < max) {
                best.add(candidate)
            }
        }
        return best.map { it.entry.supplier }
    }

    private fun rank(value: String, query: String): Int = when {
        value == query -> 0
        value.startsWith(query) -> 1
        value.contains(query) -> 2
        else -> 9
    }

    private fun compare(a: Scored, b: Scored): Int {
        val byRank = a.rank.compareTo(b.rank)
        return if (byRank != 0) byRank else a.entry.sortName.compareTo(b.entry.sortName)
    }
}

private fun normalizeSupplierSearch(value: String): String {
    if (value.isBlank()) return ""
    val source = Normalizer.normalize(value.trim().uppercase(Locale.ROOT), Normalizer.Form.NFD)
    val out = StringBuilder(source.length)
    source.forEach { ch ->
        val type = Character.getType(ch)
        if (type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
        ) return@forEach
        if (ch in 'A'..'Z' || ch in '0'..'9') out.append(ch)
    }
    return out.toString()
}

data class ClientDirectoryEntry(
    val id: String,
    val commercialName: String = "",
    val legalName: String = ""
) {
    val displayName: String get() = commercialName.ifBlank { legalName }
    fun matches(query: String): Boolean {
        val q = query.trim()
        return q.isBlank() || commercialName.contains(q, true) || legalName.contains(q, true)
    }
    fun toJson() = JSONObject().apply {
        put("id", id); put("commercialName", commercialName); put("legalName", legalName)
    }
    companion object {
        fun fromJson(j: JSONObject) = ClientDirectoryEntry(
            id = j.s("id"), commercialName = j.s("commercialName"), legalName = j.s("legalName", j.s("name"))
        )
    }
}

data class Catalogs(
    val id: String = "global",
    val projects: List<ProjectDefinition> = emptyList(),
    val costCenters: List<String> = emptyList(),
    val secondaryCosts: List<String> = emptyList(),
    val secondaryCostsByCenter: Map<String, List<String>> = emptyMap(),
    val clients: List<String> = emptyList(),
    val suppliers: List<SupplierDefinition> = emptyList(),
    val clientDirectory: List<ClientDirectoryEntry> = emptyList(),
    val evidenceStatuses: List<String> = emptyList(),
    val paymentMethods: List<String> = emptyList(),
    val schemaVersion: Int = 0,
    val seeded: Boolean = false,
    val updatedAt: String = "",
    val syncStatus: String = SYNC_PENDING,
    val syncError: String = ""
) {
    fun project(descriptionOrId: String): ProjectDefinition? = projects.firstOrNull {
        it.id == descriptionOrId || it.description == descriptionOrId
    }

    fun clientsFor(descriptionOrId: String, project2: String = ""): List<String> {
        val project = project(descriptionOrId) ?: return clients
        return project.allClients()
    }

    private val supplierLookup by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SupplierLookup(suppliers) }

    fun supplier(nameOrId: String): SupplierDefinition? = supplierLookup.exact(nameOrId)

    fun supplierCandidates(query: String, limit: Int = 8): List<SupplierDefinition> =
        supplierLookup.search(query, limit)

    fun supplierPreview(limit: Int = 12): List<SupplierDefinition> =
        supplierLookup.preview(limit)

    fun clientCandidates(query: String, limit: Int = 8): List<ClientDirectoryEntry> {
        val directory = if (clientDirectory.isNotEmpty()) clientDirectory else clients.mapIndexed { index, name ->
            ClientDirectoryEntry(id = "legacy-client-$index", legalName = name)
        }
        val q = query.trim()
        fun rank(value: String): Int = when {
            value.equals(q, true) -> 0
            value.startsWith(q, true) -> 1
            value.contains(q, true) -> 2
            else -> 9
        }
        return directory.filter { it.matches(q) }
            .sortedWith(compareBy<ClientDirectoryEntry> { minOf(rank(it.commercialName), rank(it.legalName)) }.thenBy { it.displayName.lowercase() })
            .take(limit)
    }

    fun normalizedProjectHierarchy(): Catalogs {
        fun distinctValues(values: List<String>): List<String> {
            val seen = mutableSetOf<String>()
            return values.map(String::trim).filter(String::isNotBlank).filter { seen.add(it.lowercase()) }
        }
        val currentGeneral = projects.firstOrNull { it.id == GENERAL_PROJECT_ID || it.description.equals(GENERAL_PROJECT_NAME, true) }
        val regular = projects.filterNot { it.id == GENERAL_PROJECT_ID || it.description.equals(GENERAL_PROJECT_NAME, true) }.map { project ->
            project.copy(
                project2Options = distinctValues(project.project2Options),
                clients = distinctValues(project.allClients()),
                clientsByProject2 = emptyMap()
            )
        }
        val masterClients = distinctValues(clients + currentGeneral?.allClients().orEmpty() + regular.flatMap { it.clients })
        val masterProject2 = distinctValues(currentGeneral?.project2Options.orEmpty() + regular.flatMap { it.project2Options })
        val general = ProjectDefinition(
            id = GENERAL_PROJECT_ID,
            description = GENERAL_PROJECT_NAME,
            project2Options = masterProject2,
            clients = masterClients
        )
        val directoryByName = linkedMapOf<String, ClientDirectoryEntry>()
        clientDirectory.forEach { entry ->
            val key = entry.legalName.ifBlank { entry.commercialName }.trim().lowercase()
            if (key.isNotBlank()) directoryByName[key] = entry
        }
        masterClients.forEachIndexed { index, name ->
            val key = name.trim().lowercase()
            if (key.isNotBlank() && key !in directoryByName) {
                directoryByName[key] = ClientDirectoryEntry(id = "client-${index + 1}", legalName = name)
            }
        }
        val cleanSuppliers = suppliers
            .map { it.copy(name = it.name.trim(), ruc = it.ruc.trim(), email = it.email.trim()) }
            .filter { it.name.isNotBlank() }
            .distinctBy { it.name.lowercase() }
        return copy(
            projects = listOf(general) + regular,
            clients = masterClients,
            suppliers = cleanSuppliers,
            clientDirectory = directoryByName.values.toList()
        )
    }

    fun toJson() = JSONObject().apply {
        put("id", id)
        put("projects", JSONArray().also { a -> projects.forEach { a.put(it.toJson()) } })
        put("costCenters", costCenters.toJsonArray()); put("secondaryCosts", secondaryCosts.toJsonArray())
        put("secondaryCostsByCenter", JSONObject().also { root -> secondaryCostsByCenter.forEach { (center, values) -> root.put(center, values.toJsonArray()) } })
        put("clients", clients.toJsonArray())
        put("suppliers", JSONArray().also { a -> suppliers.forEach { a.put(it.toJson()) } })
        put("clientDirectory", JSONArray().also { a -> clientDirectory.forEach { a.put(it.toJson()) } })
        put("evidenceStatuses", evidenceStatuses.toJsonArray()); put("paymentMethods", paymentMethods.toJsonArray())
        put("schemaVersion", schemaVersion)
        put("seeded", seeded); put("updatedAt", updatedAt); put("syncStatus", syncStatus); put("syncError", syncError)
    }
    companion object {
        fun fromJson(j: JSONObject): Catalogs {
            val projects = j.optJSONArray("projects")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(ProjectDefinition::fromJson) }
            }.orEmpty()
            return Catalogs(
                id = j.s("id", "global"), projects = projects,
                costCenters = j.optJSONArray("costCenters")?.toStringList().orEmpty(),
                secondaryCosts = j.optJSONArray("secondaryCosts")?.toStringList().orEmpty(),
                secondaryCostsByCenter = j.optJSONObject("secondaryCostsByCenter")?.let { root ->
                    buildMap { root.keys().forEach { key -> put(key, root.optJSONArray(key)?.toStringList().orEmpty()) } }
                }.orEmpty(),
                clients = j.optJSONArray("clients")?.toStringList().orEmpty(),
                suppliers = j.optJSONArray("suppliers")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(SupplierDefinition::fromJson) }
                }.orEmpty(),
                clientDirectory = j.optJSONArray("clientDirectory")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(ClientDirectoryEntry::fromJson) }
                }.orEmpty(),
                evidenceStatuses = j.optJSONArray("evidenceStatuses")?.toStringList().orEmpty(),
                paymentMethods = j.optJSONArray("paymentMethods")?.toStringList().orEmpty(),
                schemaVersion = j.optInt("schemaVersion", 0),
                seeded = j.b("seeded"), updatedAt = j.s("updatedAt"),
                syncStatus = j.s("syncStatus", SYNC_PENDING), syncError = j.s("syncError")
            )
        }
    }
}

data class FormOptionConfig(
    val value: String,
    val label: String = value,
    val linkedData: Map<String, String> = emptyMap(),
    val parentValue: String = ""
) {
    fun toJson() = JSONObject().apply {
        put("value", value)
        put("label", label)
        put("parentValue", parentValue)
        put("linkedData", JSONObject().also { obj -> linkedData.forEach { (k, v) -> obj.put(k, v) } })
    }
    companion object {
        fun fromJson(j: JSONObject) = FormOptionConfig(
            value = j.s("value"),
            label = j.s("label", j.s("value")),
            parentValue = j.s("parentValue"),
            linkedData = j.optJSONObject("linkedData")?.let { obj ->
                buildMap { obj.keys().forEach { key -> put(key, obj.optString(key, "")) } }
            }.orEmpty()
        )
    }
}

data class LinkedTableColumnConfig(
    val sourceColumn: String,
    val role: String = LINKED_COLUMN_IGNORE,
    val label: String = sourceColumn,
    val targetKey: String = "",
    val visible: Boolean = true,
    val order: Int = 0
) {
    fun toJson() = JSONObject().apply {
        put("sourceColumn", sourceColumn)
        put("role", role)
        put("label", label)
        put("targetKey", targetKey)
        put("visible", visible)
        put("order", order)
    }

    companion object {
        fun fromJson(j: JSONObject) = LinkedTableColumnConfig(
            sourceColumn = j.s("sourceColumn"),
            role = j.s("role", LINKED_COLUMN_IGNORE).uppercase().let {
                if (it in setOf(LINKED_COLUMN_PRIMARY, LINKED_COLUMN_SUBCATEGORY, LINKED_COLUMN_LINKED, LINKED_COLUMN_IGNORE)) it
                else LINKED_COLUMN_IGNORE
            },
            label = j.s("label", j.s("sourceColumn")),
            targetKey = j.s("targetKey"),
            visible = j.b("visible", true),
            order = j.optInt("order", 0)
        )
    }
}

data class LinkedTableConfig(
    val enabled: Boolean = false,
    val sourceName: String = "",
    val sheetName: String = "",
    val headers: List<String> = emptyList(),
    val rows: List<Map<String, String>> = emptyList(),
    val columns: List<LinkedTableColumnConfig> = emptyList()
) {
    fun primary(): LinkedTableColumnConfig? = columns.firstOrNull { it.role == LINKED_COLUMN_PRIMARY }
    fun subcategories(): List<LinkedTableColumnConfig> = columns.filter { it.role == LINKED_COLUMN_SUBCATEGORY }.sortedBy { it.order }
    fun linked(): List<LinkedTableColumnConfig> = columns.filter { it.role == LINKED_COLUMN_LINKED }.sortedBy { it.order }
    fun isUsable(): Boolean = enabled && rows.isNotEmpty() && primary()?.sourceColumn?.isNotBlank() == true

    fun toJson() = JSONObject().apply {
        put("enabled", enabled)
        put("sourceName", sourceName)
        put("sheetName", sheetName)
        put("headers", headers.toJsonArray())
        put("columns", JSONArray().also { array -> columns.forEach { array.put(it.toJson()) } })
        put("rows", JSONArray().also { array ->
            rows.forEach { row ->
                array.put(JSONObject().also { obj -> headers.forEach { header -> obj.put(header, row[header].orEmpty()) } })
            }
        })
    }

    companion object {
        fun fromJson(j: JSONObject?): LinkedTableConfig {
            if (j == null) return LinkedTableConfig()
            val headers = j.optJSONArray("headers")?.toStringList().orEmpty()
            val columns = j.optJSONArray("columns")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(LinkedTableColumnConfig::fromJson) }
            }.orEmpty()
            val rows: List<Map<String, String>> = j.optJSONArray("rows")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    a.optJSONObject(i)?.let { obj ->
                        buildMap<String, String> {
                            val keys: List<String> = if (headers.isNotEmpty()) {
                                headers
                            } else {
                                buildList<String> { obj.keys().forEach { add(it) } }
                            }
                            keys.forEach { key -> put(key, obj.optString(key, "")) }
                        }
                    }
                }
            }.orEmpty()
            return LinkedTableConfig(
                enabled = j.b("enabled"),
                sourceName = j.s("sourceName"),
                sheetName = j.s("sheetName"),
                headers = headers.ifEmpty { columns.map { it.sourceColumn }.filter(String::isNotBlank).distinct() },
                rows = rows,
                columns = columns
            )
        }
    }
}

data class FormFieldConfig(
    val key: String,
    val label: String,
    val visible: Boolean = true,
    val required: Boolean = false,
    val defaultValue: String = "",
    val order: Int = 0,
    val core: Boolean = false,
    val fieldType: String = FIELD_TEXT,
    val options: List<FormOptionConfig> = emptyList(),
    val parentKey: String = "",
    val linkedTable: LinkedTableConfig = LinkedTableConfig()
) {
    fun toJson() = JSONObject().apply {
        put("id", key); put("key", key); put("label", label); put("visible", visible); put("required", required)
        put("defaultValue", defaultValue); put("order", order); put("core", core)
        put("fieldType", fieldType); put("parentKey", parentKey)
        put("options", JSONArray().also { a -> options.forEach { a.put(it.toJson()) } })
        put("linkedTable", linkedTable.toJson())
    }
    companion object {
        fun fromJson(j: JSONObject) = FormFieldConfig(
            key = j.s("id", j.s("key")),
            label = j.s("label"),
            visible = j.b("visible", true),
            required = j.b("required"),
            defaultValue = j.s("defaultValue"),
            order = j.optInt("order", 0),
            core = j.b("core"),
            fieldType = j.s("fieldType", FIELD_TEXT).uppercase().let {
                if (it in setOf(FIELD_EVIDENCE, FIELD_TEXT, FIELD_SELECT, FIELD_LINKED_SELECT, FIELD_COMPOSITE_SELECT)) it else FIELD_TEXT
            },
            options = j.optJSONArray("options")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(FormOptionConfig::fromJson) }
            }.orEmpty(),
            parentKey = j.s("parentKey"),
            linkedTable = LinkedTableConfig.fromJson(j.optJSONObject("linkedTable"))
        )
    }
}

fun defaultFormLayouts(): Map<String, List<FormFieldConfig>> = mapOf(
    "invoice" to listOf(
        FormFieldConfig(
            "movementType", "¿Es ingreso o un gasto?", true, true, "Gasto", 0, true, FIELD_SELECT,
            options = listOf(FormOptionConfig("Gasto", "Gasto"), FormOptionConfig("Ingreso", "Ingreso"))
        ),
        FormFieldConfig("amount", "Monto", true, true, "", 1, true, FIELD_TEXT),
        FormFieldConfig("supplier", "Proveedor", true, true, "", 2, true, FIELD_LINKED_SELECT),
        // La persona administradora puede vincular aquí un CSV/Excel de Proyecto → Subproyecto → Proyecto 2.
        FormFieldConfig("project", "Proyectos", true, false, "", 3, true, FIELD_LINKED_SELECT),
        FormFieldConfig("invoiceNumber", "Número de factura", true, true, "", 4, true, FIELD_TEXT),
        FormFieldConfig("observations", "Observaciones", true, false, "", 5, true, FIELD_TEXT),
        FormFieldConfig("attachments", "Evidencia", true, true, "", 6, true, FIELD_EVIDENCE),
        // Datos del sistema: se guardan siempre pero no ensucian el formulario.
        FormFieldConfig("accountUserId", "Responsable", false, false, "@currentUser", 7, true, FIELD_TEXT),
        FormFieldConfig("purchaseDate", "Fecha", false, false, "@today", 8, true, FIELD_TEXT)
    ),
    "fundRequest" to listOf(
        FormFieldConfig("amount", "Monto", true, true, "", 0, true, FIELD_TEXT),
        FormFieldConfig("reason", "Motivo", true, true, "", 1, true, FIELD_TEXT)
    ),
    "transfer" to listOf(
        FormFieldConfig("amount", "Monto", true, true, "", 0, true, FIELD_TEXT),
        FormFieldConfig("reason", "Motivo", true, true, "", 1, true, FIELD_TEXT),
        FormFieldConfig("attachments", "Evidencia", true, true, "", 2, true, FIELD_EVIDENCE),
        FormFieldConfig("transferDate", "Fecha", false, false, "@today", 3, true, FIELD_TEXT)
    ),
    "reminder" to listOf(
        FormFieldConfig("reason", "Motivo", true, true, "", 0, true, FIELD_TEXT)
    )
)

data class StorageConfig(
    val provider: String = STORAGE_SUPABASE,
    val supabaseBucket: String = "skc-evidence",
    val r2FunctionName: String = "skc-storage-r2",
    val basePrefix: String = "skc",
    val imageMaxMb: Int = 5,
    val pdfMaxMb: Int = 15,
    val otherMaxMb: Int = 10,
    val allowedExtensions: List<String> = listOf("jpg", "jpeg", "png", "webp", "pdf"),
    val optimizeImages: Boolean = true,
    val imageMaxDimension: Int = 1920,
    val imageQuality: Int = 85
) {
    fun toJson() = JSONObject().apply {
        put("provider", provider); put("supabaseBucket", supabaseBucket); put("r2FunctionName", r2FunctionName); put("basePrefix", basePrefix)
        put("imageMaxMb", imageMaxMb); put("pdfMaxMb", pdfMaxMb); put("otherMaxMb", otherMaxMb)
        put("allowedExtensions", allowedExtensions.toJsonArray()); put("optimizeImages", optimizeImages)
        put("imageMaxDimension", imageMaxDimension); put("imageQuality", imageQuality)
    }

    companion object {
        fun fromJson(j: JSONObject?) = if (j == null) StorageConfig() else StorageConfig(
            provider = j.s("provider", STORAGE_SUPABASE).uppercase().let { if (it == STORAGE_R2) STORAGE_R2 else STORAGE_SUPABASE },
            supabaseBucket = j.s("supabaseBucket", "skc-evidence").ifBlank { "skc-evidence" },
            r2FunctionName = j.s("r2FunctionName", "skc-storage-r2").ifBlank { "skc-storage-r2" },
            basePrefix = j.s("basePrefix", "skc").trim('/').replace("/", "-").ifBlank { "skc" },
            imageMaxMb = j.optInt("imageMaxMb", 5).coerceIn(1, 50),
            pdfMaxMb = j.optInt("pdfMaxMb", 15).coerceIn(1, 50),
            otherMaxMb = j.optInt("otherMaxMb", 10).coerceIn(1, 50),
            allowedExtensions = j.optJSONArray("allowedExtensions")?.toStringList()?.map { it.lowercase().trimStart('.') }?.filter { it.isNotBlank() }?.distinct().orEmpty().ifEmpty { listOf("jpg", "jpeg", "png", "webp", "pdf") },
            optimizeImages = j.b("optimizeImages", true),
            imageMaxDimension = j.optInt("imageMaxDimension", 1920).coerceIn(640, 6000),
            imageQuality = j.optInt("imageQuality", 85).coerceIn(40, 100)
        )
    }
}

data class ExcelColumnMapping(
    val reference: String,
    val header: String = "",
    val fieldKey: String = ""
) {
    fun toJson() = JSONObject().apply {
        put("reference", reference); put("header", header); put("fieldKey", fieldKey)
    }

    companion object {
        fun fromJson(j: JSONObject) = ExcelColumnMapping(
            reference = j.s("reference"),
            header = j.s("header"),
            fieldKey = j.s("fieldKey")
        )
    }
}

data class ExcelTemplateConfig(
    val enabled: Boolean = false,
    val fileName: String = "",
    val sheetName: String = "",
    val dataStartRow: Int = 2,
    val remotePath: String = "",
    val storageProvider: String = "",
    val storageKey: String = "",
    val mappings: List<ExcelColumnMapping> = emptyList(),
    val updatedAt: String = ""
) {
    fun toJson() = JSONObject().apply {
        val key = storageKey.ifBlank { remotePath }
        put("enabled", enabled); put("fileName", fileName); put("sheetName", sheetName)
        put("dataStartRow", dataStartRow); put("remotePath", key); put("storageProvider", storageProvider); put("storageKey", key); put("updatedAt", updatedAt)
        put("mappings", JSONArray().also { a -> mappings.forEach { a.put(it.toJson()) } })
    }

    companion object {
        fun fromJson(j: JSONObject?) = if (j == null) ExcelTemplateConfig() else {
            val key = j.s("storageKey", j.s("remotePath"))
            ExcelTemplateConfig(
                enabled = j.b("enabled"),
                fileName = j.s("fileName"),
                sheetName = j.s("sheetName"),
                dataStartRow = j.optInt("dataStartRow", 2).coerceAtLeast(1),
                remotePath = key,
                storageProvider = j.s("storageProvider", if (key.isNotBlank()) STORAGE_SUPABASE else ""),
                storageKey = key,
                mappings = j.optJSONArray("mappings")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(ExcelColumnMapping::fromJson) }
                }.orEmpty(),
                updatedAt = j.s("updatedAt")
            )
        }
    }
}

data class AppConfig(
    val id: String = "global",
    val groupName: String = "PROYECTOS",
    val descriptionLimit: Int = 500,
    val duplicateWindowDays: Int = 3,
    val reminderIntervalMinutes: Int = 60,
    val forms: Map<String, List<FormFieldConfig>> = defaultFormLayouts(),
    val excelTemplate: ExcelTemplateConfig = ExcelTemplateConfig(),
    val includeInternalTransfersInExcel: Boolean = false,
    val transferRequiresConfirmation: Boolean = true,
    val storage: StorageConfig = StorageConfig(),
    val revocationKey: String = "",
    val formsUpdatedAt: String = "",
    val formsSchemaVersion: Int = FORM_SCHEMA_VERSION,
    val schemaVersion: Int = 0,
    val seeded: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = "",
    val syncStatus: String = SYNC_PENDING,
    val syncError: String = ""
) {
    fun fields(module: String): List<FormFieldConfig> =
        forms[module] ?: defaultFormLayouts()[module].orEmpty()

    fun withFields(module: String, fields: List<FormFieldConfig>): AppConfig =
        copy(forms = forms.toMutableMap().apply { put(module, fields) })

    val invoiceFields: List<FormFieldConfig> get() = fields("invoice")

    fun toJson() = JSONObject().apply {
        put("id", id); put("groupName", groupName)
        put("rules", JSONObject().apply {
            put("descriptionLimit", descriptionLimit)
            put("duplicateWindowDays", duplicateWindowDays)
            put("reminderIntervalMinutes", reminderIntervalMinutes)
        })
        put("forms", JSONObject().also { root ->
            forms.forEach { (module, fields) ->
                root.put(module, JSONArray().also { a -> fields.forEach { a.put(it.toJson()) } })
            }
        })
        put("excelTemplate", excelTemplate.toJson())
        put("includeInternalTransfersInExcel", includeInternalTransfersInExcel)
        put("transferRequiresConfirmation", transferRequiresConfirmation)
        put("storage", storage.toJson())
        put("revocationKey", revocationKey)
        put("formsUpdatedAt", formsUpdatedAt)
        put("formsSchemaVersion", formsSchemaVersion)
        put("schemaVersion", schemaVersion)
        put("seeded", seeded); put("createdAt", createdAt); put("updatedAt", updatedAt)
        put("syncStatus", syncStatus); put("syncError", syncError)
    }

    companion object {
        fun fromJson(j: JSONObject): AppConfig {
            val rules = j.optJSONObject("rules") ?: JSONObject()
            val defaults = defaultFormLayouts()
            val formsJson = j.optJSONObject("forms")
            val modules = (defaults.keys + buildSet {
                formsJson?.keys()?.forEach { add(it) }
            }).toSet()
            val parsed = modules.associateWith { module ->
                val source = formsJson?.optJSONArray(module) ?: if (module == "invoice") j.optJSONArray("invoiceFields") else null
                if (source == null) {
                    defaults[module].orEmpty()
                } else {
                    (0 until source.length()).mapNotNull { i -> source.optJSONObject(i)?.let(FormFieldConfig::fromJson) }
                        .filter { it.key.isNotBlank() }
                        .sortedBy { it.order }
                        .mapIndexed { index, field -> field.copy(order = index) }
                }
            }
            return AppConfig(
                id = j.s("id", "global"),
                groupName = j.s("groupName", "PROYECTOS"),
                descriptionLimit = rules.optInt("descriptionLimit", 500).coerceIn(50, 5000),
                duplicateWindowDays = rules.optInt("duplicateWindowDays", 3).coerceIn(0, 365),
                reminderIntervalMinutes = rules.optInt("reminderIntervalMinutes", 60).coerceIn(10, 10080),
                forms = parsed,
                excelTemplate = ExcelTemplateConfig.fromJson(j.optJSONObject("excelTemplate")),
                includeInternalTransfersInExcel = j.b("includeInternalTransfersInExcel"),
                transferRequiresConfirmation = j.b("transferRequiresConfirmation", true),
                storage = StorageConfig.fromJson(j.optJSONObject("storage")),
                revocationKey = j.s("revocationKey"),
                formsUpdatedAt = j.s("formsUpdatedAt"),
                formsSchemaVersion = j.optInt("formsSchemaVersion", 0),
                schemaVersion = j.optInt("schemaVersion", 0),
                seeded = j.b("seeded"), createdAt = j.s("createdAt"), updatedAt = j.s("updatedAt"),
                syncStatus = j.s("syncStatus", SYNC_PENDING), syncError = j.s("syncError")
            )
        }
    }
}

fun defaultInvoiceFields(): List<FormFieldConfig> = defaultFormLayouts()["invoice"].orEmpty()

data class Attachment(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val sha256: String,
    val localPath: String = "",
    val remotePath: String = "",
    val storageProvider: String = "",
    val storageKey: String = "",
    val storageObjectId: String = "",
    val storageStatus: String = STORAGE_PENDING,
    val storageError: String = ""
) {
    fun toJson() = JSONObject().apply {
        val key = storageKey.ifBlank { remotePath }
        put("id", id); put("name", name); put("filename", name); put("mime", mime); put("size", size); put("sha256", sha256)
        put("localPath", localPath); put("remotePath", key); put("storageProvider", storageProvider); put("storageKey", key)
        put("storageObjectId", storageObjectId); put("storageStatus", storageStatus); put("storageError", storageError)
    }
    companion object {
        fun fromJson(j: JSONObject): Attachment {
            val key = j.s("storageKey", j.s("remotePath"))
            val provider = j.s("storageProvider", if (key.isNotBlank()) STORAGE_SUPABASE else "")
            val status = j.s("storageStatus", if (key.isNotBlank()) STORAGE_AVAILABLE else STORAGE_PENDING)
            return Attachment(
                id = j.s("id"),
                name = j.s("filename", j.s("name", "evidencia")),
                mime = j.s("mime"),
                size = j.optLong("size", 0),
                sha256 = j.s("sha256"),
                localPath = j.s("localPath"),
                remotePath = key,
                storageProvider = provider,
                storageKey = key,
                storageObjectId = j.s("storageObjectId"),
                storageStatus = status,
                storageError = j.s("storageError")
            )
        }
    }
}

private fun attachmentsToJson(items: List<Attachment>) = JSONArray().also { a -> items.forEach { a.put(it.toJson()) } }
private fun attachmentsFromJson(a: JSONArray?): List<Attachment> = a?.let { arr ->
    (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(Attachment::fromJson) }
}.orEmpty()

data class Transaction(
    val id: String,
    val code: String,
    val movementType: String,
    val purchaseDate: String,
    val descriptionBase: String,
    val project: String,
    val description: String,
    val area: String = "",
    val liquidationType: String = "",
    val costCenter: String = "",
    val secondaryCost: String = "",
    val client: String = "",
    val supplier: String = "",
    val supplierRuc: String = "",
    val supplierEmail: String = "",
    val documentType: String = "FACTURA",
    val invoiceNumber: String = "",
    val paymentMethod: String = "",
    val evidenceStatus: String = "",
    val observations: String = "",
    val amount: Double,
    val accountUserId: String,
    val accountUsername: String,
    val createdByUserId: String,
    val createdByName: String,
    val groupName: String = "PROYECTOS",
    val attachments: List<Attachment> = emptyList(),
    val attachmentHash: String = "",
    val duplicateReview: String = "",
    val createdAt: String,
    val updatedAt: String,
    val syncStatus: String = SYNC_PENDING,
    val syncError: String = "",
    val currency: String = "USD",
    val userEmail: String = "",
    val descriptionBaseId: String = "",
    val reviewStatus: String = "NUEVO",
    val recordStatus: String = "ACTIVA",
    val revocationRequestId: String = "",
    val revocationReason: String = "",
    val revokedAt: String = "",
    val revokedByUserId: String = "",
    val sourceReminderId: String = "",
    val ledgerEntryId: String = "",
    val invoiceKey: String = "",
    val extraFields: Map<String, String> = emptyMap()
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("code", code); put("movementType", movementType); put("purchaseDate", purchaseDate)
        put("descriptionBase", descriptionBase); put("descriptionBaseId", descriptionBaseId.ifBlank { descriptionBase })
        put("project", project); put("description", description); put("area", area); put("liquidationType", liquidationType)
        put("costCenter", costCenter); put("secondaryCost", secondaryCost)
        put("client", client); put("supplier", supplier); put("supplierRuc", supplierRuc); put("supplierEmail", supplierEmail)
        put("documentType", documentType); put("invoiceNumber", invoiceNumber); put("paymentMethod", paymentMethod)
        put("evidenceStatus", evidenceStatus); put("observations", observations); put("amount", amount); put("currency", currency)
        put("accountUserId", accountUserId); put("accountUsername", accountUsername); put("username", accountUsername); put("userEmail", userEmail)
        put("createdByUserId", createdByUserId); put("createdByName", createdByName); put("groupName", groupName)
        put("attachments", attachmentsToJson(attachments)); put("attachmentHash", attachmentHash); put("duplicateReview", duplicateReview)
        put("reviewStatus", reviewStatus); put("recordStatus", recordStatus); put("revocationRequestId", revocationRequestId)
        put("revocationReason", revocationReason); put("revokedAt", revokedAt); put("revokedByUserId", revokedByUserId)
        put("sourceReminderId", sourceReminderId); put("ledgerEntryId", ledgerEntryId); put("invoiceKey", invoiceKey)
        put("extraFields", JSONObject().also { obj -> extraFields.forEach { (key, value) -> obj.put(key, value) } })
        put("createdAt", createdAt); put("updatedAt", updatedAt); put("syncStatus", syncStatus); put("syncError", syncError)
    }
    companion object {
        fun fromJson(j: JSONObject) = Transaction(
            id = j.s("id"), code = j.s("code"), movementType = j.s("movementType", "Gasto"), purchaseDate = j.s("purchaseDate"),
            descriptionBase = j.s("descriptionBase"), project = j.s("project"), description = j.s("description"), area = j.s("area"),
            liquidationType = j.s("liquidationType"), costCenter = j.s("costCenter"),
            secondaryCost = j.s("secondaryCost"), client = j.s("client"), supplier = j.s("supplier"),
            supplierRuc = j.s("supplierRuc"), supplierEmail = j.s("supplierEmail"), documentType = j.s("documentType", "FACTURA"), invoiceNumber = j.s("invoiceNumber"),
            paymentMethod = j.s("paymentMethod"), evidenceStatus = j.s("evidenceStatus"), observations = j.s("observations"), amount = j.d("amount"),
            accountUserId = j.s("accountUserId", j.s("userId")), accountUsername = j.s("accountUsername", j.s("username")),
            createdByUserId = j.s("createdByUserId"), createdByName = j.s("createdByName", j.s("username")), groupName = j.s("groupName", "PROYECTOS"),
            attachments = attachmentsFromJson(j.optJSONArray("attachments")), attachmentHash = j.s("attachmentHash"),
            duplicateReview = if (j.opt("duplicateReview") is JSONObject) j.optJSONObject("duplicateReview")?.toString().orEmpty() else j.s("duplicateReview"),
            createdAt = j.s("createdAt"), updatedAt = j.s("updatedAt"), syncStatus = j.s("syncStatus", SYNC_PENDING), syncError = j.s("syncError"),
            currency = j.s("currency", "USD"), userEmail = j.s("userEmail"), descriptionBaseId = j.s("descriptionBaseId"),
            reviewStatus = j.s("reviewStatus", "NUEVO"), recordStatus = j.s("recordStatus", "ACTIVA"),
            revocationRequestId = j.s("revocationRequestId"), revocationReason = j.s("revocationReason"), revokedAt = j.s("revokedAt"), revokedByUserId = j.s("revokedByUserId"),
            sourceReminderId = j.s("sourceReminderId"), ledgerEntryId = j.s("ledgerEntryId"), invoiceKey = j.s("invoiceKey"),
            extraFields = j.optJSONObject("extraFields")?.let { obj ->
                buildMap { obj.keys().forEach { key -> put(key, obj.optString(key, "")) } }
            }.orEmpty()
        )
    }
}

data class Reminder(
    val id: String,
    val userId: String,
    val username: String,
    val reason: String,
    val status: String = "PENDIENTE",
    val nextNotifyAt: String,
    val linkedTransactionId: String = "",
    val cancelReason: String = "",
    val notificationCount: Int = 0,
    val createdAt: String,
    val updatedAt: String,
    val syncStatus: String = SYNC_PENDING,
    val syncError: String = ""
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("userId", userId); put("username", username); put("reason", reason); put("status", status)
        put("nextNotifyAt", nextNotifyAt); put("linkedTransactionId", linkedTransactionId); put("completedTransactionId", linkedTransactionId)
        put("cancelReason", cancelReason); put("cancellationReason", cancelReason); put("notificationCount", notificationCount)
        put("createdAt", createdAt); put("updatedAt", updatedAt); put("syncStatus", syncStatus); put("syncError", syncError)
    }
    companion object {
        fun fromJson(j: JSONObject) = Reminder(
            id = j.s("id"), userId = j.s("userId"), username = j.s("username"), reason = j.s("reason"), status = j.s("status", "PENDIENTE"),
            nextNotifyAt = j.s("nextNotifyAt"), linkedTransactionId = j.s("completedTransactionId", j.s("linkedTransactionId")),
            cancelReason = j.s("cancellationReason", j.s("cancelReason")), notificationCount = j.optInt("notificationCount", 0),
            createdAt = j.s("createdAt"), updatedAt = j.s("updatedAt"), syncStatus = j.s("syncStatus", SYNC_PENDING), syncError = j.s("syncError")
        )
    }
}

data class Message(
    val id: String,
    val code: String,
    val messageType: String,
    val senderUserId: String,
    val senderName: String,
    val recipientUserId: String,
    val recipientName: String,
    val amount: Double = 0.0,
    val project: String = "",
    val reason: String = "",
    val observations: String = "",
    val status: String = "ENVIADO",
    val attachments: List<Attachment> = emptyList(),
    val confirmedByUserId: String = "",
    val confirmedAt: String = "",
    val rejectedReason: String = "",
    val ledgerEntryId: String = "",
    val createdAt: String,
    val updatedAt: String,
    val syncStatus: String = SYNC_PENDING,
    val syncError: String = "",
    val senderEmail: String = "",
    val recipientEmail: String = "",
    val balanceDeclared: Double = 0.0,
    val transferReference: String = "",
    val transferDate: String = "",
    val sourceRequestId: String = "",
    val relatedTransactionId: String = "",
    val responseTransferId: String = "",
    val respondedByUserId: String = "",
    val respondedAt: String = "",
    val editedAt: String = "",
    val editedByUserId: String = "",
    val deletedAt: String = "",
    val deletedByUserId: String = "",
    val extraFields: Map<String, String> = emptyMap()
) {

    val concept: String get() = reason

    fun toJson() = JSONObject().apply {
        put("id", id); put("code", code); put("messageType", messageType); put("senderUserId", senderUserId); put("senderName", senderName)
        put("senderEmail", senderEmail); put("recipientUserId", recipientUserId); put("recipientName", recipientName); put("recipientEmail", recipientEmail)
        put("amount", amount); put("project", project); put("reason", reason); put("concept", reason); put("observations", observations)
        put("balanceDeclared", balanceDeclared); put("transferReference", transferReference); put("reference", transferReference); put("transferDate", transferDate)
        put("sourceRequestId", sourceRequestId); put("relatedTransactionId", relatedTransactionId); put("responseTransferId", responseTransferId); put("respondedByUserId", respondedByUserId); put("respondedAt", respondedAt)
        put("editedAt", editedAt); put("editedByUserId", editedByUserId); put("deletedAt", deletedAt); put("deletedByUserId", deletedByUserId)
        put("extraFields", JSONObject().also { obj -> extraFields.forEach { (key, value) -> obj.put(key, value) } })
        put("status", status); put("attachments", attachmentsToJson(attachments)); put("confirmedByUserId", confirmedByUserId)
        put("confirmedAt", confirmedAt); put("rejectedReason", rejectedReason); put("ledgerEntryId", ledgerEntryId); put("createdAt", createdAt); put("updatedAt", updatedAt)
        put("syncStatus", syncStatus); put("syncError", syncError)
    }

    companion object {
        fun fromJson(j: JSONObject) = Message(
            id = j.s("id"), code = j.s("code"), messageType = j.s("messageType"), senderUserId = j.s("senderUserId"), senderName = j.s("senderName"),
            recipientUserId = j.s("recipientUserId"), recipientName = j.s("recipientName", "Finanzas"), amount = j.d("amount"), project = j.s("project"),
            reason = j.s("reason", j.s("concept")), observations = j.s("observations"), status = j.s("status", "ENVIADO"),
            attachments = attachmentsFromJson(j.optJSONArray("attachments")), confirmedByUserId = j.s("confirmedByUserId"), confirmedAt = j.s("confirmedAt"),
            rejectedReason = j.s("rejectedReason"), ledgerEntryId = j.s("ledgerEntryId"), createdAt = j.s("createdAt"), updatedAt = j.s("updatedAt"),
            syncStatus = j.s("syncStatus", SYNC_PENDING), syncError = j.s("syncError"), senderEmail = j.s("senderEmail"), recipientEmail = j.s("recipientEmail"),
            balanceDeclared = j.d("balanceDeclared"), transferReference = j.s("transferReference", j.s("reference")), transferDate = j.s("transferDate"),
            sourceRequestId = j.s("sourceRequestId"), relatedTransactionId = j.s("relatedTransactionId"), responseTransferId = j.s("responseTransferId"), respondedByUserId = j.s("respondedByUserId"), respondedAt = j.s("respondedAt"),
            editedAt = j.s("editedAt"), editedByUserId = j.s("editedByUserId"), deletedAt = j.s("deletedAt"), deletedByUserId = j.s("deletedByUserId"),
            extraFields = j.optJSONObject("extraFields")?.let { obj -> buildMap { obj.keys().forEach { key -> put(key, obj.optString(key, "")) } } }.orEmpty()
        )
    }
}

data class LedgerEntry(
    val id: String,
    val sourceType: String,
    val sourceId: String,
    val sourceCode: String,
    val userId: String,
    val username: String,
    val direction: String,
    val amount: Double,
    val occurredAt: String,
    val createdAt: String,
    val updatedAt: String,
    val createdByUserId: String,
    val createdBy: String,
    val reason: String,
    val status: String = "PUBLICADO",
    val reversalOf: String = "",
    val syncStatus: String = SYNC_PENDING,
    val syncError: String = ""
) {
    fun signedAmount() = if (direction == "INGRESO") amount else -amount
    fun toJson() = JSONObject().apply {
        put("id", id); put("sourceType", sourceType); put("sourceId", sourceId); put("sourceCode", sourceCode); put("userId", userId); put("username", username)
        put("direction", direction); put("amount", amount); put("occurredAt", occurredAt); put("createdAt", createdAt); put("updatedAt", updatedAt)
        put("createdByUserId", createdByUserId); put("createdBy", createdBy); put("reason", reason); put("status", status); put("reversalOf", reversalOf)
        put("syncStatus", syncStatus); put("syncError", syncError)
    }
    companion object {
        fun fromJson(j: JSONObject) = LedgerEntry(
            j.s("id"), j.s("sourceType"), j.s("sourceId"), j.s("sourceCode"), j.s("userId"), j.s("username"), j.s("direction"), j.d("amount"),
            j.s("occurredAt"), j.s("createdAt"), j.s("updatedAt"), j.s("createdByUserId"), j.s("createdBy"), j.s("reason"),
            j.s("status", "PUBLICADO"), j.s("reversalOf"), j.s("syncStatus", SYNC_PENDING), j.s("syncError")
        )
    }
}

data class AuditEntry(
    val id: String,
    val entityType: String,
    val entityId: String,
    val action: String,
    val actorUserId: String,
    val actorName: String,
    val detail: String,
    val createdAt: String,
    val updatedAt: String,
    val syncStatus: String = SYNC_PENDING,
    val syncError: String = ""
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("entityType", entityType); put("entityId", entityId); put("action", action); put("actorUserId", actorUserId); put("actorName", actorName)
        put("detail", detail); put("createdAt", createdAt); put("updatedAt", updatedAt); put("syncStatus", syncStatus); put("syncError", syncError)
    }
    companion object {
        fun fromJson(j: JSONObject) = AuditEntry(
            j.s("id"), j.s("entityType"), j.s("entityId"), j.s("action"), j.s("actorUserId"), j.s("actorName"), j.s("detail"),
            j.s("createdAt"), j.s("updatedAt"), j.s("syncStatus", SYNC_PENDING), j.s("syncError")
        )
    }
}

data class SupabaseConfig(
    val projectUrl: String = "",
    val publishableKey: String = "",
    val pollSeconds: Int = 20,
    val autoSync: Boolean = true,
    val allowSelfSignUp: Boolean = false,
    val lockUserToEmail: Boolean = true,
    val lastSyncAt: String = "",
    val lastBackendFingerprint: String = ""
) {
    fun toJson() = JSONObject().apply {
        put("projectUrl", projectUrl); put("supabaseUrl", projectUrl)
        put("publishableKey", publishableKey); put("anonKey", publishableKey)
        put("pollSeconds", pollSeconds); put("autoSync", autoSync); put("auto", autoSync)
        put("allowSelfSignUp", allowSelfSignUp); put("lockUserToEmail", lockUserToEmail)
        put("lastSyncAt", lastSyncAt); put("lastBackendFingerprint", lastBackendFingerprint)
    }
    companion object {
        fun fromJson(j: JSONObject?) = if (j == null) SupabaseConfig() else SupabaseConfig(
            projectUrl = j.s("projectUrl", j.s("supabaseUrl")),
            publishableKey = j.s("publishableKey", j.s("anonKey")),
            pollSeconds = j.optInt("pollSeconds", 20),
            autoSync = if (j.has("autoSync")) j.b("autoSync", true) else j.b("auto", true),
            allowSelfSignUp = j.b("allowSelfSignUp"), lockUserToEmail = j.b("lockUserToEmail", true),
            lastSyncAt = j.s("lastSyncAt"), lastBackendFingerprint = j.s("lastBackendFingerprint")
        )
    }
}

data class InfrastructureConfig(
    val primaryDataProvider: String = DATA_BACKEND_SUPABASE,
    val secondaryDataProvider: String = "",
    val preferredBackupProvider: String = BACKUP_NONE,
    val primaryDataProviders: List<String> = listOf(DATA_BACKEND_SUPABASE),
    val backupProviders: List<String> = emptyList(),
    val dailyDriveBackupEnabled: Boolean = false,
    val dailyBackupHour: Int = 23
) {
    fun toJson() = JSONObject().apply {
        put("primaryDataProvider", primaryDataProviders.firstOrNull() ?: primaryDataProvider)
        put("secondaryDataProvider", secondaryDataProvider)
        put("preferredBackupProvider", backupProviders.firstOrNull() ?: preferredBackupProvider)
        put("primaryDataProviders", primaryDataProviders.toJsonArray())
        put("backupProviders", backupProviders.toJsonArray())
        put("dailyDriveBackupEnabled", dailyDriveBackupEnabled)
        put("automaticBackupEnabled", dailyDriveBackupEnabled)
        put("dailyBackupHour", dailyBackupHour)
    }

    companion object {
        fun fromJson(j: JSONObject?) = if (j == null) InfrastructureConfig() else {
            val allowedData = setOf(
                DATA_BACKEND_SUPABASE, DATA_BACKEND_VPS_API, DATA_BACKEND_POSTGRES_API,
                DATA_BACKEND_MYSQL_API, DATA_BACKEND_MARIADB_API, DATA_BACKEND_SQLSERVER_API,
                DATA_BACKEND_GENERIC_REST
            )
            val allowedBackup = setOf(BACKUP_GOOGLE_DRIVE, BACKUP_ONEDRIVE, BACKUP_VPS, BACKUP_LOCAL_ZIP)
            val legacyPrimary = j.s("primaryDataProvider", DATA_BACKEND_SUPABASE).uppercase().takeIf { it in allowedData } ?: DATA_BACKEND_SUPABASE
            val primaries = j.optJSONArray("primaryDataProviders")?.toStringList()?.map { it.uppercase() }?.filter { it in allowedData }?.distinct().orEmpty()
                .ifEmpty { listOf(legacyPrimary) }
            val legacyBackup = j.s("preferredBackupProvider", BACKUP_NONE).uppercase()
            val backups = j.optJSONArray("backupProviders")?.toStringList()?.map { it.uppercase() }?.filter { it in allowedBackup }?.distinct().orEmpty()
                .ifEmpty { if (legacyBackup in allowedBackup) listOf(legacyBackup) else emptyList() }
            InfrastructureConfig(
                primaryDataProvider = primaries.firstOrNull() ?: DATA_BACKEND_SUPABASE,
                secondaryDataProvider = j.s("secondaryDataProvider").uppercase().takeIf { it in allowedData && it != DATA_BACKEND_SUPABASE }.orEmpty(),
                preferredBackupProvider = backups.firstOrNull() ?: BACKUP_NONE,
                primaryDataProviders = primaries,
                backupProviders = backups,
                dailyDriveBackupEnabled = if (j.has("automaticBackupEnabled")) j.b("automaticBackupEnabled") else j.b("dailyDriveBackupEnabled"),
                dailyBackupHour = j.optInt("dailyBackupHour", 23).coerceIn(0, 23)
            )
        }
    }
}

data class AlternateBackendConfig(
    val provider: String = DATA_BACKEND_VPS_API,
    val apiBaseUrl: String = "",
    val healthPath: String = "/health",
    val databaseName: String = "",
    val lastTestAt: String = "",
    val lastTestMessage: String = ""
) {
    fun toJson() = JSONObject().apply {
        put("provider", provider)
        put("apiBaseUrl", apiBaseUrl)
        put("healthPath", healthPath)
        put("databaseName", databaseName)
        put("lastTestAt", lastTestAt)
        put("lastTestMessage", lastTestMessage)
    }
    companion object {
        fun fromJson(j: JSONObject?) = if (j == null) AlternateBackendConfig() else AlternateBackendConfig(
            provider = j.s("provider", DATA_BACKEND_VPS_API).uppercase().let {
                when (it) {
                    DATA_BACKEND_VPS_API, DATA_BACKEND_POSTGRES_API, DATA_BACKEND_MYSQL_API,
                    DATA_BACKEND_MARIADB_API, DATA_BACKEND_SQLSERVER_API, DATA_BACKEND_GENERIC_REST -> it
                    else -> DATA_BACKEND_VPS_API
                }
            },
            apiBaseUrl = j.s("apiBaseUrl"),
            healthPath = j.s("healthPath", "/health").ifBlank { "/health" },
            databaseName = j.s("databaseName"),
            lastTestAt = j.s("lastTestAt"),
            lastTestMessage = j.s("lastTestMessage")
        )
    }
}

data class ExternalBackupConfig(
    val googleDriveTreeUri: String = "",
    val oneDriveTreeUri: String = "",
    val includeFiles: Boolean = true,
    val vpsBaseUrl: String = "",
    val lastBackupAt: String = "",
    val lastBackupTarget: String = "",
    val lastAutomaticBackupAt: String = ""
) {
    fun toJson() = JSONObject().apply {
        put("googleDriveTreeUri", googleDriveTreeUri)
        put("oneDriveTreeUri", oneDriveTreeUri)
        put("includeFiles", includeFiles)
        put("vpsBaseUrl", vpsBaseUrl)
        put("lastBackupAt", lastBackupAt)
        put("lastBackupTarget", lastBackupTarget)
        put("lastAutomaticBackupAt", lastAutomaticBackupAt)
    }
    companion object {
        fun fromJson(j: JSONObject?) = if (j == null) ExternalBackupConfig() else ExternalBackupConfig(
            googleDriveTreeUri = j.s("googleDriveTreeUri"),
            oneDriveTreeUri = j.s("oneDriveTreeUri"),
            includeFiles = j.b("includeFiles", true),
            vpsBaseUrl = j.s("vpsBaseUrl"),
            lastBackupAt = j.s("lastBackupAt"),
            lastBackupTarget = j.s("lastBackupTarget"),
            lastAutomaticBackupAt = j.s("lastAutomaticBackupAt")
        )
    }
}

data class LocalSettings(
    val profileUserId: String = "",
    val notificationsEnabled: Boolean = true,
    val supabase: SupabaseConfig = SupabaseConfig(),
    val infrastructure: InfrastructureConfig = InfrastructureConfig(),
    val alternateBackend: AlternateBackendConfig = AlternateBackendConfig(),
    val backendConnections: List<AlternateBackendConfig> = emptyList(),
    val externalBackup: ExternalBackupConfig = ExternalBackupConfig()
) {
    fun toJson() = JSONObject().apply {
        put("profileUserId", profileUserId)
        put("notificationsEnabled", notificationsEnabled)
        put("supabase", supabase.toJson())
        put("infrastructure", infrastructure.toJson())
        put("alternateBackend", alternateBackend.toJson())
        put("backendConnections", JSONArray().also { a -> backendConnections.forEach { a.put(it.toJson()) } })
        put("externalBackup", externalBackup.toJson())
    }
    companion object {
        fun fromJson(j: JSONObject?) = if (j == null) LocalSettings() else LocalSettings(
            profileUserId = j.s("profileUserId"),
            notificationsEnabled = j.b("notificationsEnabled", true),
            supabase = SupabaseConfig.fromJson(j.optJSONObject("supabase") ?: j.optJSONObject("sync")),
            infrastructure = InfrastructureConfig.fromJson(j.optJSONObject("infrastructure")),
            alternateBackend = AlternateBackendConfig.fromJson(j.optJSONObject("alternateBackend")),
            backendConnections = j.optJSONArray("backendConnections")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(AlternateBackendConfig::fromJson) }
            }.orEmpty(),
            externalBackup = ExternalBackupConfig.fromJson(j.optJSONObject("externalBackup"))
        )
    }
}

data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
    val email: String,
    val authUserId: String
) {
    fun toJson() = JSONObject().apply {
        put("accessToken", accessToken); put("refreshToken", refreshToken); put("expiresAtEpochSeconds", expiresAtEpochSeconds); put("email", email); put("authUserId", authUserId)
    }
    companion object {
        fun fromJson(j: JSONObject?) = if (j == null) null else AuthSession(
            j.s("accessToken"), j.s("refreshToken"), j.optLong("expiresAtEpochSeconds", 0), j.s("email"), j.s("authUserId")
        )
    }
}

data class RemoteEvent(
    val id: String,
    val entityType: String,
    val entityId: String,
    val payload: JSONObject,
    val updatedAt: String
)
