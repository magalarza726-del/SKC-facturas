package com.skcingenieria.facturas.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.skcingenieria.facturas.data.*
import com.skcingenieria.facturas.repository.DuplicateDetected
import com.skcingenieria.facturas.repository.InvoiceDraft
import com.skcingenieria.facturas.repository.CLEANUP_TRANSACTIONS
import com.skcingenieria.facturas.repository.CLEANUP_MESSAGES
import com.skcingenieria.facturas.repository.CLEANUP_REMINDERS
import com.skcingenieria.facturas.repository.CLEANUP_AUDIT
import com.skcingenieria.facturas.ui.theme.SkcPalette
import com.skcingenieria.facturas.util.CodeUtils
import com.skcingenieria.facturas.util.ExcelFieldCatalog
import com.skcingenieria.facturas.util.ExcelTemplateEngine
import com.skcingenieria.facturas.util.InvoiceRules
import com.skcingenieria.facturas.util.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun AppRoot(vm: AppViewModel) {
    val s = vm.snapshot
    val identityReady = s.currentUser() != null

    if (vm.screen != Screen.HOME && vm.screen != Screen.MESSAGES) {
        BackHandler {
            if (vm.screen == Screen.INVOICE && vm.editingInvoiceId != null) vm.cancelInvoiceEdit()
            else vm.navigate(Screen.HOME)
        }
    }

    Scaffold(
        containerColor = SkcPalette.Canvas,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            SkcTopBar(
                user = s.currentUser()?.name.orEmpty(),
                email = s.session?.email.orEmpty(),
                busy = vm.busy,
                onSync = vm::sync
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(SkcPalette.Canvas)) {
            when {
                vm.screen == Screen.SETTINGS -> SettingsScreen(vm)
                vm.screen == Screen.HOME -> HomeScreen(vm)
                !identityReady -> IdentityRequiredScreen(vm)
                vm.screen == Screen.INVOICE -> InvoiceScreen(vm)
                vm.screen == Screen.HISTORY -> HistoryScreen(vm)
                vm.screen == Screen.MESSAGES -> MessagesScreen(vm)
                vm.screen == Screen.REMINDERS -> RemindersScreen(vm)
            }
            if (vm.screen != Screen.HOME && vm.screen != Screen.MESSAGES) {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(34.dp).clickable {
                        if (vm.screen == Screen.INVOICE && vm.editingInvoiceId != null) vm.cancelInvoiceEdit()
                        else vm.navigate(Screen.HOME)
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, SkcPalette.Line),
                    shadowElevation = 2.dp
                ) { Box(contentAlignment = Alignment.Center) { Text("←", fontSize = 18.sp, color = SkcPalette.Blue) } }
            }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }

    vm.notice.takeIf { it.isNotBlank() }?.let { message ->
        AlertDialog(onDismissRequest = vm::clearMessages, title = { Text("SKC Facturas") }, text = { Text(message) }, confirmButton = { TextButton(onClick = vm::clearMessages) { Text("OK") } })
    }
    vm.error.takeIf { it.isNotBlank() }?.let { message ->
        AlertDialog(onDismissRequest = vm::clearMessages, title = { Text("Ocurrió un problema") }, text = { Text(message) }, confirmButton = { TextButton(onClick = vm::clearMessages) { Text("Cerrar") } })
    }
    vm.duplicate?.let { DuplicateDialog(it, vm::confirmDuplicate, vm::dismissDuplicate) }
}

@Composable
private fun DuplicateDialog(duplicate: DuplicateDetected, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Posible duplicado") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Encontramos ${duplicate.matches.size} registro(s) similar(es). Revisa antes de continuar.")
                duplicate.matches.take(3).forEach { tx -> Text("• ${tx.purchaseDate} · ${tx.accountUsername} · ${money(tx.amount)} · ${tx.descriptionBase}", style = MaterialTheme.typography.bodySmall) }
                if (duplicate.requiresJustification) OutlinedTextField(reason, { reason = it }, label = { Text("Justificación") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = { onConfirm(reason) }, enabled = !duplicate.requiresJustification || reason.trim().length >= 3) { Text("Guardar de todos modos") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Revisar") } }
    )
}

@Composable
private fun HomeScreen(vm: AppViewModel) {
    val s = vm.snapshot
    val user = s.currentUser()
    val rows = if (user != null) s.balanceRows().filter { it.user.active || it.balance != 0.0 } else emptyList()
    val revocable = if (user == null) emptyList() else s.transactions
        .filter { tx -> tx.recordStatus == "ACTIVA" && (s.isAdmin() || tx.createdByUserId == user.id || tx.accountUserId == user.id) }
        .sortedByDescending { it.updatedAt }

    var revokeOpen by remember { mutableStateOf(false) }
    var revokeTarget by remember { mutableStateOf<Transaction?>(null) }

    MobilePage("Inicio") {
        if (user != null) {
            ModuleGrid(buildList {
                add(ModuleAction("▤", "Subir factura", onClick = { vm.navigate(Screen.INVOICE) }))
                add(ModuleAction("✉", "Mensajes", onClick = { vm.navigate(Screen.MESSAGES) }, tone = "green"))
                add(ModuleAction("▦", "Historial", onClick = { vm.navigate(Screen.HISTORY) }, tone = "green"))
                add(ModuleAction("⏰", "Recordatorios", onClick = { vm.navigate(Screen.REMINDERS) }, tone = "orange"))
                add(ModuleAction("⚙", "Configuración", onClick = { vm.navigate(Screen.SETTINGS) }))
            })

            BalanceLiveCard(rows, "Flujo")

            MobileCard {
                DownloadExcelButton(vm, "invoice", "Descargar Excel de Facturas")
                DownloadExcelButton(vm, "transfer", "Descargar Excel de Transferencias")
                if (revocable.isNotEmpty()) {
                    OutlinedButton(onClick = { revokeOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("Revocar factura") }
                }
            }
        } else {
            ModuleGrid(listOf(ModuleAction("⚙", "Iniciar sesión", onClick = { vm.navigate(Screen.SETTINGS) })))
        }
    }

    if (revokeOpen) {
        var selected by remember(revocable) { mutableStateOf(revocable.firstOrNull()?.id.orEmpty()) }
        AlertDialog(
            onDismissRequest = { revokeOpen = false },
            title = { Text("Revocar factura") },
            text = {
                SelectField(
                    "Factura",
                    selected,
                    revocable.map { it.id to "${it.supplier.ifBlank { it.code }} · ${money(it.amount)}" }
                ) { selected = it }
            },
            confirmButton = {
                Button(onClick = { revokeTarget = revocable.firstOrNull { it.id == selected }; revokeOpen = false }) { Text("Continuar") }
            },
            dismissButton = { TextButton(onClick = { revokeOpen = false }) { Text("Cancelar") } }
        )
    }

    revokeTarget?.let { tx ->
        var reason by remember(tx.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text("Revocar factura") },
            text = { OutlinedTextField(reason, { reason = it }, label = { Text("Motivo") }, modifier = Modifier.fillMaxWidth(), minLines = 2) },
            confirmButton = {
                Button(
                    onClick = { vm.revokeInvoice(tx.id, reason); revokeTarget = null },
                    enabled = reason.trim().length >= 3
                ) { Text("Revocar") }
            },
            dismissButton = { TextButton(onClick = { revokeTarget = null }) { Text("Cancelar") } }
        )
    }
}

private fun AppConfig.field(module: String, key: String): FormFieldConfig = fields(module).firstOrNull { it.key == key } ?: FormFieldConfig(key, key, false)

private fun configuredDefault(s: com.skcingenieria.facturas.repository.AppSnapshot, module: String, key: String): String {
    val token = s.config.field(module, key).defaultValue
    if (!token.startsWith("@")) return token
    val firstProject = s.catalogs.projects.firstOrNull()
    return when (token) {
        "@today" -> TimeUtils.todayIso()
        "@currentUser" -> s.currentUser()?.id.orEmpty()
        "@firstProject" -> firstProject?.id.orEmpty()
        "@firstProject2" -> firstProject?.project2Options?.firstOrNull().orEmpty()
        "@projectDescription" -> firstProject?.description.orEmpty()
        "@normalEvidence" -> s.catalogs.evidenceStatuses.firstOrNull { !it.contains("sin comprobante", true) }.orEmpty()
        "@noEvidence" -> s.catalogs.evidenceStatuses.firstOrNull { it.contains("sin comprobante", true) }.orEmpty()
        "@firstCostCenter" -> s.catalogs.costCenters.firstOrNull().orEmpty()
        "@firstSecondaryCost" -> s.catalogs.secondaryCostsByCenter[s.catalogs.costCenters.firstOrNull()].orEmpty().firstOrNull()
            ?: s.catalogs.secondaryCosts.firstOrNull().orEmpty()
        "@firstPaymentMethod" -> s.catalogs.paymentMethods.firstOrNull().orEmpty()
        "@currentBalance" -> (s.balances()[s.currentUser()?.id] ?: 0.0).toString()
        else -> ""
    }
}

private fun isSupplierFormField(field: FormFieldConfig): Boolean =
    field.key == "supplier" || (field.core && field.label.contains("proveedor", ignoreCase = true))

@Composable
private fun rememberSupplierResults(
    catalogs: Catalogs,
    query: String,
    limit: Int,
    previewWhenBlank: Boolean = false
): List<SupplierDefinition> {
    var results by remember(catalogs.suppliers) { mutableStateOf<List<SupplierDefinition>>(emptyList()) }

    LaunchedEffect(query, catalogs.suppliers, limit, previewWhenBlank) {
        val cleanQuery = query.trim()
        if (cleanQuery.isNotBlank()) delay(70)
        results = withContext(Dispatchers.Default) {
            if (cleanQuery.isBlank()) {
                if (previewWhenBlank) catalogs.supplierPreview(limit) else emptyList()
            } else {
                catalogs.supplierCandidates(cleanQuery, limit)
            }
        }
    }
    return results
}

@Composable
private fun SupplierAutocompleteField(
    label: String,
    value: String,
    ruc: String,
    email: String,
    catalogs: Catalogs,
    onValueChange: (String) -> Unit,
    onSupplierSelected: (SupplierDefinition) -> Unit,
    onNewSupplier: (SupplierDefinition) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    var createOpen by remember { mutableStateOf(false) }
    var showMatches by remember { mutableStateOf(false) }
    val matches = rememberSupplierResults(catalogs = catalogs, query = value, limit = 8)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { typed ->
                // Campo deliberadamente editable: cada tecla actualiza el texto y filtra el directorio.
                onValueChange(typed)
                showMatches = typed.isNotBlank()
            },
            label = { Text(label) },
            placeholder = { Text("Buscar proveedor") },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    focused = state.isFocused
                    showMatches = state.isFocused && value.isNotBlank()
                },
            singleLine = true,
            readOnly = false,
            enabled = true
        )

        if (focused && showMatches && matches.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color.White,
                border = BorderStroke(1.dp, SkcPalette.Line),
                shadowElevation = 2.dp
            ) {
                Column {
                    matches.forEachIndexed { index, supplier ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSupplierSelected(supplier)
                                    showMatches = false
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp)
                        ) {
                            Text(
                                supplier.name,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val linked = listOfNotNull(
                                supplier.ruc.takeIf { it.isNotBlank() }?.let { "RUC $it" },
                                supplier.email.takeIf { it.isNotBlank() }
                            ).joinToString(" · ")
                            if (linked.isNotBlank()) {
                                Text(
                                    linked,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (index != matches.lastIndex) HorizontalDivider(color = SkcPalette.Line)
                    }
                }
            }
        }

        val linked = listOfNotNull(
            ruc.takeIf { it.isNotBlank() }?.let { "RUC $it" },
            email.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
        if (linked.isNotBlank()) {
            Text(
                linked,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = { createOpen = true }) { Text("+ Nuevo proveedor") }
    }
    if (createOpen) {
        var newName by remember(createOpen) { mutableStateOf(value.trim()) }
        var newRuc by remember(createOpen) { mutableStateOf("") }
        var newEmail by remember(createOpen) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { createOpen = false },
            title = { Text("Nuevo proveedor") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Nombre, RUC y correo son necesarios. Si eres usuario, se enviará la solicitud a un administrador; si eres ADMIN, se crea directamente.", fontSize = 10.sp, color = SkcPalette.Muted)
                    OutlinedTextField(newName, { newName = it }, label = { Text("Proveedor") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(newRuc, { newRuc = it }, label = { Text("RUC") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(newEmail, { newEmail = it }, label = { Text("Correo") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val supplier = SupplierDefinition("pending-${CodeUtils.uuid()}", newName.trim(), newRuc.trim(), newEmail.trim())
                        onSupplierSelected(supplier)
                        onNewSupplier(supplier)
                        createOpen = false
                    },
                    enabled = newName.trim().length >= 2 && newRuc.trim().length >= 5 && newEmail.contains("@")
                ) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { createOpen = false }) { Text("Cancelar") } }
        )
    }
}


private fun linkedColumnValueKey(field: FormFieldConfig, column: LinkedTableColumnConfig): String {
    if (column.targetKey.isNotBlank()) return column.targetKey
    val slug = CodeUtils.slug(column.sourceColumn).replace("-", "_").ifBlank { "nivel_${column.order + 1}" }
    return "${field.key}__$slug"
}

private fun normalizeLookup(value: String): String = java.text.Normalizer
    .normalize(value.trim().uppercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
    .replace("\\p{Mn}+".toRegex(), "")

private fun distinctColumnValues(rows: List<Map<String, String>>, column: String): List<String> {
    val seen = mutableSetOf<String>()
    return rows.map { it[column].orEmpty().trim() }
        .filter(String::isNotBlank)
        .filter { seen.add(normalizeLookup(it)) }
}

private fun linkedTableRowsForLevel(
    field: FormFieldConfig,
    values: Map<String, String>,
    subcategoryIndexExclusive: Int
): List<Map<String, String>> {
    val table = field.linkedTable
    val primary = table.primary() ?: return emptyList()
    val primaryValue = values[field.key].orEmpty().trim()
    var rows = table.rows
    if (primaryValue.isNotBlank()) rows = rows.filter { it[primary.sourceColumn].orEmpty().equals(primaryValue, ignoreCase = true) }
    table.subcategories().take(subcategoryIndexExclusive).forEach { column ->
        val selected = values[linkedColumnValueKey(field, column)].orEmpty().trim()
        if (selected.isNotBlank()) rows = rows.filter { it[column.sourceColumn].orEmpty().equals(selected, ignoreCase = true) }
    }
    return rows
}

private fun applyLinkedTableValues(field: FormFieldConfig, source: Map<String, String>): Map<String, String> {
    val table = field.linkedTable
    val next = source.toMutableMap()
    table.linked().forEach { next[linkedColumnValueKey(field, it)] = "" }
    if (!table.isUsable()) return next
    val primary = table.primary() ?: return next
    val primaryValue = next[field.key].orEmpty().trim()
    if (primaryValue.isBlank()) return next
    var rows = table.rows.filter { it[primary.sourceColumn].orEmpty().equals(primaryValue, ignoreCase = true) }
    val subcategories = table.subcategories()
    for (column in subcategories) {
        val selected = next[linkedColumnValueKey(field, column)].orEmpty().trim()
        if (selected.isBlank()) return next
        rows = rows.filter { it[column.sourceColumn].orEmpty().equals(selected, ignoreCase = true) }
    }
    val row = rows.firstOrNull() ?: return next
    table.linked().forEach { column ->
        next[linkedColumnValueKey(field, column)] = row[column.sourceColumn].orEmpty()
    }
    return next
}

private fun clearLinkedTableAfterLevel(
    field: FormFieldConfig,
    source: Map<String, String>,
    level: Int
): Map<String, String> {
    val next = source.toMutableMap()
    field.linkedTable.subcategories().drop(level).forEach { next[linkedColumnValueKey(field, it)] = "" }
    field.linkedTable.linked().forEach { next[linkedColumnValueKey(field, it)] = "" }
    return next
}

private fun linkedTableAmbiguityCount(table: LinkedTableConfig): Int {
    val primary = table.primary() ?: return 0
    val keyColumns = listOf(primary) + table.subcategories()
    if (keyColumns.isEmpty()) return 0
    return table.rows.asSequence()
        .filter { row -> row[primary.sourceColumn].orEmpty().isNotBlank() }
        .groupingBy { row -> keyColumns.joinToString("\u001F") { column -> normalizeLookup(row[column.sourceColumn].orEmpty()) } }
        .eachCount()
        .count { (_, count) -> count > 1 }
}

private fun linkedTableTargetConflicts(table: LinkedTableConfig): List<String> =
    (table.subcategories() + table.linked())
        .map { it.targetKey.trim() }
        .filter(String::isNotBlank)
        .groupingBy { it }
        .eachCount()
        .filterValues { it > 1 }
        .keys
        .sorted()

private fun linkedTableComplete(field: FormFieldConfig, values: Map<String, String>): Boolean {
    val table = field.linkedTable
    if (!table.isUsable()) return values[field.key].orEmpty().isNotBlank()
    val primary = table.primary() ?: return false
    val primaryValue = values[field.key].orEmpty().trim()
    if (primaryValue.isBlank()) return false
    var rows = table.rows.filter { it[primary.sourceColumn].orEmpty().equals(primaryValue, ignoreCase = true) }
    if (rows.isEmpty()) return false
    for (column in table.subcategories()) {
        val selected = values[linkedColumnValueKey(field, column)].orEmpty().trim()
        if (selected.isBlank()) return false
        rows = rows.filter { it[column.sourceColumn].orEmpty().equals(selected, ignoreCase = true) }
        if (rows.isEmpty()) return false
    }
    return true
}

private fun resolveLinkedTableValues(fields: List<FormFieldConfig>, source: Map<String, String>): Map<String, String> =
    fields.filter { it.fieldType == FIELD_LINKED_SELECT && it.linkedTable.isUsable() }
        .fold(source) { values, field -> applyLinkedTableValues(field, values) }

@Composable
private fun SearchableChoiceField(
    label: String,
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    onSelected: (String) -> Unit,
    enabled: Boolean = true
) {
    var focused by remember { mutableStateOf(false) }
    var showMatches by remember { mutableStateOf(false) }
    var matches by remember(options) { mutableStateOf(options.take(8)) }

    LaunchedEffect(value, options) {
        val query = value.trim()
        if (query.isNotBlank()) delay(60)
        matches = withContext(Dispatchers.Default) {
            if (query.isBlank()) options.take(8)
            else {
                val q = normalizeLookup(query)
                options.asSequence()
                    .map { it to normalizeLookup(it) }
                    .filter { (_, normalized) -> normalized.contains(q) }
                    .sortedWith(compareBy<Pair<String, String>> { (_, normalized) -> if (normalized.startsWith(q)) 0 else 1 }.thenBy { it.first.lowercase() })
                    .take(8)
                    .map { it.first }
                    .toList()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { typed -> if (enabled) { onValueChange(typed); showMatches = true } },
            label = { Text(label) },
            placeholder = { Text(if (enabled) "Escribe para buscar" else "Selecciona el nivel anterior") },
            modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
                focused = state.isFocused
                if (enabled && state.isFocused) showMatches = true
            },
            singleLine = true,
            enabled = enabled
        )
        if (enabled && focused && showMatches && matches.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color.White,
                border = BorderStroke(1.dp, SkcPalette.Line),
                shadowElevation = 2.dp
            ) {
                Column {
                    matches.forEachIndexed { index, option ->
                        Text(
                            option,
                            modifier = Modifier.fillMaxWidth().clickable {
                                onSelected(option)
                                showMatches = false
                            }.padding(horizontal = 12.dp, vertical = 10.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (index != matches.lastIndex) HorizontalDivider(color = SkcPalette.Line)
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkedTableFormField(
    field: FormFieldConfig,
    values: Map<String, String>,
    onValuesChange: (Map<String, String>) -> Unit
) {
    val table = field.linkedTable
    val primary = table.primary() ?: return
    val primaryOptions = remember(table.rows, primary.sourceColumn) { distinctColumnValues(table.rows, primary.sourceColumn) }

    SearchableChoiceField(
        label = field.label,
        value = values[field.key].orEmpty(),
        options = primaryOptions,
        onValueChange = { typed ->
            val next = clearLinkedTableAfterLevel(field, values.toMutableMap().apply { put(field.key, typed) }, 0)
            onValuesChange(next)
        },
        onSelected = { selected ->
            val next = clearLinkedTableAfterLevel(field, values.toMutableMap().apply { put(field.key, selected) }, 0)
            onValuesChange(applyLinkedTableValues(field, next))
        }
    )

    val subcategories = table.subcategories()
    subcategories.forEachIndexed { index, column ->
        val key = linkedColumnValueKey(field, column)
        val previousReady = values[field.key].orEmpty().isNotBlank() && subcategories.take(index).all { previous ->
            values[linkedColumnValueKey(field, previous)].orEmpty().isNotBlank()
        }
        val eligibleRows = if (previousReady) linkedTableRowsForLevel(field, values, index) else emptyList()
        val options = remember(eligibleRows, column.sourceColumn) { distinctColumnValues(eligibleRows, column.sourceColumn) }
        SearchableChoiceField(
            label = column.label.ifBlank { column.sourceColumn },
            value = values[key].orEmpty(),
            options = options,
            onValueChange = { typed ->
                val base = values.toMutableMap().apply { put(key, typed) }
                val next = clearLinkedTableAfterLevel(field, base, index + 1)
                onValuesChange(next)
            },
            onSelected = { selected ->
                val base = values.toMutableMap().apply { put(key, selected) }
                val next = clearLinkedTableAfterLevel(field, base, index + 1)
                onValuesChange(applyLinkedTableValues(field, next))
            },
            enabled = previousReady && options.isNotEmpty()
        )
    }

    val resolved = run {
        if (!linkedTableComplete(field, values)) null
        else {
            var rows = table.rows.filter { it[primary.sourceColumn].orEmpty().equals(values[field.key].orEmpty(), ignoreCase = true) }
            subcategories.forEach { column ->
                val selected = values[linkedColumnValueKey(field, column)].orEmpty()
                rows = rows.filter { it[column.sourceColumn].orEmpty().equals(selected, ignoreCase = true) }
            }
            rows.firstOrNull()
        }
    }
    val visibleLinked = table.linked().filter { it.visible && it.sourceColumn.isNotBlank() }
    if (resolved != null && visibleLinked.isNotEmpty()) {
        val text = visibleLinked.mapNotNull { column ->
            resolved[column.sourceColumn].orEmpty().takeIf(String::isNotBlank)?.let { "${column.label.ifBlank { column.sourceColumn }}: $it" }
        }.joinToString(" · ")
        if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InvoiceScreen(vm: AppViewModel) {
    val s = vm.snapshot
    val cfg = s.config
    val fields = cfg.fields("invoice").filter { it.visible }.sortedBy { it.order }
    val evidenceRequired = fields.any { (it.fieldType == FIELD_EVIDENCE || it.key == "attachments") && it.required }
    val current = s.currentUser()
    val editing = vm.editingInvoice()
    val editKey = editing?.id ?: "new"

    val knownKeys = setOf(
        "movementType", "amount", "accountUserId", "purchaseDate", "descriptionBase", "project", "description",
        "area", "liquidationType", "evidenceStatus", "supplier", "supplierRuc", "supplierEmail", "documentType",
        "invoiceNumber", "costCenter", "secondaryCost", "client", "paymentMethod", "observations", "attachments"
    )

    var values by remember(editKey, cfg.formsUpdatedAt, cfg.updatedAt) {
        mutableStateOf(buildMap {
            put("movementType", editing?.movementType ?: configuredDefault(s, "invoice", "movementType").ifBlank { "Gasto" })
            put("amount", editing?.amount?.toString() ?: configuredDefault(s, "invoice", "amount"))
            put("accountUserId", editing?.accountUserId ?: configuredDefault(s, "invoice", "accountUserId").ifBlank { current?.id.orEmpty() })
            put("purchaseDate", editing?.purchaseDate ?: configuredDefault(s, "invoice", "purchaseDate").ifBlank { TimeUtils.todayIso() })
            put("descriptionBase", editing?.descriptionBase ?: configuredDefault(s, "invoice", "descriptionBase"))
            put("project", editing?.project ?: configuredDefault(s, "invoice", "project"))
            put("description", editing?.description ?: vm.invoicePrefill.ifBlank { configuredDefault(s, "invoice", "description") })
            put("area", editing?.area ?: configuredDefault(s, "invoice", "area"))
            put("liquidationType", editing?.liquidationType ?: configuredDefault(s, "invoice", "liquidationType"))
            put("evidenceStatus", editing?.evidenceStatus ?: configuredDefault(s, "invoice", "evidenceStatus").ifBlank { if (evidenceRequired) "Comprobante adjunto" else "Compra sin comprobante" })
            put("supplier", editing?.supplier ?: configuredDefault(s, "invoice", "supplier"))
            put("supplierRuc", editing?.supplierRuc.orEmpty())
            put("supplierEmail", editing?.supplierEmail.orEmpty())
            put("documentType", editing?.documentType ?: "FACTURA")
            put("invoiceNumber", editing?.invoiceNumber ?: configuredDefault(s, "invoice", "invoiceNumber"))
            put("costCenter", editing?.costCenter ?: configuredDefault(s, "invoice", "costCenter"))
            put("secondaryCost", editing?.secondaryCost ?: configuredDefault(s, "invoice", "secondaryCost"))
            put("client", editing?.client ?: configuredDefault(s, "invoice", "client"))
            put("paymentMethod", editing?.paymentMethod ?: configuredDefault(s, "invoice", "paymentMethod"))
            put("observations", editing?.observations ?: configuredDefault(s, "invoice", "observations"))
            editing?.extraFields?.forEach { (k, v) -> put(k, v) }
            fields.forEach { field -> if (!containsKey(field.key)) put(field.key, configuredDefault(s, "invoice", field.key)) }
        })
    }
    fun setValue(key: String, value: String) {
        values = values.toMutableMap().apply { put(key, value) }
    }
    fun setSupplierValue(fieldKey: String, value: String) {
        // Al escribir no recorremos el catálogo buscando coincidencia exacta.
        // Los datos vinculados se restauran al elegir una sugerencia (o al guardar si el texto es exacto).
        values = values.toMutableMap().apply {
            put(fieldKey, value)
            put("supplier", value)
            put("supplierRuc", "")
            put("supplierEmail", "")
        }
    }
    fun selectSupplier(fieldKey: String, supplier: SupplierDefinition) {
        values = values.toMutableMap().apply {
            put(fieldKey, supplier.name)
            put("supplier", supplier.name)
            put("supplierRuc", supplier.ruc)
            put("supplierEmail", supplier.email)
        }
    }
    fun applyOption(field: FormFieldConfig, option: FormOptionConfig) {
        val next = values.toMutableMap()
        next[field.key] = option.value
        option.linkedData.forEach { (key, value) -> next[key] = value }
        values = next
    }

    val ctx = LocalContext.current
    val uris = remember(editKey) { mutableStateListOf<Uri>() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked -> uris.addAll(picked) }
    var cameraUri by remember(editKey) { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let { uris.add(it) } }
    fun takePhoto() {
        val file = File(ctx.cacheDir, "camera/${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs(); createNewFile() }
        cameraUri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        camera.launch(cameraUri!!)
    }

    fun optionsFor(field: FormFieldConfig): List<FormOptionConfig> = when {
        field.options.isNotEmpty() -> field.options
        isSupplierFormField(field) -> s.catalogs.suppliers.map { supplier ->
            FormOptionConfig(
                value = supplier.name,
                label = supplier.name,
                linkedData = mapOf("supplierRuc" to supplier.ruc, "supplierEmail" to supplier.email)
            )
        }
        field.key == "accountUserId" -> s.users.filter { it.active }.map { FormOptionConfig(it.id, it.name) }
        field.key == "movementType" -> listOf(FormOptionConfig("Gasto"), FormOptionConfig("Ingreso"))
        field.key == "project" -> s.catalogs.projects.map { FormOptionConfig(it.id.ifBlank { it.description }, it.description.ifBlank { it.id }) }
        field.key == "costCenter" -> s.catalogs.costCenters.map { FormOptionConfig(it) }
        field.key == "secondaryCost" -> s.catalogs.secondaryCosts.map { FormOptionConfig(it) }
        field.key == "paymentMethod" -> s.catalogs.paymentMethods.map { FormOptionConfig(it) }
        field.key == "evidenceStatus" -> s.catalogs.evidenceStatuses.map { FormOptionConfig(it) }
        else -> emptyList()
    }

    fun draft(): InvoiceDraft {
        val resolvedValues = resolveLinkedTableValues(fields, values)
        val supplierFieldKey = fields.firstOrNull(::isSupplierFormField)?.key ?: "supplier"
        val supplierText = resolvedValues[supplierFieldKey].orEmpty().ifBlank { resolvedValues["supplier"].orEmpty() }.trim()
        val catalogSupplier = s.catalogs.supplier(supplierText)
        return InvoiceDraft(
        movementType = resolvedValues["movementType"].orEmpty().ifBlank { "Gasto" },
        descriptionBase = resolvedValues["descriptionBase"].orEmpty(),
        project = resolvedValues["project"].orEmpty(),
        description = resolvedValues["description"].orEmpty(),
        area = resolvedValues["area"].orEmpty(),
        liquidationType = resolvedValues["liquidationType"].orEmpty(),
        accountUserId = resolvedValues["accountUserId"].orEmpty().ifBlank { current?.id.orEmpty() },
        amount = resolvedValues["amount"].orEmpty().toDoubleOrNull() ?: 0.0,
        purchaseDate = resolvedValues["purchaseDate"].orEmpty().ifBlank { TimeUtils.todayIso() },
        evidenceStatus = resolvedValues["evidenceStatus"].orEmpty().ifBlank {
            if ((editing?.attachments?.isNotEmpty() == true) || uris.isNotEmpty()) "Comprobante adjunto"
            else "Compra sin comprobante"
        },
        supplier = catalogSupplier?.name ?: supplierText,
        supplierRuc = resolvedValues["supplierRuc"].orEmpty().ifBlank { catalogSupplier?.ruc.orEmpty() },
        supplierEmail = resolvedValues["supplierEmail"].orEmpty().ifBlank { catalogSupplier?.email.orEmpty() },
        documentType = resolvedValues["documentType"].orEmpty().ifBlank { "FACTURA" },
        invoiceNumber = resolvedValues["invoiceNumber"].orEmpty(),
        costCenter = resolvedValues["costCenter"].orEmpty(),
        secondaryCost = resolvedValues["secondaryCost"].orEmpty(),
        client = resolvedValues["client"].orEmpty(),
        paymentMethod = resolvedValues["paymentMethod"].orEmpty(),
        observations = resolvedValues["observations"].orEmpty(),
        extraFields = resolvedValues.filterKeys { it !in knownKeys }
        )
    }

    val existingEvidence = editing?.attachments?.size ?: 0
    val requiredOk = fields.filter { it.required }.all { field ->
        when {
            field.fieldType == FIELD_EVIDENCE || field.key == "attachments" -> existingEvidence + uris.size > 0
            field.fieldType == FIELD_LINKED_SELECT && field.linkedTable.isUsable() -> linkedTableComplete(field, values)
            else -> values[field.key].orEmpty().isNotBlank()
        }
    }
    val canSave = (values["amount"]?.toDoubleOrNull() ?: 0.0) > 0 && requiredOk

    MobilePage(if (editing == null) "Factura" else "Editar factura") {
        fields.forEach { field ->
            if (field.fieldType == FIELD_LINKED_SELECT && field.linkedTable.isUsable()) {
                LinkedTableFormField(field, values) { updated -> values = updated }
            } else if (isSupplierFormField(field)) {
                SupplierAutocompleteField(
                    label = field.label,
                    value = values[field.key].orEmpty().ifBlank { values["supplier"].orEmpty() },
                    ruc = values["supplierRuc"].orEmpty(),
                    email = values["supplierEmail"].orEmpty(),
                    catalogs = s.catalogs,
                    onValueChange = { typed -> setSupplierValue(field.key, typed) },
                    onSupplierSelected = { supplier -> selectSupplier(field.key, supplier) },
                    onNewSupplier = { supplier -> vm.requestSupplier(supplier.name, supplier.ruc, supplier.email) }
                )
            } else when (field.fieldType) {
                FIELD_EVIDENCE -> EvidencePicker(uris, { picker.launch(arrayOf("image/*", "application/pdf")) }, ::takePhoto)
                FIELD_SELECT, FIELD_LINKED_SELECT, FIELD_COMPOSITE_SELECT -> {
                    val options = optionsFor(field).filter { option ->
                        field.fieldType != FIELD_COMPOSITE_SELECT || field.parentKey.isBlank() || option.parentValue.isBlank() || option.parentValue == values[field.parentKey].orEmpty()
                    }
                    if (options.isEmpty()) {
                        OutlinedTextField(values[field.key].orEmpty(), { setValue(field.key, it) }, label = { Text(field.label) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    } else {
                        SelectField(field.label, values[field.key].orEmpty(), options.map { it.value to it.label }) { selected ->
                            val option = options.firstOrNull { it.value == selected } ?: FormOptionConfig(selected)
                            applyOption(field, option)
                        }
                    }
                }
                else -> OutlinedTextField(
                    values[field.key].orEmpty(),
                    { value -> setValue(field.key, value) },
                    label = { Text(field.label) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = field.key !in setOf("description", "observations", "reason")
                )
            }
        }

        Button(
            onClick = { if (editing == null) vm.saveInvoice(draft(), uris.toList()) else vm.updateInvoice(draft(), uris.toList()) },
            enabled = canSave && !vm.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (editing == null) "Guardar factura" else "Guardar cambios") }

        if (editing != null) {
            TextButton(onClick = vm::cancelInvoiceEdit, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
        }
    }
}


@Composable
private fun SuggestionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, SkcPalette.Line),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), content = content)
    }
}

@Composable
private fun SuggestionRow(title: String, subtitle: String = "", onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
        if (subtitle.isNotBlank()) Text(subtitle, color = SkcPalette.Muted, fontSize = 9.sp)
    }
}

@Composable
private fun EvidencePicker(uris: MutableList<Uri>, onFiles: () -> Unit, onCamera: () -> Unit) {
    Surface(color = Color(0xFFFBFDFF), shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, SkcPalette.Blue.copy(alpha = .65f))) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("▧", color = SkcPalette.Blue, fontSize = 22.sp)
            Text("Fotos, comprobantes o PDF", color = SkcPalette.Blue, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedButton(onClick = onCamera) { Text("Cámara", fontSize = 9.sp) }
                OutlinedButton(onClick = onFiles) { Text("Galería / PDF", fontSize = 9.sp) }
            }
            uris.forEachIndexed { index, uri ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${index + 1}. ${uri.lastPathSegment ?: "evidencia"}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(240.dp), fontSize = 9.sp)
                    TextButton(onClick = { uris.remove(uri) }) { Text("Quitar", fontSize = 8.sp) }
                }
            }
        }
    }
}

@Composable
private fun DownloadExcelButton(vm: AppViewModel, kind: String, label: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var payload by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        val data = payload
        if (uri != null && data != null) {
            runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(data.second) } }
                .onFailure { localError = it.message ?: "No se pudo guardar el Excel" }
        }
        payload = null
    }
    OutlinedButton(
        onClick = {
            scope.launch {
                loading = true
                runCatching { if (kind == "transfer") vm.transferExcelDownload() else vm.invoiceExcelDownload() }
                    .onSuccess { result -> payload = result; launcher.launch(result.first) }
                    .onFailure { localError = it.message ?: "No se pudo generar el Excel" }
                loading = false
            }
        },
        enabled = !loading,
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (loading) "Generando…" else label) }
    if (localError.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { localError = "" },
            title = { Text("Excel") },
            text = { Text(localError) },
            confirmButton = { TextButton(onClick = { localError = "" }) { Text("Cerrar") } }
        )
    }
}

private data class HistoryRow(
    val key: String,
    val type: String,
    val amount: Double,
    val sortAt: String,
    val transaction: Transaction? = null,
    val message: Message? = null
)

@Composable
private fun HistoryScreen(vm: AppViewModel) {
    val s = vm.snapshot
    val admin = s.isAdmin()
    var detail by remember { mutableStateOf<HistoryRow?>(null) }
    var evidenceDetail by remember { mutableStateOf<List<Attachment>?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var correctionOpen by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }

    val rows = buildList {
        vm.visibleTransactions().forEach { tx ->
            add(HistoryRow("invoice:${tx.id}", "Factura", tx.amount, tx.updatedAt.ifBlank { tx.createdAt }, transaction = tx))
        }
        vm.visibleMessages().filter { it.messageType in setOf("TRANSFERENCIA", "SOLICITUD_MONTO") }.forEach { message ->
            val label = if (message.messageType == "TRANSFERENCIA") "Transferencia" else "Solicitud"
            add(HistoryRow("message:${message.id}", label, message.amount, message.updatedAt.ifBlank { message.createdAt }, message = message))
        }
    }.sortedByDescending { it.sortAt }

    MobilePage("Historial") {
        if (admin) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { correctionOpen = true }) { Text("+ Corrección") }
                TextButton(onClick = {
                    selectionMode = !selectionMode
                    if (!selectionMode) selected.clear()
                }) { Text(if (selectionMode) "Cancelar" else "Seleccionar") }
            }
        }

        rows.forEach { row ->
            val tx = row.transaction
            val msg = row.message
            MobileCard(modifier = Modifier.fillMaxWidth().clickable(enabled = !selectionMode) { detail = row }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (selectionMode && admin) {
                        Checkbox(
                            checked = row.key in selected,
                            onCheckedChange = { checked -> if (checked) selected.add(row.key) else selected.remove(row.key) }
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(row.type, fontWeight = FontWeight.Bold)
                        val line = when {
                            tx != null -> listOf(tx.purchaseDate, tx.supplier.ifBlank { tx.accountUsername }, tx.recordStatus).filter { it.isNotBlank() }.joinToString(" · ")
                            msg != null -> listOf(msg.transferDate.ifBlank { msg.createdAt.take(10) }, "${msg.senderName} → ${msg.recipientName.ifBlank { msg.recipientEmail }}", msg.status).filter { it.isNotBlank() }.joinToString(" · ")
                            else -> ""
                        }
                        if (line.isNotBlank()) Text(line, fontSize = 9.sp, color = SkcPalette.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(money(row.amount), fontWeight = FontWeight.Bold, color = SkcPalette.Blue)
                }
            }
        }
        if (rows.isEmpty()) Text("Sin movimientos", color = SkcPalette.Muted)
        if (selectionMode && selected.isNotEmpty()) {
            Button(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = SkcPalette.Red)
            ) { Text("Eliminar (${selected.size})") }
        }
    }

    if (correctionOpen && admin) {
        val users = s.users.filter { it.active }
        var userId by remember { mutableStateOf(users.firstOrNull()?.id.orEmpty()) }
        var direction by remember { mutableStateOf("EGRESO") }
        var amount by remember { mutableStateOf("") }
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { correctionOpen = false },
            title = { Text("Corrección administrativa") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Crea un ajuste trazable para corregir el flujo de ingreso o gasto de un usuario.", fontSize = 10.sp, color = SkcPalette.Muted)
                    SelectField("Usuario", userId, users.map { it.id to it.name }) { userId = it }
                    SelectField("Tipo", direction, listOf("EGRESO" to "Gasto / egreso", "INGRESO" to "Ingreso")) { direction = it }
                    OutlinedTextField(amount, { amount = it }, label = { Text("Monto") }, prefix = { Text("$") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(reason, { reason = it }, label = { Text("Motivo de la corrección") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(
                    onClick = { vm.adjustment(userId, direction, amount.toDoubleOrNull() ?: 0.0, reason); correctionOpen = false },
                    enabled = userId.isNotBlank() && (amount.toDoubleOrNull() ?: 0.0) > 0 && reason.trim().length >= 3
                ) { Text("Aplicar") }
            },
            dismissButton = { TextButton(onClick = { correctionOpen = false }) { Text("Cancelar") } }
        )
    }

    detail?.let { row ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(row.type) },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.transaction?.let { tx ->
                        TechnicalValue("Código", tx.code)
                        TechnicalValue("Monto", money(tx.amount))
                        TechnicalValue("Proveedor", tx.supplier)
                        TechnicalValue("Número de factura", tx.invoiceNumber)
                        TechnicalValue("Fecha", tx.purchaseDate)
                        TechnicalValue("Responsable", tx.accountUsername.ifBlank { tx.createdByName })
                        TechnicalValue("Estado", tx.recordStatus)
                        tx.extraFields.forEach { (key, value) ->
                            if (value.isNotBlank()) TechnicalValue(s.config.fields("invoice").firstOrNull { it.key == key }?.label ?: key, value)
                        }
                        if (tx.attachments.isNotEmpty()) TextButton(onClick = { evidenceDetail = tx.attachments }) { Text("Ver evidencia (${tx.attachments.size})") }
                    }
                    row.message?.let { m ->
                        TechnicalValue("Código", m.code)
                        TechnicalValue("Tipo", if (m.messageType == "TRANSFERENCIA") "Transferencia" else "Solicitud")
                        TechnicalValue("Monto", money(m.amount))
                        TechnicalValue("De", m.senderName)
                        TechnicalValue("Para", m.recipientName.ifBlank { m.recipientEmail })
                        TechnicalValue("Fecha", m.transferDate.ifBlank { TimeUtils.displayDateTime(m.createdAt) })
                        TechnicalValue("Motivo", m.reason)
                        TechnicalValue("Estado", m.status)
                        val module = if (m.messageType == "TRANSFERENCIA") "transfer" else "fundRequest"
                        m.extraFields.forEach { (key, value) ->
                            if (value.isNotBlank()) TechnicalValue(s.config.fields(module).firstOrNull { it.key == key }?.label ?: key, value)
                        }
                        if (m.attachments.isNotEmpty()) TextButton(onClick = { evidenceDetail = m.attachments }) { Text("Ver evidencia (${m.attachments.size})") }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.transaction?.takeIf { it.recordStatus == "ACTIVA" && vm.canEditInvoice(it.id) }?.let { tx ->
                        TextButton(onClick = { detail = null; vm.startInvoiceEdit(tx.id) }) { Text("Editar") }
                    }
                    Button(onClick = { detail = null }) { Text("Cerrar") }
                }
            }
        )
    }

    evidenceDetail?.let { items -> EvidenceDialog(vm, items) { evidenceDetail = null } }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminar movimientos") },
            text = { Text("${selected.size} seleccionados") },
            confirmButton = {
                Button(
                    onClick = {
                        val invoiceIds = selected.filter { it.startsWith("invoice:") }.map { it.substringAfter(':') }.toSet()
                        val messageIds = selected.filter { it.startsWith("message:") }.map { it.substringAfter(':') }.toSet()
                        vm.deleteHistorySelection(invoiceIds, messageIds)
                        selected.clear(); selectionMode = false; confirmDelete = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SkcPalette.Red)
                ) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun AdminSpaceSection(vm: AppViewModel) {
    val s = vm.snapshot
    val report = vm.adminSpaceReport
    var category by remember { mutableStateOf(CLEANUP_AUDIT) }
    var days by remember { mutableStateOf("90") }
    var confirmCleanup by remember { mutableStateOf(false) }
    var confirmOrphans by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }

    val selectedBackups = s.settings.infrastructure.backupProviders
    val remoteBackups = selectedBackups.filter { it in setOf(BACKUP_GOOGLE_DRIVE, BACKUP_ONEDRIVE, BACKUP_VPS) }
    val automaticSafetyBackup = remoteBackups.isNotEmpty()
    val backupConnected = remoteBackups.all { provider ->
        when (provider) {
            BACKUP_GOOGLE_DRIVE -> s.settings.externalBackup.googleDriveTreeUri.isNotBlank()
            BACKUP_ONEDRIVE -> s.settings.externalBackup.oneDriveTreeUri.isNotBlank()
            BACKUP_VPS -> s.settings.externalBackup.vpsBaseUrl.isNotBlank()
            else -> true
        }
    }
    val selectedBackupLabel = remoteBackups.joinToString(", ") { backupProviderLabel(it) }.ifBlank { "Sin respaldo" }

    val categoryLabel = when (category) {
        CLEANUP_TRANSACTIONS -> "Facturas antiguas"
        CLEANUP_MESSAGES -> "Transferencias/solicitudes finalizadas"
        CLEANUP_REMINDERS -> "Recordatorios finalizados"
        else -> "Auditoría antigua"
    }

    MobileCard {
        Text("Uso real de espacio", style = MaterialTheme.typography.titleLarge)
        val local = vm.storageUsage()
        SettingLine("Evidencias conocidas", "${local.totalFiles} · ${humanBytes(local.totalBytes)}", true)
        if (report != null) {
            SettingLine("Supabase · tabla skc_events", "${report.databaseRows} registros · ${humanBytes(report.databaseTableBytes)}", true)
            SettingLine("Papelera técnica", "${report.tombstones} registro(s)", report.tombstones == 0)
            SettingLine("Archivos remotos", "${report.remoteFiles} · ${humanBytes(report.remoteBytes)}", true)
            SettingLine(
                "Archivos huérfanos",
                "${report.orphanFiles} · ${humanBytes(report.orphanBytes)}",
                report.orphanFiles == 0
            )
            report.warnings.forEach { Text(it, fontSize = 9.sp, color = SkcPalette.Orange) }
        }
        Button(onClick = vm::scanAdminSpace, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (report == null) "Analizar Supabase y Cloudflare" else "Actualizar análisis")
        }
        if (report != null && report.orphanFiles > 0) {
            OutlinedButton(onClick = { confirmOrphans = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Eliminar archivos huérfanos (${report.orphanFiles})")
            }
        }
    }

    MobileCard {
        Text("Limpiar registros antiguos", style = MaterialTheme.typography.titleLarge)
        SelectField(
            "Categoría",
            category,
            listOf(
                CLEANUP_TRANSACTIONS to "Facturas antiguas",
                CLEANUP_MESSAGES to "Transferencias/solicitudes finalizadas",
                CLEANUP_REMINDERS to "Recordatorios finalizados",
                CLEANUP_AUDIT to "Auditoría antigua"
            )
        ) { category = it }
        SelectField(
            "Más antiguos que",
            days,
            listOf("30" to "30 días", "90" to "90 días", "180" to "180 días", "365" to "1 año")
        ) { days = it }
        if (category == CLEANUP_TRANSACTIONS || category == CLEANUP_MESSAGES) {
            SettingLine(
                "Respaldo previo",
                if (automaticSafetyBackup) {
                    if (backupConnected) "ZIP completo en $selectedBackupLabel" else "$selectedBackupLabel sin conectar"
                } else {
                    "No requerido"
                },
                !automaticSafetyBackup || backupConnected
            )
        }
        Button(
            onClick = { confirmCleanup = true },
            enabled = !vm.busy && (!automaticSafetyBackup || backupConnected),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Limpiar $categoryLabel") }
    }

    MobileCard {
        Text("Papelera técnica", style = MaterialTheme.typography.titleLarge)
        OutlinedButton(onClick = { confirmTrash = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Purgar eliminados de hace más de 90 días")
        }
    }

    if (confirmOrphans) {
        AlertDialog(
            onDismissRequest = { confirmOrphans = false },
            title = { Text("Eliminar archivos huérfanos") },
            text = { Text("${report?.orphanFiles ?: 0} archivo(s)") },
            confirmButton = {
                Button(onClick = { confirmOrphans = false; vm.deleteOrphanedStorage() }, enabled = !vm.busy) {
                    Text("Eliminar huérfanos")
                }
            },
            dismissButton = { TextButton(onClick = { confirmOrphans = false }) { Text("Cancelar") } }
        )
    }

    if (confirmCleanup) {
        AlertDialog(
            onDismissRequest = { confirmCleanup = false },
            title = { Text("Confirmar limpieza") },
            text = { Text("$categoryLabel · más de $days días") },
            confirmButton = {
                Button(onClick = { confirmCleanup = false; vm.cleanupOldRecords(category, days.toInt()) }, enabled = !vm.busy) {
                    Text("Limpiar")
                }
            },
            dismissButton = { TextButton(onClick = { confirmCleanup = false }) { Text("Cancelar") } }
        )
    }

    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = { Text("Purgar papelera técnica") },
            text = { Text("Más de 90 días") },
            confirmButton = {
                Button(onClick = { confirmTrash = false; vm.purgeTombstones(90) }, enabled = !vm.busy) {
                    Text("Purgar")
                }
            },
            dismissButton = { TextButton(onClick = { confirmTrash = false }) { Text("Cancelar") } }
        )
    }
}

private fun backupProviderLabel(provider: String): String = when (provider) {
    BACKUP_NONE -> "Sin respaldo"
    BACKUP_GOOGLE_DRIVE -> "Google Drive"
    BACKUP_ONEDRIVE -> "OneDrive"
    BACKUP_VPS -> "VPS / API"
    BACKUP_LOCAL_ZIP -> "ZIP local"
    else -> provider.ifBlank { "Sin respaldo" }
}

private fun backendProviderLabel(provider: String): String = when (provider) {
    DATA_BACKEND_VPS_API -> "VPS / API propia"
    DATA_BACKEND_POSTGRES_API -> "PostgreSQL vía API"
    DATA_BACKEND_MYSQL_API -> "MySQL vía API"
    DATA_BACKEND_MARIADB_API -> "MariaDB vía API"
    DATA_BACKEND_SQLSERVER_API -> "Microsoft SQL Server vía API"
    DATA_BACKEND_GENERIC_REST -> "API REST genérica"
    DATA_BACKEND_SUPABASE -> "Supabase"
    else -> provider.ifBlank { "Sin configurar" }
}

private fun humanBytes(value: Long): String = when {
    value >= 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.2f GB", value / (1024.0 * 1024.0 * 1024.0))
    value >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.2f MB", value / (1024.0 * 1024.0))
    value >= 1024L -> String.format(java.util.Locale.US, "%.1f KB", value / 1024.0)
    else -> "$value B"
}

private fun databaseColumnWidth(fieldKey: String) = when (fieldKey) {
    "description", "descriptionBase", "project" -> 210.dp
    "observations" -> 190.dp
    "client", "supplier", "secondaryCost", "liquidationType" -> 170.dp
    "accountUsername", "createdByName", "evidenceStatus" -> 145.dp
    else -> 112.dp
}

@Composable
private fun DatabasePreviewRow(columns: List<ExcelColumnMapping>, tx: Transaction?, header: Boolean) {
    Row(Modifier.background(if (header) SkcPalette.Blue else Color.White)) {
        columns.forEach { mapping ->
            val width = databaseColumnWidth(mapping.fieldKey)
            val title = mapping.header.ifBlank { ExcelFieldCatalog.label(mapping.fieldKey).takeIf { mapping.fieldKey.isNotBlank() } ?: "Sin vincular" }
            Surface(
                modifier = Modifier.width(width).heightIn(min = if (header) 52.dp else 44.dp),
                color = if (header) SkcPalette.Blue else Color.Transparent,
                border = BorderStroke(0.5.dp, if (header) Color.White.copy(alpha = .3f) else SkcPalette.Line)
            ) {
                Column(Modifier.padding(horizontal = 7.dp, vertical = 5.dp), verticalArrangement = Arrangement.Center) {
                    if (header) {
                        Text(title, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(mapping.reference, color = Color.White.copy(alpha = .75f), fontSize = 7.5.sp)
                    } else if (tx != null) {
                        Text(ExcelFieldCatalog.display(tx, mapping.fieldKey), fontSize = 9.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadEvidenceButton(vm: AppViewModel, attachments: List<Attachment>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var payload by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val p = payload; if (uri != null && p != null) ctx.contentResolver.openOutputStream(uri)?.use { it.write(p.second) }
    }
    OutlinedButton(onClick = { scope.launch { payload = vm.evidenceDownload(attachments); launcher.launch(payload!!.first) } }) { Text("Descargar", fontSize = 9.sp) }
}

@Composable
private fun EvidenceDialog(vm: AppViewModel, attachments: List<Attachment>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var index by remember { mutableIntStateOf(0) }
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    val item = attachments[index]
    LaunchedEffect(index) { bytes = runCatching { vm.evidenceBytes(item) }.getOrNull() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Evidencia ${index + 1}/${attachments.size}") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.name, style = MaterialTheme.typography.bodySmall)
                val providerLabel = when (item.storageProvider) { STORAGE_R2 -> "R2"; STORAGE_SUPABASE -> "Supabase"; else -> "Local" }
                Text("$providerLabel · ${item.storageStatus}", style = MaterialTheme.typography.bodySmall, color = SkcPalette.Muted)
                if (item.storageError.isNotBlank()) Text(item.storageError, style = MaterialTheme.typography.bodySmall, color = SkcPalette.Red)
                if (item.mime.startsWith("image/")) {
                    bytes?.let { data -> BitmapFactory.decodeByteArray(data, 0, data.size)?.let { bmp -> Image(bmp.asImageBitmap(), null, modifier = Modifier.fillMaxWidth().heightIn(max = 390.dp)) } }
                } else {
                    Surface(color = SkcPalette.BlueSoft, shape = RoundedCornerShape(12.dp)) { Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("PDF", color = SkcPalette.Blue, fontWeight = FontWeight.Bold, fontSize = 24.sp); Text(item.name) } }
                    Button(onClick = {
                        val data = bytes ?: return@Button
                        val file = File(ctx.cacheDir, item.name.ifBlank { "evidencia.pdf" }); file.writeBytes(data)
                        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime.ifBlank { "application/pdf" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    }, enabled = bytes != null) { Text("Abrir PDF") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Cerrar") } },
        dismissButton = {
            Row {
                TextButton(onClick = { if (index > 0) index-- }, enabled = index > 0) { Text("Anterior") }
                TextButton(onClick = { if (index < attachments.lastIndex) index++ }, enabled = index < attachments.lastIndex) { Text("Siguiente") }
            }
        }
    )
}

@Composable
private fun MessagesScreen(vm: AppViewModel) {
    val s = vm.snapshot
    val current = s.currentUser() ?: return
    var selectedContactId by remember { mutableStateOf<String?>(null) }
    var composerMode by remember { mutableStateOf("") }
    val contacts = s.users.filter { it.active && it.id != current.id }
    val messages = vm.visibleMessages().filter { it.messageType in listOf("TRANSFERENCIA", "SOLICITUD_MONTO") }

    BackHandler {
        if (selectedContactId != null) selectedContactId = null else vm.navigate(Screen.HOME)
    }

    val selectedContact = contacts.firstOrNull { it.id == selectedContactId }
    if (selectedContact == null) {
        MobilePage("Mensajes") {
            if (contacts.isEmpty()) {
                Text("Sin contactos", color = SkcPalette.Muted)
            }
            contacts.sortedByDescending { contact ->
                messages.filter { m ->
                    (m.senderUserId == current.id && m.recipientUserId == contact.id) ||
                        (m.senderUserId == contact.id && m.recipientUserId == current.id)
                }.maxOfOrNull { it.updatedAt }.orEmpty()
            }.forEach { contact ->
                val conversation = messages.filter { m ->
                    (m.senderUserId == current.id && m.recipientUserId == contact.id) ||
                        (m.senderUserId == contact.id && m.recipientUserId == current.id)
                }.sortedBy { it.createdAt }
                val last = conversation.lastOrNull()
                MobileCard(modifier = Modifier.fillMaxWidth().clickable { selectedContactId = contact.id }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(24.dp), color = SkcPalette.BlueSoft, modifier = Modifier.size(44.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text(contact.name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = SkcPalette.Blue) }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(contact.name, fontWeight = FontWeight.Bold)
                            if (last != null) {
                                Text(
                                    when (last.messageType) {
                                        "TRANSFERENCIA" -> "Transferencia · ${money(last.amount)}"
                                        else -> "Solicitud · ${money(last.amount)}"
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 9.sp,
                                    color = SkcPalette.Muted
                                )
                            }
                        }
                        Text("›", fontSize = 22.sp, color = SkcPalette.Muted)
                    }
                }
            }
        }
        Surface(
            modifier = Modifier.padding(6.dp).size(34.dp).clickable { vm.navigate(Screen.HOME) },
            shape = RoundedCornerShape(10.dp), color = Color.White, border = BorderStroke(1.dp, SkcPalette.Line), shadowElevation = 2.dp
        ) { Box(contentAlignment = Alignment.Center) { Text("←", fontSize = 18.sp, color = SkcPalette.Blue) } }
    } else {
        val conversation = messages.filter { m ->
            (m.senderUserId == current.id && m.recipientUserId == selectedContact.id) ||
                (m.senderUserId == selectedContact.id && m.recipientUserId == current.id)
        }.sortedBy { it.createdAt }
        MobilePage(selectedContact.name) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { composerMode = "request" }, modifier = Modifier.weight(1f)) {
                    Text("Solicitar transferencia", fontSize = 9.sp)
                }
                Button(onClick = { composerMode = "transfer" }, modifier = Modifier.weight(1f)) {
                    Text("Enviar transferencia", fontSize = 9.sp)
                }
            }
            conversation.forEach { m -> MessageBubble(m, s, vm, m.senderUserId == current.id) }
            if (conversation.isEmpty()) Text("Sin movimientos con este contacto", color = SkcPalette.Muted)
        }
        Surface(
            modifier = Modifier.padding(6.dp).size(34.dp).clickable { selectedContactId = null },
            shape = RoundedCornerShape(10.dp), color = Color.White, border = BorderStroke(1.dp, SkcPalette.Line), shadowElevation = 2.dp
        ) { Box(contentAlignment = Alignment.Center) { Text("←", fontSize = 18.sp, color = SkcPalette.Blue) } }
        if (composerMode.isNotBlank()) {
            MessageComposer(vm, composerMode, selectedContact.id) { composerMode = "" }
        }
    }
}

@Composable
private fun MessageBubble(
    m: Message,
    s: com.skcingenieria.facturas.repository.AppSnapshot,
    vm: AppViewModel,
    mine: Boolean
) {
    var evidenceOpen by remember { mutableStateOf(false) }
    var rejectOpen by remember { mutableStateOf(false) }
    var responseOpen by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = RoundedCornerShape(14.dp),
            color = if (mine) Color(0xFFE7F6E9) else Color.White,
            border = BorderStroke(1.dp, SkcPalette.Line)
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val deleted = m.deletedAt.isNotBlank() || m.status == "ELIMINADO"
                val title = when {
                    m.messageType == "TRANSFERENCIA" && m.sourceRequestId.isNotBlank() && mine -> "Solicitud aprobada · enviada"
                    m.messageType == "TRANSFERENCIA" && m.sourceRequestId.isNotBlank() && !mine -> "Solicitud aprobada"
                    m.messageType == "TRANSFERENCIA" && mine -> "Transferencia enviada"
                    m.messageType == "TRANSFERENCIA" && !mine -> "Transferencia recibida"
                    m.messageType == "SOLICITUD_PROVEEDOR" -> "Nuevo proveedor"
                    m.messageType == "SOLICITUD_MONTO" && mine -> "Solicitud enviada"
                    else -> "Solicitud recibida"
                }
                val visibleStatus = when {
                    m.messageType == "TRANSFERENCIA" &&
                        m.sourceRequestId.isNotBlank() &&
                        m.status in listOf("ENVIADO", "RECIBIDO") && mine -> "PENDIENTE RECEPCIÓN"
                    m.messageType == "TRANSFERENCIA" &&
                        m.sourceRequestId.isNotBlank() &&
                        m.status in listOf("ENVIADO", "RECIBIDO") && !mine -> "PENDIENTE CONFIRMACIÓN"
                    else -> m.status
                }
                Text(title, fontWeight = FontWeight.Bold)
                if (deleted) {
                    Text("Este mensaje fue eliminado", fontStyle = FontStyle.Italic, color = SkcPalette.Muted)
                } else {
                    if (m.messageType != "SOLICITUD_PROVEEDOR") Text(money(m.amount), fontWeight = FontWeight.Bold, color = SkcPalette.Blue)
                    if (m.reason.isNotBlank()) Text(m.reason)
                    if (m.observations.isNotBlank()) Text(m.observations, fontSize = 9.sp, color = SkcPalette.Muted)
                    if (m.editedAt.isNotBlank()) Text("Editado", fontSize = 8.sp, color = SkcPalette.Muted)
                    if (m.attachments.isNotEmpty()) TextButton(onClick = { evidenceOpen = true }) { Text("Evidencia") }
                    StatusBadge(visibleStatus, when (visibleStatus) { "CONFIRMADO", "RESPONDIDA", "COMPLETADA", "RECIBIDO" -> "green"; "RECHAZADO" -> "red"; else -> "orange" })
                }
                if (!deleted && !mine && m.messageType == "TRANSFERENCIA" && m.status !in listOf("CONFIRMADO", "RECHAZADO")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { vm.respondTransfer(m.id, true) }) { Text("Confirmar recepción", fontSize = 9.sp) }
                        OutlinedButton(onClick = { rejectOpen = true }) { Text("Rechazar", fontSize = 9.sp) }
                    }
                }
                if (!deleted && !mine && m.messageType == "SOLICITUD_MONTO" && m.status !in listOf("RESPONDIDA", "COMPLETADA") && m.responseTransferId.isBlank()) {
                    Button(onClick = { responseOpen = true }) { Text("Revisar y aprobar", fontSize = 9.sp) }
                }
                if (!deleted && s.isAdmin() && m.messageType == "SOLICITUD_PROVEEDOR" && m.status == "PENDIENTE") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { vm.approveSupplierRequest(m.id) }) { Text("Aprobar", fontSize = 9.sp) }
                        OutlinedButton(onClick = { vm.rejectSupplierRequest(m.id, "Rechazado por administrador") }) { Text("Rechazar", fontSize = 9.sp) }
                    }
                }
                if (!deleted && (mine || s.isAdmin()) && m.status !in listOf("CONFIRMADO", "COMPLETADA")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { editOpen = true }) { Text("Editar", fontSize = 9.sp) }
                        TextButton(onClick = { deleteOpen = true }) { Text("Eliminar", fontSize = 9.sp, color = SkcPalette.Red) }
                    }
                }
            }
        }
    }
    if (editOpen) {
        var editAmount by remember(m.id, m.updatedAt) { mutableStateOf(m.amount.toString()) }
        var editReason by remember(m.id, m.updatedAt) { mutableStateOf(m.reason) }
        var editObservations by remember(m.id, m.updatedAt) { mutableStateOf(m.observations) }
        AlertDialog(
            onDismissRequest = { editOpen = false },
            title = { Text("Editar mensaje") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (m.messageType != "SOLICITUD_PROVEEDOR") OutlinedTextField(editAmount, { editAmount = it }, label = { Text("Monto") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(editReason, { editReason = it }, label = { Text(if (m.messageType == "SOLICITUD_PROVEEDOR") "Proveedor" else "Motivo") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(editObservations, { editObservations = it }, label = { Text("Observaciones") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.editMessage(m.id, if (m.messageType == "SOLICITUD_PROVEEDOR") 1.0 else (editAmount.toDoubleOrNull() ?: 0.0), editReason, editObservations)
                    editOpen = false
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { editOpen = false }) { Text("Cancelar") } }
        )
    }
    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text("Eliminar mensaje") },
            text = { Text("Se eliminará para ambas partes y no conservará una copia vacía en la base de datos.") },
            confirmButton = { Button(onClick = { vm.deleteConversationMessage(m.id); deleteOpen = false }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("Cancelar") } }
        )
    }
    if (evidenceOpen) EvidenceDialog(vm, m.attachments) { evidenceOpen = false }
    if (rejectOpen) {
        var reason by remember(m.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rejectOpen = false },
            title = { Text("Rechazar transferencia") },
            text = { OutlinedTextField(reason, { reason = it }, label = { Text("Motivo") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(onClick = { vm.respondTransfer(m.id, false, reason); rejectOpen = false }, enabled = reason.trim().length >= 3) { Text("Rechazar") } },
            dismissButton = { TextButton(onClick = { rejectOpen = false }) { Text("Cancelar") } }
        )
    }
    if (responseOpen) {
        val ctx = LocalContext.current
        val uris = remember(m.id) { mutableStateListOf<Uri>() }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { list -> uris.clear(); uris.addAll(list) }
        var cameraUri by remember(m.id) { mutableStateOf<Uri?>(null) }
        val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let { uris.add(it) } }
        fun takePhoto() {
            val file = File(ctx.cacheDir, "camera/${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs(); createNewFile() }
            cameraUri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
            camera.launch(cameraUri!!)
        }
        var customAmount by remember(m.id) { mutableStateOf(m.amount.toString()) }
        var responseReason by remember(m.id) { mutableStateOf("") }
        val evidenceRequired = s.config.fields("transfer").any { it.key == "attachments" && it.visible && it.required }
        AlertDialog(
            onDismissRequest = { responseOpen = false },
            title = { Text("Aprobar solicitud") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Solicitado: ${money(m.amount)}", fontWeight = FontWeight.SemiBold)
                    if (m.reason.isNotBlank()) Text("Motivo de la solicitud: ${m.reason}", color = SkcPalette.Muted)
                    OutlinedTextField(customAmount, { customAmount = it }, label = { Text("Monto aprobado / enviado") }, prefix = { Text("$") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(responseReason, { responseReason = it }, label = { Text("Motivo / comentario (opcional)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    EvidencePicker(uris, { picker.launch(arrayOf("image/*", "application/pdf")) }, ::takePhoto)
                }
            },
            confirmButton = {
                Button(
                    onClick = { vm.respondAmountRequest(m.id, false, customAmount.toDoubleOrNull() ?: 0.0, uris.toList(), responseReason); responseOpen = false },
                    enabled = (customAmount.toDoubleOrNull() ?: 0.0) > 0 && (!evidenceRequired || uris.isNotEmpty())
                ) { Text("Aprobar y enviar") }
            },
            dismissButton = { TextButton(onClick = { responseOpen = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun MessageComposer(vm: AppViewModel, mode: String, contactId: String, onClose: () -> Unit) {
    val s = vm.snapshot
    val contact = s.users.firstOrNull { it.id == contactId && it.active } ?: return
    val formModule = if (mode == "transfer") "transfer" else "fundRequest"
    val fields = s.config.fields(formModule).filter { it.visible }.sortedBy { it.order }
    var values by remember(mode, contactId, s.config.formsUpdatedAt) {
        mutableStateOf(buildMap {
            put("recipientUserId", contact.id)
            put("recipientEmail", contact.email)
            put("amount", "")
            put("reason", "")
            put("project", "")
            put("balanceDeclared", (s.balances()[s.currentUser()?.id] ?: 0.0).toString())
            put("reference", "")
            put("transferDate", TimeUtils.todayIso())
            fields.forEach { field -> if (!containsKey(field.key)) put(field.key, configuredDefault(s, formModule, field.key)) }
        })
    }
    fun setValue(key: String, value: String) { values = values.toMutableMap().apply { put(key, value) } }
    fun applyOption(field: FormFieldConfig, option: FormOptionConfig) {
        val next = values.toMutableMap(); next[field.key] = option.value; option.linkedData.forEach { (k, v) -> next[k] = v }; values = next
    }

    val ctx = LocalContext.current
    val uris = remember(mode, contactId) { mutableStateListOf<Uri>() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { list -> uris.addAll(list) }
    var cameraUri by remember(mode, contactId) { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let { uris.add(it) } }
    fun takePhoto() {
        val file = File(ctx.cacheDir, "camera/${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs(); createNewFile() }
        cameraUri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        camera.launch(cameraUri!!)
    }
    fun optionsFor(field: FormFieldConfig): List<FormOptionConfig> = when {
        field.options.isNotEmpty() -> field.options
        field.key == "recipientUserId" -> listOf(FormOptionConfig(contact.id, contact.name))
        else -> emptyList()
    }

    val requiredOk = fields.filter { it.required }.all { field ->
        when {
            field.fieldType == FIELD_EVIDENCE || field.key == "attachments" -> uris.isNotEmpty()
            field.fieldType == FIELD_LINKED_SELECT && field.linkedTable.isUsable() -> linkedTableComplete(field, values)
            else -> values[field.key].orEmpty().isNotBlank()
        }
    }
    val resolvedValues = resolveLinkedTableValues(fields, values)
    val amount = resolvedValues["amount"].orEmpty().toDoubleOrNull() ?: 0.0
    val reason = resolvedValues["reason"].orEmpty()
    val known = setOf("recipientUserId", "recipientEmail", "amount", "reason", "project", "balanceDeclared", "reference", "transferDate", "attachments")
    val extras = resolvedValues.filterKeys { it !in known }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (mode == "transfer") "Enviar" else "Solicitar") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fields.forEach { field ->
                    if (field.fieldType == FIELD_LINKED_SELECT && field.linkedTable.isUsable()) {
                        LinkedTableFormField(field, values) { updated -> values = updated }
                    } else when (field.fieldType) {
                        FIELD_EVIDENCE -> EvidencePicker(uris, { picker.launch(arrayOf("image/*", "application/pdf")) }, ::takePhoto)
                        FIELD_SELECT, FIELD_LINKED_SELECT, FIELD_COMPOSITE_SELECT -> {
                            val options = optionsFor(field).filter { option ->
                                field.fieldType != FIELD_COMPOSITE_SELECT || field.parentKey.isBlank() || option.parentValue.isBlank() || option.parentValue == values[field.parentKey].orEmpty()
                            }
                            if (options.isEmpty()) {
                                OutlinedTextField(values[field.key].orEmpty(), { setValue(field.key, it) }, label = { Text(field.label) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            } else {
                                SelectField(field.label, values[field.key].orEmpty(), options.map { it.value to it.label }) { selected ->
                                    applyOption(field, options.firstOrNull { it.value == selected } ?: FormOptionConfig(selected))
                                }
                            }
                        }
                        else -> OutlinedTextField(
                            values[field.key].orEmpty(),
                            { setValue(field.key, it) },
                            label = { Text(field.label) },
                                    modifier = Modifier.fillMaxWidth(),
                            singleLine = field.key != "reason"
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (mode == "transfer") {
                        vm.transfer(contact.id, amount, resolvedValues["project"].orEmpty(), reason, resolvedValues["reference"].orEmpty(), resolvedValues["transferDate"].orEmpty(), uris.toList(), extras)
                    } else {
                        vm.requestAmount(contact.id, resolvedValues["recipientEmail"].orEmpty(), amount, resolvedValues["project"].orEmpty(), reason, resolvedValues["balanceDeclared"].orEmpty().toDoubleOrNull() ?: 0.0, uris.toList(), extras)
                    }
                    onClose()
                },
                enabled = amount > 0 && requiredOk
            ) { Text("Enviar") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } }
    )
}


@Composable
private fun RemindersScreen(vm: AppViewModel) {
    var create by remember { mutableStateOf(false) }
    var cancelTarget by remember { mutableStateOf<Reminder?>(null) }
    val list = vm.visibleReminders().filter { it.status == "PENDIENTE" }.sortedBy { it.nextNotifyAt }

    MobilePage("Recordatorios") {
        Button(onClick = { create = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Nuevo recordatorio") }
        list.forEach { r ->
            MobileCard {
                Text(r.reason, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    OutlinedButton(onClick = { vm.startReminderInvoice(r.id, r.reason) }) { Text("Completar", fontSize = 8.sp) }
                    OutlinedButton(onClick = { vm.postponeReminder(r.id) }) { Text("Posponer", fontSize = 8.sp) }
                    OutlinedButton(onClick = { cancelTarget = r }) { Text("Cancelar", fontSize = 8.sp) }
                }
            }
        }
        if (list.isEmpty()) Text("Sin recordatorios", color = SkcPalette.Muted)
    }
    if (create) ReminderCreateDialog(vm) { create = false }
    cancelTarget?.let { r -> CancelReminderDialog(r, { cancelTarget = null }, { reason -> vm.cancelReminder(r.id, reason); cancelTarget = null }) }
}

@Composable
private fun ReminderCreateDialog(vm: AppViewModel, onClose: () -> Unit) {
    var reason by remember { mutableStateOf(configuredDefault(vm.snapshot, "reminder", "reason")) }
    val field = vm.snapshot.config.field("reminder", "reason")
    AlertDialog(onDismissRequest = onClose, title = { Text("Nuevo recordatorio") }, text = { OutlinedTextField(reason, { reason = it }, label = { Text(field.label) }, modifier = Modifier.fillMaxWidth(), minLines = 2) }, confirmButton = { Button(onClick = { vm.createReminder(reason); onClose() }) { Text("Crear") } }, dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } })
}

@Composable
private fun CancelReminderDialog(r: Reminder, onClose: () -> Unit, onCancel: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, title = { Text("Cancelar recordatorio") }, text = { Column { Text(r.reason, style = MaterialTheme.typography.bodySmall); OutlinedTextField(reason, { reason = it }, label = { Text("Motivo de cancelación (opcional)") }, modifier = Modifier.fillMaxWidth()) } }, confirmButton = { Button(onClick = { onCancel(reason) }) { Text("Cancelar recordatorio") } }, dismissButton = { TextButton(onClick = onClose) { Text("Volver") } })
}

@Composable
private fun IdentityRequiredScreen(vm: AppViewModel) {
    MobilePage("Cuenta") {
        Button(onClick = { vm.navigate(Screen.SETTINGS) }, modifier = Modifier.fillMaxWidth()) {
            Text("Iniciar sesión")
        }
    }
}

@Composable
private fun SettingsScreen(vm: AppViewModel) {
    val s = vm.snapshot
    val admin = s.isAdmin()

    if (s.session == null) {
        MobilePage("Configuración") {
            ConfigurationJsonCard(vm, firstRun = true)
            InitialSupabaseSettings(vm, firstRun = s.settings.supabase.projectUrl.isBlank())
            AccountSettings(vm)
        }
        return
    }

    if (!admin) {
        MobilePage("Configuración") {
            AccountSettings(vm)
            ConfigurationJsonCard(vm, firstRun = false)
        }
        return
    }

    val tabs = listOf("Cuenta", "Usuarios", "Transferencias", "Formatos personalizados", "Nube y respaldo")
    var tab by remember { mutableStateOf("Cuenta") }

    MobilePage("Configuración") {
        MobileTabs(tabs, tab) { tab = it }
        when (tab) {
            "Cuenta" -> AccountSettings(vm)
            "Usuarios" -> UsersSettings(vm)
            "Transferencias" -> TransferSettings(vm)
            "Formatos personalizados" -> CustomFormatsSettings(vm)
            "Nube y respaldo" -> CloudAndBackupSettings(vm)
        }
    }
}


@Composable
private fun TransferSettings(vm: AppViewModel) {
    val required = vm.snapshot.config.transferRequiresConfirmation
    MobileCard {
        Text("Confirmación de transferencias", style = MaterialTheme.typography.titleLarge)
        SwitchLine("Requerir confirmación del receptor", required) { enabled ->
            vm.saveTransferConfirmationMode(enabled)
        }
        Text(
            if (required) "Emisor y receptor: el dinero se acredita cuando el receptor confirma. Puede rechazarlo."
            else "Solo emisor: al enviar, la transferencia se confirma y acredita automáticamente al receptor.",
            fontSize = 11.sp,
            color = SkcPalette.Muted
        )
        Text("El cambio aplica a las transferencias nuevas. Las pendientes mantienen su proceso original.",
            fontSize = 10.sp, color = SkcPalette.Muted)
    }
}

@Composable
private fun InitialSupabaseSettings(vm: AppViewModel, firstRun: Boolean) {
    val c = vm.snapshot.settings.supabase
    var url by remember(c.projectUrl) { mutableStateOf(c.projectUrl) }
    var key by remember(c.publishableKey) { mutableStateOf(c.publishableKey) }

    MobileCard {
        Text(if (firstRun) "Conexión inicial" else "Supabase", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            url,
            { url = it },
            label = { Text("Project URL") },
            placeholder = { Text("https://xxxxx.supabase.co") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            key,
            { key = it },
            label = { Text("Publishable key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Button(
            onClick = { vm.saveSupabase(url, key, c.pollSeconds, c.autoSync, c.allowSelfSignUp, c.lockUserToEmail) },
            enabled = url.trim().startsWith("https://") && key.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (c.projectUrl.isBlank()) "Guardar conexión" else "Actualizar conexión") }
    }
}

@Composable
private fun AccountSettings(vm: AppViewModel) {
    val s = vm.snapshot
    val c = s.settings.supabase
    var email by remember(s.session?.email) { mutableStateOf(s.session?.email.orEmpty()) }
    var password by remember(s.session?.email) { mutableStateOf("") }
    val connectionReady = c.projectUrl.isNotBlank() && c.publishableKey.isNotBlank()

    MobileCard {
        when {
            s.session == null -> {
                Text("Iniciar sesión", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(email, { email = it }, label = { Text("Correo") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text("Contraseña") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Button(
                    onClick = { vm.signIn(email.trim(), password) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = connectionReady && email.isNotBlank() && password.isNotBlank()
                ) { Text("Iniciar sesión") }
            }
            else -> {
                Text("Usted ha iniciado (${s.session.email}).", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = vm::signOut, modifier = Modifier.fillMaxWidth()) { Text("Cerrar sesión") }
            }
        }
    }
}

@Composable
private fun SettingLine(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(label); Row { Text(value, color = SkcPalette.Muted, fontSize = 10.sp); Spacer(Modifier.width(6.dp)); StatusBadge(if (ok) "OK" else "REVISAR", if (ok) "green" else "orange") } }
}

@Composable
private fun ConnectionSettings(vm: AppViewModel) {
    val s = vm.snapshot
    val c = s.settings.supabase
    val diagnostics = vm.databaseDiagnostics()
    var url by remember(c.projectUrl) { mutableStateOf(c.projectUrl) }
    var key by remember(c.publishableKey) { mutableStateOf(c.publishableKey) }
    var poll by remember(c.pollSeconds) { mutableStateOf(c.pollSeconds.toString()) }
    var auto by remember(c.autoSync) { mutableStateOf(c.autoSync) }
    var showTechnical by remember { mutableStateOf(false) }

    MobileCard {
        Text("Supabase", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(url, { url = it }, label = { Text("Project URL de Supabase") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            key, { key = it },
            label = { Text("Publishable key") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(poll, { poll = it.filter(Char::isDigit) }, label = { Text("Sincronizar cada (segundos)") }, modifier = Modifier.fillMaxWidth())
        SwitchLine("Sincronización automática", auto) { auto = it }
        Button(
            onClick = { vm.saveSupabase(url, key, poll.toIntOrNull() ?: 20, auto, c.allowSelfSignUp, c.lockUserToEmail) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Guardar conexión") }
        OutlinedButton(onClick = vm::testConnection, modifier = Modifier.fillMaxWidth(), enabled = c.projectUrl.isNotBlank() && s.session != null) {
            Text("Probar conexión")
        }
        TextButton(onClick = { showTechnical = !showTechnical }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showTechnical) "Ocultar parámetros reales" else "Ver parámetros reales")
        }
    }

    if (showTechnical) {
        MobileCard {
            Text("Parámetros reales en uso", style = MaterialTheme.typography.titleLarge)
            TechnicalValue("Proveedor de datos", diagnostics.provider)
            TechnicalValue("Base local", diagnostics.localDatabase)
            TechnicalValue("Tabla local", diagnostics.localEntityTable)
            TechnicalValue("Project URL", diagnostics.projectUrl.ifBlank { "Sin configurar" })
            TechnicalValue("REST endpoint", diagnostics.restEndpoint.ifBlank { "Sin configurar" })
            TechnicalValue("Auth endpoint", diagnostics.authEndpoint.ifBlank { "Sin configurar" })
            TechnicalValue("Tabla remota", diagnostics.eventsTable)
            TechnicalValue("Publishable key", diagnostics.publishableKey.ifBlank { "Sin configurar" })
            TechnicalValue("Usuario autenticado", diagnostics.authenticatedEmail.ifBlank { "Sin sesión" })
            TechnicalValue("Auth UID", diagnostics.authenticatedUserId.ifBlank { "Sin sesión" })
            TechnicalValue("Sincronización", if (diagnostics.autoSync) "Automática · ${diagnostics.pollSeconds}s" else "Manual")
            TechnicalValue("Última sincronización", diagnostics.lastSyncAt.ifBlank { "Aún no registrada" })
            TechnicalValue("Almacenamiento", "${diagnostics.storageProvider} · ${diagnostics.storageTarget}")
            TechnicalValue("Conector alternativo", if (s.settings.alternateBackend.apiBaseUrl.isBlank()) "Sin configurar" else backendProviderLabel(s.settings.alternateBackend.provider))
            TechnicalValue("API alternativa", s.settings.alternateBackend.apiBaseUrl.ifBlank { "Sin configurar" })
            TechnicalValue("Base/instancia alternativa", s.settings.alternateBackend.databaseName.ifBlank { "No indicada" })
            TechnicalValue("API de respaldo VPS", diagnostics.backupApiUrl.ifBlank { "Sin configurar" })
        }
    }
}

@Composable
private fun TechnicalValue(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 9.sp, color = SkcPalette.Muted, fontWeight = FontWeight.SemiBold)
        Text(value, fontSize = 10.sp)
    }
    HorizontalDivider(color = SkcPalette.Line)
}

@Composable
private fun UsersSettings(vm: AppViewModel) {
    val s = vm.snapshot
    if (!s.isAdmin() && s.anyAdmin()) { AccessDenied(); return }

    var adding by remember { mutableStateOf(false) }
    var expandedUserId by remember { mutableStateOf<String?>(null) }

    Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Agregar usuario") }

    s.users.forEach { user ->
        key(user.id) {
            UserEditor(
                user = user,
                vm = vm,
                expanded = expandedUserId == user.id,
                onToggle = {
                    expandedUserId = if (expandedUserId == user.id) null else user.id
                },
                onSaved = { expandedUserId = null }
            )
        }
    }

    if (adding) UserCreateDialog(vm) { adding = false }
}

@Composable
private fun UserEditor(
    user: UserProfile,
    vm: AppViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSaved: () -> Unit
) {
    var name by remember(user.id, user.name) { mutableStateOf(user.name) }
    var email by remember(user.id, user.email) { mutableStateOf(user.email) }
    var role by remember(user.id, user.role) { mutableStateOf(user.role) }
    var authUserId by remember(user.id, user.authUserId) { mutableStateOf(user.authUserId) }
    var balance by remember(user.id, user.initialBalance) { mutableStateOf(user.initialBalance.toString()) }
    var active by remember(user.id, user.active) { mutableStateOf(user.active) }
    var transfer by remember(user.id, user.transferEnabled) { mutableStateOf(user.transferEnabled) }

    MobileCard(modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().clickable { onToggle() }) {
            val availableWidth = maxWidth
            val actionWidth = 72.dp
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.width((availableWidth - actionWidth).coerceAtLeast(150.dp))) {
                    Text(
                        name.ifBlank { user.name },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${role.ifBlank { ROLE_USER }} · ${if (active) "Activo" else "Inactivo"} · ${money(balance.toDoubleOrNull() ?: user.initialBalance)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SkcPalette.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (email.isNotBlank()) {
                        Text(
                            if (authUserId.isNotBlank()) "Supabase Auth · UID vinculado" else "Supabase Auth · correo preparado",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (authUserId.isNotBlank()) SkcPalette.Green else SkcPalette.Blue,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                TextButton(onClick = onToggle, modifier = Modifier.width(actionWidth)) {
                    Text(if (expanded) "Cerrar" else "Editar", fontSize = 10.sp)
                }
            }
        }

        if (expanded) {
            HorizontalDivider(color = SkcPalette.Line)

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val editorWidth = maxWidth
                val wideEditor = editorWidth >= 560.dp
                val gap = 10.dp
                val fieldWidth = ((editorWidth - gap) / 2).coerceAtLeast(220.dp)

                if (wideEditor) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            OutlinedTextField(
                                name,
                                { name = it },
                                label = { Text("Nombre") },
                                modifier = Modifier.width(fieldWidth),
                                singleLine = true
                            )
                            OutlinedTextField(
                                email,
                                { email = it },
                                label = { Text("Correo") },
                                modifier = Modifier.width(fieldWidth),
                                singleLine = true
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            Box(Modifier.width(fieldWidth)) {
                                SelectField("Rol", role, listOf(ROLE_USER to "USUARIO", ROLE_ADMIN to "ADMIN")) { role = it }
                            }
                            OutlinedTextField(
                                balance,
                                { balance = it },
                                label = { Text("Saldo inicial") },
                                modifier = Modifier.width(fieldWidth),
                                singleLine = true
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(email, { email = it }, label = { Text("Correo") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        SelectField("Rol", role, listOf(ROLE_USER to "USUARIO", ROLE_ADMIN to "ADMIN")) { role = it }
                        OutlinedTextField(balance, { balance = it }, label = { Text("Saldo inicial") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                }
            }

            if (authUserId.isNotBlank()) {
                Surface(
                    color = SkcPalette.BlueSoft,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, SkcPalette.Blue.copy(alpha = .25f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.fillMaxWidth(0.62f)) {
                            Text("Supabase Auth vinculado", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            Text("UID: ${authUserId.take(8)}…", fontSize = 9.sp, color = SkcPalette.Muted)
                        }
                        TextButton(onClick = { authUserId = "" }) { Text("Desvincular", fontSize = 9.sp) }
                    }
                }
            }

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val switchGap = 8.dp
                if (maxWidth >= 320.dp) {
                    val switchWidth = (maxWidth - switchGap) / 2
                    Row(horizontalArrangement = Arrangement.spacedBy(switchGap)) {
                        CompactSwitchLine("Activo", active, Modifier.width(switchWidth)) { active = it }
                        CompactSwitchLine("Puede transferir", transfer, Modifier.width(switchWidth)) { transfer = it }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CompactSwitchLine("Activo", active, Modifier.fillMaxWidth()) { active = it }
                        CompactSwitchLine("Puede transferir", transfer, Modifier.fillMaxWidth()) { transfer = it }
                    }
                }
            }

            Button(
                onClick = {
                    vm.saveUser(
                        user.copy(
                            name = name,
                            email = email,
                            role = role,
                            authUserId = authUserId,
                            initialBalance = balance.toDoubleOrNull() ?: 0.0,
                            active = active,
                            transferEnabled = transfer
                        )
                    )
                    onSaved()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
            ) { Text("Guardar") }
        }
    }
}

@Composable
private fun CompactSwitchLine(
    label: String,
    value: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit
) {
    Surface(
        modifier = modifier,
        color = SkcPalette.Canvas,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, SkcPalette.Line)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 10.sp, maxLines = 2, modifier = Modifier.widthIn(min = 54.dp))
            Switch(checked = value, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun UserCreateDialog(vm: AppViewModel, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }; var email by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, title = { Text("Agregar usuario") }, text = { Column { OutlinedTextField(name, { name = it }, label = { Text("Nombre") }); OutlinedTextField(email, { email = it }, label = { Text("Correo") }) } }, confirmButton = { Button(onClick = { vm.saveUser(UserProfile("user-${CodeUtils.uuid()}", name, email)); onClose() }) { Text("Crear") } }, dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } })
}

private fun customFieldTypeLabel(type: String): String = when (type) {
    FIELD_EVIDENCE -> "Captura de evidencia"
    FIELD_SELECT -> "Celda desplegable simple"
    FIELD_LINKED_SELECT -> "Celda desplegable vinculada"
    FIELD_COMPOSITE_SELECT -> "Celda desplegable compuesta"
    else -> "Celda libre"
}

private fun formatDataFields(module: String): List<Pair<String, String>> = when (module) {
    "invoice" -> listOf(
        "amount" to "Monto", "supplier" to "Proveedor", "supplierRuc" to "RUC del proveedor", "supplierEmail" to "Correo del proveedor",
        "invoiceNumber" to "Número de documento", "attachments" to "Evidencia",
        "client" to "Cliente", "movementType" to "Tipo de movimiento", "accountUserId" to "Responsable", "purchaseDate" to "Fecha",
        "descriptionBase" to "Proyecto", "project" to "Proyecto 2", "description" to "Detalle", "area" to "Área",
        "liquidationType" to "Tipo de liquidación", "costCenter" to "Centro de costo", "secondaryCost" to "Costo secundario",
        "paymentMethod" to "Método de pago", "observations" to "Observaciones", "documentType" to "Tipo de documento"
    )
    "fundRequest" -> listOf(
        "recipientUserId" to "Contacto", "amount" to "Monto", "reason" to "Motivo", "attachments" to "Evidencia",
        "project" to "Proyecto", "balanceDeclared" to "Saldo actual", "recipientEmail" to "Correo externo"
    )
    "transfer" -> listOf(
        "recipientUserId" to "Contacto", "amount" to "Monto", "reason" to "Motivo", "attachments" to "Evidencia",
        "transferDate" to "Fecha", "reference" to "Referencia", "project" to "Proyecto"
    )
    else -> listOf("reason" to "Motivo")
}

@Composable
private fun LinkedDataEditor(
    option: FormOptionConfig,
    targets: List<Pair<String, String>>,
    onChange: (FormOptionConfig) -> Unit
) {
    option.linkedData.entries.toList().forEach { entry ->
        val choices = buildList {
            addAll(targets)
            if (entry.key !in targets.map { it.first }) add(entry.key to entry.key)
        }
        SelectField("Campo vinculado", entry.key, choices) { newKey ->
            val next = option.linkedData.toMutableMap()
            val value = next.remove(entry.key).orEmpty()
            next[newKey] = value
            onChange(option.copy(linkedData = next))
        }
        OutlinedTextField(
            entry.value,
            { value ->
                val next = option.linkedData.toMutableMap()
                next[entry.key] = value
                onChange(option.copy(linkedData = next))
            },
            label = { Text("Valor vinculado") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        TextButton(
            onClick = {
                val next = option.linkedData.toMutableMap()
                next.remove(entry.key)
                onChange(option.copy(linkedData = next))
            }
        ) { Text("Quitar", color = SkcPalette.Red) }
    }
    val available = targets.firstOrNull { it.first !in option.linkedData.keys }
    if (available != null) {
        OutlinedButton(
            onClick = { onChange(option.copy(linkedData = option.linkedData + (available.first to ""))) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("+ Dato vinculado") }
    }
}


@Composable
private fun SupplierLinkedCatalogEditor(
    catalogs: Catalogs,
    onCatalogsChange: (Catalogs) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var editId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var ruc by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }

    val visible = rememberSupplierResults(
        catalogs = catalogs,
        query = search,
        limit = 12,
        previewWhenBlank = true
    )

    fun startCreate() {
        editId = ""
        name = ""
        ruc = ""
        email = ""
    }

    fun startEdit(supplier: SupplierDefinition) {
        editId = supplier.id
        name = supplier.name
        ruc = supplier.ruc
        email = supplier.email
    }

    fun cancelEdit() {
        editId = null
        name = ""
        ruc = ""
        email = ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                search,
                { search = it },
                label = { Text("Buscar proveedor") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedButton(onClick = ::startCreate) { Text("+ Proveedor") }
        }

        editId?.let {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, SkcPalette.Line),
                color = SkcPalette.Canvas
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Proveedor") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(ruc, { ruc = it }, label = { Text("RUC / C.I.") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(email, { email = it }, label = { Text("Correo") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = ::cancelEdit, modifier = Modifier.weight(1f)) { Text("Cancelar") }
                        Button(
                            onClick = {
                                val cleanName = name.trim()
                                val cleanRuc = ruc.trim()
                                val cleanEmail = email.trim()
                                if (cleanName.isBlank()) return@Button
                                val currentId = editId.orEmpty()
                                val existing = catalogs.suppliers.firstOrNull { supplier ->
                                    supplier.id == currentId ||
                                        (currentId.isBlank() && cleanRuc.isNotBlank() && supplier.ruc.equals(cleanRuc, true)) ||
                                        (currentId.isBlank() && supplier.name.equals(cleanName, true))
                                }
                                val saved = SupplierDefinition(
                                    id = existing?.id ?: "supplier-${CodeUtils.uuid()}",
                                    name = cleanName,
                                    ruc = cleanRuc,
                                    email = cleanEmail
                                )
                                val next = catalogs.suppliers
                                    .filterNot { it.id == saved.id }
                                    .plus(saved)
                                    .sortedBy { it.name.lowercase() }
                                onCatalogsChange(catalogs.copy(suppliers = next))
                                search = cleanName
                                cancelEdit()
                            },
                            enabled = name.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) { Text("Guardar") }
                    }
                }
            }
        }

        visible.forEach { supplier ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, SkcPalette.Line),
                color = Color.White
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(supplier.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val linked = listOfNotNull(
                            supplier.ruc.takeIf { it.isNotBlank() }?.let { "RUC $it" },
                            supplier.email.takeIf { it.isNotBlank() }
                        ).joinToString(" · ")
                        if (linked.isNotBlank()) Text(linked, fontSize = 9.sp, color = SkcPalette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { startEdit(supplier) }) { Text("Editar") }
                    TextButton(
                        onClick = {
                            onCatalogsChange(catalogs.copy(suppliers = catalogs.suppliers.filterNot { it.id == supplier.id }))
                            if (editId == supplier.id) cancelEdit()
                        }
                    ) { Text("Eliminar", color = SkcPalette.Red) }
                }
            }
        }
    }
}

@Composable
private fun CustomFormatsSettings(vm: AppViewModel) {
    if (!vm.snapshot.isAdmin()) { AccessDenied(); return }
    val modules = linkedMapOf("invoice" to "Factura", "fundRequest" to "Solicitar", "transfer" to "Enviar")
    var module by remember { mutableStateOf("invoice") }
    var draftForms by remember(vm.formsSaveRevision) {
        mutableStateOf(modules.keys.associateWith { key -> vm.snapshot.config.fields(key) })
    }
    var editing by remember { mutableStateOf<Pair<String, FormFieldConfig>?>(null) }
    val tablePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::analyzeFormDataFile)
    }

    fun updateFields(next: List<FormFieldConfig>) {
        draftForms = draftForms.toMutableMap().apply { put(module, next.mapIndexed { index, field -> field.copy(order = index) }) }
    }

    MobileTabs(modules.values.toList(), modules[module] ?: "Factura") { label -> module = modules.entries.first { it.value == label }.key }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { updateFields(emptyList()) }, modifier = Modifier.weight(1f)) { Text("Desde cero") }
        OutlinedButton(onClick = { updateFields(defaultFormLayouts()[module].orEmpty()) }, modifier = Modifier.weight(1f)) { Text("Restaurar") }
    }

    if (module == "invoice") {
        MobileCard {
            Text("Proveedores", fontWeight = FontWeight.Bold)
            Text("Busca, crea, edita o elimina proveedores desde aquí. Solo ADMIN puede modificar el catálogo.", fontSize = 9.sp, color = SkcPalette.Muted)
            SupplierLinkedCatalogEditor(vm.snapshot.catalogs) { next -> vm.updateCatalogs(next) }
        }
    }

    val fields = draftForms[module].orEmpty().sortedBy { it.order }
    fields.forEach { field ->
        MobileCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(field.label.ifBlank { field.key }, fontWeight = FontWeight.SemiBold)
                    Text(
                        customFieldTypeLabel(field.fieldType) + " · " + if (field.required) "Obligatorio" else "Opcional",
                        fontSize = 9.sp,
                        color = SkcPalette.Muted
                    )
                    if (field.linkedTable.isUsable()) {
                        Text(
                            "${field.linkedTable.sourceName} · ${field.linkedTable.rows.size} filas" +
                                field.linkedTable.sheetName.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                            fontSize = 9.sp,
                            color = SkcPalette.Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                TextButton(onClick = { vm.clearFormDataWorkbook(); editing = field.key to field }) { Text("Editar") }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(if (field.required) "Campo obligatorio" else "Campo opcional", fontSize = 9.sp, color = SkcPalette.Muted)
                Switch(
                    checked = field.required,
                    onCheckedChange = { required ->
                        updateFields(fields.map { current -> if (current.key == field.key) current.copy(required = required) else current })
                    }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { updateFields(moveField(fields, field.key, -1)) }, modifier = Modifier.weight(1f)) { Text("↑") }
                OutlinedButton(onClick = { updateFields(moveField(fields, field.key, 1)) }, modifier = Modifier.weight(1f)) { Text("↓") }
                OutlinedButton(
                    onClick = { updateFields(fields.filterNot { it.key == field.key }) },
                    modifier = Modifier.weight(1f)
                ) { Text("Eliminar", color = SkcPalette.Red) }
            }
        }
    }

    OutlinedButton(
        onClick = {
            vm.clearFormDataWorkbook()
            val key = "custom_${System.currentTimeMillis()}"
            editing = "" to FormFieldConfig(key = key, label = "Nuevo campo", visible = true, order = fields.size, fieldType = FIELD_TEXT)
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("+ Campo") }

    Button(onClick = { vm.saveFormSettings(draftForms, "") }, modifier = Modifier.fillMaxWidth()) { Text("Guardar formatos") }

    editing?.let { (originalKey, initial) ->
        var draft by remember(originalKey, initial) { mutableStateOf(initial) }
        val importedWorkbook = vm.formDataWorkbook
        val targets = (formatDataFields(module) + fields.map { it.key to it.label })
            .distinctBy { it.first }
            .filter { it.first != draft.key }

        fun configFromSheet(sheetName: String): LinkedTableConfig {
            val workbook = importedWorkbook ?: return draft.linkedTable
            val sheet = workbook.sheets.firstOrNull { it.name == sheetName } ?: workbook.sheets.first()
            val previous = draft.linkedTable.columns.associateBy { normalizeLookup(it.sourceColumn) }
            var nextOrder = (previous.values.maxOfOrNull { it.order } ?: -1) + 1
            var columns = sheet.headers.map { header ->
                previous[normalizeLookup(header)]?.copy(sourceColumn = header)
                    ?: LinkedTableColumnConfig(
                        sourceColumn = header,
                        role = LINKED_COLUMN_IGNORE,
                        label = header,
                        order = nextOrder++
                    )
            }
            if (columns.none { it.role == LINKED_COLUMN_PRIMARY } && columns.isNotEmpty()) {
                columns = columns.toMutableList().also { it[0] = it[0].copy(role = LINKED_COLUMN_PRIMARY) }
            }
            return LinkedTableConfig(
                enabled = true,
                sourceName = workbook.fileName,
                sheetName = sheet.name,
                headers = sheet.headers,
                rows = sheet.rows,
                columns = columns
            )
        }

        LaunchedEffect(importedWorkbook, originalKey) {
            if (importedWorkbook != null && importedWorkbook.sheets.isNotEmpty()) {
                draft = draft.copy(
                    fieldType = FIELD_LINKED_SELECT,
                    options = emptyList(),
                    linkedTable = configFromSheet(importedWorkbook.sheets.first().name)
                )
            }
        }

        val ambiguityCount = if (draft.linkedTable.enabled) linkedTableAmbiguityCount(draft.linkedTable) else 0
        val targetConflicts = if (draft.linkedTable.enabled) linkedTableTargetConflicts(draft.linkedTable) else emptyList()
        val tableValid = !draft.linkedTable.enabled || (
            draft.linkedTable.rows.isNotEmpty() &&
                draft.linkedTable.columns.count { it.role == LINKED_COLUMN_PRIMARY } == 1 &&
                ambiguityCount == 0 &&
                targetConflicts.isEmpty()
            )

        AlertDialog(
            onDismissRequest = { vm.clearFormDataWorkbook(); editing = null },
            title = { Text("Campo") },
            text = {
                Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(draft.label, { draft = draft.copy(label = it) }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(
                        draft.key,
                        { value -> draft = draft.copy(key = value.trim().replace(Regex("[^A-Za-z0-9_-]"), "_")) },
                        label = { Text("Identificador del dato") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        readOnly = draft.core
                    )
                    SelectField(
                        "Tipo",
                        draft.fieldType,
                        listOf(
                            FIELD_EVIDENCE to "Captura de evidencia",
                            FIELD_TEXT to "Celda libre",
                            FIELD_SELECT to "Celda desplegable simple",
                            FIELD_LINKED_SELECT to "Celda desplegable vinculada",
                            FIELD_COMPOSITE_SELECT to "Celda desplegable compuesta"
                        )
                    ) { selectedType ->
                        draft = draft.copy(
                            fieldType = selectedType,
                            linkedTable = if (selectedType == FIELD_LINKED_SELECT) draft.linkedTable else draft.linkedTable.copy(enabled = false)
                        )
                    }
                    SwitchLine("Obligatorio", draft.required) { draft = draft.copy(required = it) }
                    OutlinedTextField(draft.defaultValue, { draft = draft.copy(defaultValue = it) }, label = { Text("Valor inicial") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

                    if (draft.fieldType == FIELD_LINKED_SELECT) {
                        MobileCard {
                            Text("Datos desde CSV / Excel", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Usa una columna principal, subcategorías dependientes y columnas vinculadas. Las columnas ocultas siguen guardando datos. El archivo se importa a la configuración; si cambia, usa Reemplazar archivo.",
                                fontSize = 10.sp,
                                color = SkcPalette.Muted
                            )
                            OutlinedButton(
                                onClick = {
                                    tablePicker.launch(
                                        arrayOf(
                                            "text/csv",
                                            "text/comma-separated-values",
                                            "text/tab-separated-values",
                                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                            "application/vnd.ms-excel",
                                            "text/plain"
                                        )
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (draft.linkedTable.sourceName.isBlank()) "Vincular CSV / Excel" else "Reemplazar archivo") }

                            if (draft.linkedTable.sourceName.isNotBlank()) {
                                Text(
                                    "${draft.linkedTable.sourceName} · ${draft.linkedTable.rows.size} filas",
                                    fontSize = 10.sp,
                                    color = SkcPalette.Muted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (importedWorkbook != null && importedWorkbook.sheets.size > 1) {
                                    SelectField(
                                        "Hoja",
                                        draft.linkedTable.sheetName,
                                        importedWorkbook.sheets.map { it.name to it.name }
                                    ) { selected -> draft = draft.copy(linkedTable = configFromSheet(selected), options = emptyList()) }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Switch(
                                        checked = draft.linkedTable.enabled,
                                        onCheckedChange = { enabled -> draft = draft.copy(linkedTable = draft.linkedTable.copy(enabled = enabled)) }
                                    )
                                    Text("Usar archivo en este campo", modifier = Modifier.align(Alignment.CenterVertically), fontSize = 10.sp)
                                }
                                TextButton(
                                    onClick = { draft = draft.copy(linkedTable = LinkedTableConfig()) },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Desvincular archivo", color = SkcPalette.Red) }
                            }
                        }

                        if (draft.linkedTable.headers.isNotEmpty()) {
                            Text("Columnas", fontWeight = FontWeight.SemiBold)
                            draft.linkedTable.columns.sortedBy { it.order }.forEach { column ->
                                val actualIndex = draft.linkedTable.columns.indexOfFirst { it.sourceColumn == column.sourceColumn }
                                MobileCard {
                                    Text(column.sourceColumn, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    SelectField(
                                        "Uso de la columna",
                                        column.role,
                                        listOf(
                                            LINKED_COLUMN_IGNORE to "Ignorar",
                                            LINKED_COLUMN_PRIMARY to "Columna principal",
                                            LINKED_COLUMN_SUBCATEGORY to "Subcategoría",
                                            LINKED_COLUMN_LINKED to "Dato vinculado"
                                        )
                                    ) { role ->
                                        val nextColumns = draft.linkedTable.columns.map { current ->
                                            when {
                                                current.sourceColumn == column.sourceColumn -> current.copy(role = role)
                                                role == LINKED_COLUMN_PRIMARY && current.role == LINKED_COLUMN_PRIMARY -> current.copy(role = LINKED_COLUMN_IGNORE)
                                                else -> current
                                            }
                                        }
                                        draft = draft.copy(linkedTable = draft.linkedTable.copy(columns = nextColumns))
                                    }
                                    if (column.role == LINKED_COLUMN_PRIMARY) {
                                        Text(
                                            "La columna principal se muestra con el nombre de la celda: ${draft.label.ifBlank { draft.key }}",
                                            fontSize = 9.sp,
                                            color = SkcPalette.Muted
                                        )
                                    } else if (column.role != LINKED_COLUMN_IGNORE) {
                                        OutlinedTextField(
                                            column.label,
                                            { label ->
                                                val next = draft.linkedTable.columns.toMutableList()
                                                if (actualIndex >= 0) next[actualIndex] = column.copy(label = label)
                                                draft = draft.copy(linkedTable = draft.linkedTable.copy(columns = next))
                                            },
                                            label = { Text("Nombre en el formulario") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )
                                    }
                                    if (column.role == LINKED_COLUMN_LINKED) {
                                        val choices = listOf("" to "Guardar automáticamente como dato oculto") + targets
                                        SelectField("Guardar también en", column.targetKey, choices) { target ->
                                            val next = draft.linkedTable.columns.toMutableList()
                                            if (actualIndex >= 0) next[actualIndex] = column.copy(targetKey = target)
                                            draft = draft.copy(linkedTable = draft.linkedTable.copy(columns = next))
                                        }
                                        SwitchLine("Mostrar valor en el formulario", column.visible) { visible ->
                                            val next = draft.linkedTable.columns.toMutableList()
                                            if (actualIndex >= 0) next[actualIndex] = column.copy(visible = visible)
                                            draft = draft.copy(linkedTable = draft.linkedTable.copy(columns = next))
                                        }
                                    }
                                    if (column.role == LINKED_COLUMN_SUBCATEGORY) {
                                        val choices = listOf("" to "Guardar automáticamente como dato del formulario") + targets
                                        SelectField("Guardar selección en", column.targetKey, choices) { target ->
                                            val next = draft.linkedTable.columns.toMutableList()
                                            if (actualIndex >= 0) next[actualIndex] = column.copy(targetKey = target)
                                            draft = draft.copy(linkedTable = draft.linkedTable.copy(columns = next))
                                        }
                                        Text("Se mostrará como nivel dependiente después de la columna principal.", fontSize = 9.sp, color = SkcPalette.Muted)
                                    }
                                    if (column.role in setOf(LINKED_COLUMN_SUBCATEGORY, LINKED_COLUMN_LINKED)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            OutlinedButton(
                                                onClick = {
                                                    val ordered = draft.linkedTable.columns.sortedBy { it.order }.toMutableList()
                                                    val pos = ordered.indexOfFirst { it.sourceColumn == column.sourceColumn }
                                                    if (pos > 0) {
                                                        val item = ordered.removeAt(pos); ordered.add(pos - 1, item)
                                                        draft = draft.copy(linkedTable = draft.linkedTable.copy(columns = ordered.mapIndexed { i, c -> c.copy(order = i) }))
                                                    }
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) { Text("↑") }
                                            OutlinedButton(
                                                onClick = {
                                                    val ordered = draft.linkedTable.columns.sortedBy { it.order }.toMutableList()
                                                    val pos = ordered.indexOfFirst { it.sourceColumn == column.sourceColumn }
                                                    if (pos >= 0 && pos < ordered.lastIndex) {
                                                        val item = ordered.removeAt(pos); ordered.add(pos + 1, item)
                                                        draft = draft.copy(linkedTable = draft.linkedTable.copy(columns = ordered.mapIndexed { i, c -> c.copy(order = i) }))
                                                    }
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) { Text("↓") }
                                        }
                                    }
                                }
                            }
                            if (draft.linkedTable.columns.count { it.role == LINKED_COLUMN_PRIMARY } != 1) {
                                Text("Selecciona exactamente una columna principal.", color = SkcPalette.Red, fontSize = 10.sp)
                            }
                            if (ambiguityCount > 0) {
                                Text(
                                    "Hay $ambiguityCount combinación(es) repetida(s). Marca otra columna como subcategoría para distinguirlas.",
                                    color = SkcPalette.Red,
                                    fontSize = 10.sp
                                )
                            }
                            if (targetConflicts.isNotEmpty()) {
                                Text(
                                    "Un destino no puede usarse en dos columnas del mismo catálogo: ${targetConflicts.joinToString()}",
                                    color = SkcPalette.Red,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }

                    if (draft.fieldType == FIELD_COMPOSITE_SELECT) {
                        SelectField(
                            "Campo anterior",
                            draft.parentKey,
                            listOf("" to "Ninguno") + fields.filter { it.key != originalKey }.map { it.key to it.label }
                        ) { draft = draft.copy(parentKey = it) }
                    }

                    val useManualOptions = draft.fieldType in setOf(FIELD_SELECT, FIELD_LINKED_SELECT, FIELD_COMPOSITE_SELECT) && !draft.linkedTable.isUsable()
                    if (useManualOptions) {
                        draft.options.forEachIndexed { index, option ->
                            MobileCard {
                                OutlinedTextField(option.label, { value -> draft = draft.copy(options = draft.options.toMutableList().also { it[index] = option.copy(label = value, value = value) }) }, label = { Text("Opción") }, modifier = Modifier.fillMaxWidth())
                                if (draft.fieldType == FIELD_COMPOSITE_SELECT) {
                                    val parentOptions = fields.firstOrNull { it.key == draft.parentKey }?.options.orEmpty()
                                    if (parentOptions.isNotEmpty()) {
                                        SelectField(
                                            "Disponible cuando",
                                            option.parentValue,
                                            listOf("" to "Siempre") + parentOptions.map { it.value to it.label }
                                        ) { value -> draft = draft.copy(options = draft.options.toMutableList().also { it[index] = option.copy(parentValue = value) }) }
                                    } else {
                                        OutlinedTextField(
                                            option.parentValue,
                                            { value -> draft = draft.copy(options = draft.options.toMutableList().also { it[index] = option.copy(parentValue = value) }) },
                                            label = { Text("Disponible cuando") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )
                                    }
                                }
                                if (draft.fieldType == FIELD_LINKED_SELECT) {
                                    LinkedDataEditor(option, targets) { updated ->
                                        draft = draft.copy(options = draft.options.toMutableList().also { it[index] = updated })
                                    }
                                }
                                TextButton(onClick = { draft = draft.copy(options = draft.options.toMutableList().also { it.removeAt(index) }) }) { Text("Quitar", color = SkcPalette.Red) }
                            }
                        }
                        OutlinedButton(onClick = { draft = draft.copy(options = draft.options + FormOptionConfig("Opción ${draft.options.size + 1}")) }, modifier = Modifier.fillMaxWidth()) { Text("+ Opción manual") }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val normalized = draft.copy(label = draft.label.trim().ifBlank { draft.key }, visible = true)
                    val next = if (originalKey.isBlank()) fields + normalized else fields.map { if (it.key == originalKey) normalized else it }
                    updateFields(next)
                    vm.clearFormDataWorkbook()
                    editing = null
                }, enabled = draft.key.isNotBlank() && draft.label.isNotBlank() && tableValid) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { vm.clearFormDataWorkbook(); editing = null }) { Text("Cancelar") } }
        )
    }
}


private fun moveField(fields: List<FormFieldConfig>, key: String, delta: Int): List<FormFieldConfig> {
    val list = fields.sortedBy { it.order }.toMutableList(); val index = list.indexOfFirst { it.key == key }; val target = index + delta
    if (index >= 0 && target in list.indices) { val item = list.removeAt(index); list.add(target, item) }
    return list.mapIndexed { i, f -> f.copy(order = i) }
}

@Composable
private fun ExcelTemplateSettings(vm: AppViewModel) {
    val s = vm.snapshot
    if (!s.isAdmin()) { AccessDenied(); return }
    val current = s.config.excelTemplate
    val activeColumns = ExcelTemplateEngine.columns(current)
    val inspection = vm.excelInspection
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::analyzeExcelTemplate)
    }

    MobileCard {
        Text("Formato Excel", style = MaterialTheme.typography.titleLarge)
        SettingLine("Formato activo", if (current.enabled) current.fileName else "RE-S11.01 FLUJO FM 2026.xlsx", true)
        SettingLine("Hoja", if (current.enabled) current.sheetName else "FLUJO PROYECTO", true)
        SettingLine("Primera fila de datos", if (current.enabled) current.dataStartRow.toString() else "2", true)
        SettingLine("Columnas activas", activeColumns.size.toString(), activeColumns.isNotEmpty())
        SettingLine("Campos vinculados", activeColumns.count { it.fieldKey.isNotBlank() }.toString(), activeColumns.any { it.fieldKey.isNotBlank() })
        Text(
            activeColumns.joinToString(" · ") { it.header.ifBlank { it.reference } },
            style = MaterialTheme.typography.bodySmall,
            color = SkcPalette.Muted
        )
        SwitchLine("Incluir transferencias internas en INGRESOS", s.config.includeInternalTransfersInExcel) { enabled ->
            vm.saveExcelOptions(enabled)
        }
        Button(
            onClick = { picker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/zip", "application/octet-stream")) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Subir nuevo formato Excel") }
        if (current.enabled) {
            OutlinedButton(onClick = vm::restoreDefaultExcel, modifier = Modifier.fillMaxWidth()) {
                Text("Restaurar formato predeterminado")
            }
        }
    }

    if (inspection != null) {
        var selectedSheet by remember(inspection) { mutableStateOf(inspection.sheets.first().name) }
        val selected = inspection.sheets.firstOrNull { it.name == selectedSheet } ?: inspection.sheets.first()
        var dataStart by remember(inspection, selectedSheet) { mutableStateOf((selected.detectedHeaderRow + 1).toString()) }
        var mappings by remember(inspection, selectedSheet) { mutableStateOf(selected.suggestedMappings) }

        MobileCard {
            Text("Revisar nuevo formato", style = MaterialTheme.typography.titleLarge)
            Text(inspection.fileName, fontWeight = FontWeight.SemiBold)
            SelectField(
                "Hoja de trabajo",
                selectedSheet,
                inspection.sheets.map { it.name to it.name }
            ) { selectedSheet = it }
            OutlinedTextField(
                dataStart,
                { dataStart = it.filter(Char::isDigit) },
                label = { Text("Primera fila donde se escribirán datos") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        Text("Campos y columnas del formato", style = MaterialTheme.typography.titleMedium)

        mappings.forEachIndexed { index, mapping ->
            MobileCard {
                OutlinedTextField(
                    mapping.reference,
                    { value -> mappings = mappings.toMutableList().also { it[index] = mapping.copy(reference = value.uppercase()) } },
                    label = { Text("Referencia Excel") },
                    placeholder = { Text("A1 o A1:C1") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text(
                    "Detectado: ${mapping.header.ifBlank { "(encabezado vacío)" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SkcPalette.Muted
                )
                SelectField(
                    "Campo del formulario",
                    mapping.fieldKey,
                    buildList {
                        addAll(ExcelFieldCatalog.options.map { it.key to it.label })
                        val base = defaultFormLayouts()["invoice"].orEmpty().map { it.key }.toSet()
                        addAll(s.config.fields("invoice").filter { it.key !in base }.map { "custom:${it.key}" to "Personalizado · ${it.label}" })
                    }
                ) { field -> mappings = mappings.toMutableList().also { it[index] = mapping.copy(fieldKey = field) } }
                TextButton(
                    onClick = { mappings = mappings.toMutableList().also { it.removeAt(index) } },
                    modifier = Modifier.align(Alignment.End)
                ) { Text("Quitar campo del formato", color = SkcPalette.Red) }
            }
        }

        OutlinedButton(
            onClick = { mappings = mappings + ExcelColumnMapping("", "", "") },
            modifier = Modifier.fillMaxWidth()
        ) { Text("+ Agregar campo / columna") }

        Button(
            onClick = { vm.saveExcelTemplate(selectedSheet, dataStart.toIntOrNull() ?: 0, mappings) },
            modifier = Modifier.fillMaxWidth(),
            enabled = dataStart.toIntOrNull()?.let { it > 0 } == true && mappings.isNotEmpty()
        ) { Text("Usar este formato") }
        TextButton(onClick = vm::cancelExcelTemplateDraft, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
    }
}

@Composable
private fun ConfigurationJsonCard(vm: AppViewModel, firstRun: Boolean) {
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) vm.exportConfig(uri) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importConfig(uri) }
    MobileCard {
        Text("Importar / exportar .JSON", style = MaterialTheme.typography.titleLarge)
        Button(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }, modifier = Modifier.fillMaxWidth()) { Text("Importar") }
        OutlinedButton(onClick = { exportLauncher.launch("SKC_Facturas_config_${TimeUtils.todayIso()}.json") }, modifier = Modifier.fillMaxWidth()) { Text("Exportar") }
    }
}


@Composable
private fun BackendConnectorCard(vm: AppViewModel, provider: String, label: String) {
    val current = vm.snapshot.settings.backendConnections.firstOrNull { it.provider == provider }
        ?: vm.snapshot.settings.alternateBackend.takeIf { it.provider == provider }
        ?: AlternateBackendConfig(provider = provider)
    var url by remember(provider, current.apiBaseUrl) { mutableStateOf(current.apiBaseUrl) }
    var database by remember(provider, current.databaseName) { mutableStateOf(current.databaseName) }
    var health by remember(provider, current.healthPath) { mutableStateOf(current.healthPath) }
    var token by remember(provider) { mutableStateOf("") }
    MobileCard {
        Text(label, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(url, { url = it }, label = { Text("URL HTTPS") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(database, { database = it }, label = { Text("Base / instancia") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(health, { health = it }, label = { Text("Ruta de prueba") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(
            token,
            { token = it },
            label = { Text(if (vm.hasBackendApiToken(provider)) "Token/API key guardado" else "Token/API key") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    vm.saveBackendConnection(AlternateBackendConfig(provider, url, health, database), token.takeIf { it.isNotBlank() })
                    token = ""
                },
                modifier = Modifier.weight(1f)
            ) { Text("Guardar") }
            OutlinedButton(
                onClick = { vm.testBackendConnection(provider) },
                enabled = url.trim().startsWith("https://") && !vm.busy,
                modifier = Modifier.weight(1f)
            ) { Text("Probar") }
        }
    }
}

@Composable
private fun CloudAndBackupSettings(vm: AppViewModel) {
    val s = vm.snapshot
    if (!s.isAdmin()) { AccessDenied(); return }
    val storage = s.config.storage
    val infra = s.settings.infrastructure
    val backup = s.settings.externalBackup

    val primaryProviders = remember(infra.primaryDataProviders) { mutableStateListOf<String>().apply { addAll(infra.primaryDataProviders) } }
    val backupProviders = remember(infra.backupProviders) { mutableStateListOf<String>().apply { addAll(infra.backupProviders) } }
    var evidenceProvider by remember(storage.provider) { mutableStateOf(storage.provider) }
    var supabaseBucket by remember(storage.supabaseBucket) { mutableStateOf(storage.supabaseBucket) }
    var r2Function by remember(storage.r2FunctionName) { mutableStateOf(storage.r2FunctionName) }
    var includeFiles by remember(backup.includeFiles) { mutableStateOf(backup.includeFiles) }
    var dailyBackup by remember(infra.dailyDriveBackupEnabled) { mutableStateOf(infra.dailyDriveBackupEnabled) }
    var vpsUrl by remember(backup.vpsBaseUrl) { mutableStateOf(backup.vpsBaseUrl) }
    var vpsToken by remember { mutableStateOf("") }

    val drivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.connectExternalFolder(BACKUP_GOOGLE_DRIVE, uri) }
    val oneDrivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.connectExternalFolder(BACKUP_ONEDRIVE, uri) }
    val localZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.exportPortable(uri, includeFiles) }

    fun toggle(list: MutableList<String>, value: String, enabled: Boolean) {
        if (enabled && value !in list) list.add(value)
        if (!enabled) list.remove(value)
    }

    Text("Almacenamientos principales", style = MaterialTheme.typography.titleLarge)
    SwitchLine("Supabase", DATA_BACKEND_SUPABASE in primaryProviders) { toggle(primaryProviders, DATA_BACKEND_SUPABASE, it) }
    SwitchLine("VPS / API propia", DATA_BACKEND_VPS_API in primaryProviders) { toggle(primaryProviders, DATA_BACKEND_VPS_API, it) }
    SwitchLine("PostgreSQL", DATA_BACKEND_POSTGRES_API in primaryProviders) { toggle(primaryProviders, DATA_BACKEND_POSTGRES_API, it) }
    SwitchLine("MySQL", DATA_BACKEND_MYSQL_API in primaryProviders) { toggle(primaryProviders, DATA_BACKEND_MYSQL_API, it) }
    SwitchLine("MariaDB", DATA_BACKEND_MARIADB_API in primaryProviders) { toggle(primaryProviders, DATA_BACKEND_MARIADB_API, it) }
    SwitchLine("Microsoft SQL Server", DATA_BACKEND_SQLSERVER_API in primaryProviders) { toggle(primaryProviders, DATA_BACKEND_SQLSERVER_API, it) }
    SwitchLine("API REST", DATA_BACKEND_GENERIC_REST in primaryProviders) { toggle(primaryProviders, DATA_BACKEND_GENERIC_REST, it) }

    if (DATA_BACKEND_SUPABASE in primaryProviders) ConnectionSettings(vm)
    if (DATA_BACKEND_VPS_API in primaryProviders) BackendConnectorCard(vm, DATA_BACKEND_VPS_API, "VPS / API propia")
    if (DATA_BACKEND_POSTGRES_API in primaryProviders) BackendConnectorCard(vm, DATA_BACKEND_POSTGRES_API, "PostgreSQL")
    if (DATA_BACKEND_MYSQL_API in primaryProviders) BackendConnectorCard(vm, DATA_BACKEND_MYSQL_API, "MySQL")
    if (DATA_BACKEND_MARIADB_API in primaryProviders) BackendConnectorCard(vm, DATA_BACKEND_MARIADB_API, "MariaDB")
    if (DATA_BACKEND_SQLSERVER_API in primaryProviders) BackendConnectorCard(vm, DATA_BACKEND_SQLSERVER_API, "Microsoft SQL Server")
    if (DATA_BACKEND_GENERIC_REST in primaryProviders) BackendConnectorCard(vm, DATA_BACKEND_GENERIC_REST, "API REST")

    MobileCard {
        Text("Evidencias", style = MaterialTheme.typography.titleLarge)
        SelectField("Destino", evidenceProvider, listOf(STORAGE_R2 to "Cloudflare R2", STORAGE_SUPABASE to "Supabase Storage")) { evidenceProvider = it }
        if (evidenceProvider == STORAGE_R2) {
            OutlinedTextField(r2Function, { r2Function = it }, label = { Text("Edge Function") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        } else {
            OutlinedTextField(supabaseBucket, { supabaseBucket = it }, label = { Text("Bucket") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                vm.saveStorageConfig(storage.copy(provider = evidenceProvider, supabaseBucket = supabaseBucket.trim().ifBlank { "skc-evidence" }, r2FunctionName = r2Function.trim().ifBlank { "skc-storage-r2" }))
            }, modifier = Modifier.weight(1f)) { Text("Guardar") }
            OutlinedButton(onClick = vm::testStorageConnection, modifier = Modifier.weight(1f)) { Text("Probar") }
        }
    }

    MobileCard {
        Text("Almacenamiento", style = MaterialTheme.typography.titleLarge)
        val report = vm.adminSpaceReport
        if (report != null) {
            SettingLine("Supabase", "${report.databaseRows} registros", true)
            SettingLine("Archivos", "${report.remoteFiles} · ${humanBytes(report.remoteBytes)}", true)
            SettingLine("Huérfanos", "${report.orphanFiles} · ${humanBytes(report.orphanBytes)}", report.orphanFiles == 0)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::scanAdminSpace, modifier = Modifier.weight(1f)) { Text("Analizar") }
            Button(
                onClick = vm::deleteOrphanedStorage,
                enabled = report?.orphanFiles?.let { it > 0 } == true,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = SkcPalette.Red)
            ) { Text("Limpiar") }
        }
    }

    Text("Respaldos", style = MaterialTheme.typography.titleLarge)
    SwitchLine("Google Drive", BACKUP_GOOGLE_DRIVE in backupProviders) { toggle(backupProviders, BACKUP_GOOGLE_DRIVE, it) }
    if (BACKUP_GOOGLE_DRIVE in backupProviders) {
        Button(onClick = { drivePicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text(if (backup.googleDriveTreeUri.isBlank()) "Conectar Google Drive" else "Cambiar Google Drive") }
        if (backup.googleDriveTreeUri.isNotBlank()) OutlinedButton(onClick = { vm.createExternalBackup(BACKUP_GOOGLE_DRIVE) }, modifier = Modifier.fillMaxWidth()) { Text("Respaldar en Google Drive") }
    }
    SwitchLine("OneDrive", BACKUP_ONEDRIVE in backupProviders) { toggle(backupProviders, BACKUP_ONEDRIVE, it) }
    if (BACKUP_ONEDRIVE in backupProviders) {
        Button(onClick = { oneDrivePicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text(if (backup.oneDriveTreeUri.isBlank()) "Conectar OneDrive" else "Cambiar OneDrive") }
        if (backup.oneDriveTreeUri.isNotBlank()) OutlinedButton(onClick = { vm.createExternalBackup(BACKUP_ONEDRIVE) }, modifier = Modifier.fillMaxWidth()) { Text("Respaldar en OneDrive") }
    }
    SwitchLine("VPS / API", BACKUP_VPS in backupProviders) { toggle(backupProviders, BACKUP_VPS, it) }
    if (BACKUP_VPS in backupProviders) {
        OutlinedTextField(vpsUrl, { vpsUrl = it }, label = { Text("URL HTTPS") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(vpsToken, { vpsToken = it }, label = { Text(if (vm.hasVpsBackupToken()) "Token/API key guardado" else "Token/API key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveAndTestVpsBackup(includeFiles, vpsUrl, vpsToken.takeIf { it.isNotBlank() }); vpsToken = "" }, enabled = vpsUrl.startsWith("https://"), modifier = Modifier.weight(1f)) { Text("Guardar") }
            OutlinedButton(onClick = { vm.createExternalBackup(BACKUP_VPS) }, enabled = backup.vpsBaseUrl.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Respaldar") }
        }
    }
    SwitchLine("ZIP local", BACKUP_LOCAL_ZIP in backupProviders) { toggle(backupProviders, BACKUP_LOCAL_ZIP, it) }
    if (BACKUP_LOCAL_ZIP in backupProviders) {
        OutlinedButton(onClick = { localZip.launch("SKC_Facturas_${TimeUtils.todayIso()}.zip") }, modifier = Modifier.fillMaxWidth()) { Text("Crear ZIP") }
    }
    SwitchLine("Incluir fotos/PDF", includeFiles) { includeFiles = it }

    val remoteSelected = backupProviders.any { it in setOf(BACKUP_GOOGLE_DRIVE, BACKUP_ONEDRIVE, BACKUP_VPS) }
    val remoteReady = (BACKUP_GOOGLE_DRIVE !in backupProviders || backup.googleDriveTreeUri.isNotBlank()) &&
        (BACKUP_ONEDRIVE !in backupProviders || backup.oneDriveTreeUri.isNotBlank()) &&
        (BACKUP_VPS !in backupProviders || vpsUrl.isNotBlank())
    SwitchLine("Respaldo diario · ${infra.dailyBackupHour}:00", dailyBackup, enabled = remoteSelected && remoteReady) { dailyBackup = it }

    Button(
        onClick = {
            vm.saveBackupPreferences(
                infra.copy(
                    primaryDataProviders = primaryProviders.toList().ifEmpty { listOf(DATA_BACKEND_SUPABASE) },
                    backupProviders = backupProviders.toList(),
                    primaryDataProvider = primaryProviders.firstOrNull() ?: DATA_BACKEND_SUPABASE,
                    preferredBackupProvider = backupProviders.firstOrNull() ?: BACKUP_NONE,
                    dailyDriveBackupEnabled = dailyBackup && remoteSelected && remoteReady
                ),
                includeFiles,
                vpsUrl,
                vpsToken.takeIf { it.isNotBlank() }
            )
            vpsToken = ""
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Guardar configuración") }

    ConfigurationJsonCard(vm, false)
}


@Composable
private fun SwitchLine(label: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = if (enabled) SkcPalette.Ink else SkcPalette.Muted)
        Switch(checked = value, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun SegmentControl(items: List<String>, selected: String, onSelected: (String) -> Unit) {
    Surface(shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, SkcPalette.Blue.copy(alpha = .45f)), color = Color.White) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val w = maxWidth / items.size.coerceAtLeast(1)
            Row { items.forEach { item -> val active = selected.equals(item, true); Surface(modifier = Modifier.width(w).clickable { onSelected(item) }, color = if (active) SkcPalette.BlueSoft else Color.White) { Box(Modifier.heightIn(min = 38.dp), contentAlignment = Alignment.Center) { Text(item, color = if (active) SkcPalette.Blue else SkcPalette.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) } } } }
        }
    }
}

@Composable
fun SelectField(label: String, value: String, options: List<Pair<String, String>>, onValue: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val display = options.firstOrNull { it.first == value }?.second.orEmpty()
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(display, {}, readOnly = true, label = { Text(label) }, trailingIcon = { Text("⌄") }, modifier = Modifier.fillMaxWidth().clickable { open = true }, enabled = false, colors = OutlinedTextFieldDefaults.colors(disabledTextColor = SkcPalette.Ink, disabledBorderColor = SkcPalette.Line, disabledLabelColor = SkcPalette.Muted))
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { options.forEach { pair -> DropdownMenuItem(text = { Text(pair.second) }, onClick = { onValue(pair.first); open = false }) } }
    }
}

@Composable
private fun AccessDenied() { Box(Modifier.fillMaxSize().background(SkcPalette.Canvas), contentAlignment = Alignment.Center) { Text("Acceso reservado para ADMIN") } }
