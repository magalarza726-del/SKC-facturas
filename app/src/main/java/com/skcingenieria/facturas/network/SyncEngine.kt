package com.skcingenieria.facturas.network

import android.content.Context
import com.skcingenieria.facturas.data.*
import com.skcingenieria.facturas.util.FileUtils
import com.skcingenieria.facturas.util.TimeUtils
import com.skcingenieria.facturas.worker.MessageNotificationManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.OffsetDateTime

class SyncEngine(
    private val db: LocalDatabase,
    private val state: LocalStateStore,
    private val client: SupabaseRestClient = SupabaseRestClient(),
    context: Context? = null
) {
    private val messageNotifications = context?.applicationContext?.let { MessageNotificationManager(it, db, state) }
    data class Result(
        val received: Int,
        val sent: Int,
        val errors: Int,
        val evidenceWarnings: Int = 0,
        val message: String
    )
    private data class PreparedRemote(val payload: JSONObject, val storageError: String = "")
    private data class RemoteMerge(val payload: JSONObject, val keepPending: Boolean = false)

    private fun fingerprint(url: String): String = url.trim().trimEnd('/').removeSuffix("/rest/v1").lowercase()

    private fun instant(value: String): Instant = runCatching { OffsetDateTime.parse(value).toInstant() }
        .recoverCatching { Instant.parse(value) }.getOrDefault(Instant.EPOCH)

    fun ensureSession(): Pair<SupabaseConfig, AuthSession> = synchronized(SESSION_LOCK) {
        val settings = state.settings(); val config = settings.supabase
        require(config.projectUrl.isNotBlank() && config.publishableKey.isNotBlank()) { "Supabase no está configurado" }
        var session = state.session() ?: error("Inicie sesión en Supabase")
        if (session.expiresAtEpochSeconds <= TimeUtils.nowEpochSeconds() + 20) {
            session = client.refresh(config, session)
            state.saveSession(session)
        }
        config to session
    }

    fun sync(): Result = synchronized(SYNC_LOCK) { syncLocked() }

    /**
     * A single process-wide synchronization prevents the UI poller, manual sync and
     * WorkManager from uploading/deleting the same local evidence concurrently.
     */
    private fun syncLocked(): Result {
        val (config, session) = ensureSession()
        // 3.12 can store/test several future primary connectors, but the production
        // shared-data adapter remains Supabase until those APIs implement RemoteDataService.
        // Always using Supabase here prevents selecting a future connector in Settings from
        // breaking the current deliverable.
        var dataService = RemoteDataServiceFactory.create(DATA_BACKEND_SUPABASE, client, config, session)
        val backendFp = fingerprint(config.projectUrl)
        var received = 0; var sent = 0; var errors = 0; var evidenceWarnings = 0

        runCatching { dataService.bindMyProfile() }

        val initialRemote = dataService.fetchEvents()
        received += mergeRemote(initialRemote)
        queueLegacyStorageRepairs()

        val pending = db.pending().sortedWith(compareBy<com.skcingenieria.facturas.data.EntityRow> { dependencyOrder(it.type) }.thenBy { it.updatedAt })
        for (row in pending) {
            try {
                if (!dependencyReady(row)) continue
                val prepared = prepareForRemote(row, config, session)
                dataService.upsertEvent(RemoteEvent("${row.type}:${row.id}", row.type, row.id, prepared.payload, row.updatedAt))
                // La sincronización de datos y la de evidencias son estados distintos.
                // Si Supabase aceptó el registro, el registro queda sincronizado aunque un
                // archivo adjunto no haya podido subirse. El adjunto conserva su propio
                // storageStatus/storageError y puede reintentarse desde Almacenamiento.
                db.markSync(row.type, row.id, SYNCED)
                sent++
                if (prepared.storageError.isNotBlank()) evidenceWarnings++
            } catch (e: Exception) {
                db.markSync(row.type, row.id, SYNC_ERROR, e.message ?: "Error de sincronización")
                errors++
            }
        }

        val finalSession = state.session() ?: session
        dataService = RemoteDataServiceFactory.create(DATA_BACKEND_SUPABASE, client, config, finalSession)
        runCatching { dataService.bindMyProfile() }

        val finalRemote = dataService.fetchEvents()
        received += mergeRemote(finalRemote)
        val remoteKeys = finalRemote.mapTo(mutableSetOf()) { "${it.entityType}:${it.entityId}" }
        db.deleteWhereSyncedAbsent(remoteKeys)

        val now = TimeUtils.nowIso()
        state.saveSettings(settingsWithSync(now, backendFp))
        return Result(
            received = received,
            sent = sent,
            errors = errors,
            evidenceWarnings = evidenceWarnings,
            message = "Recibidos: $received · Enviados: $sent" +
                (if (errors > 0) " · Errores: $errors" else "") +
                (if (evidenceWarnings > 0) " · Evidencias: $evidenceWarnings" else "")
        )
    }

    private fun settingsWithSync(now: String, fp: String): LocalSettings {
        val s = state.settings(); return s.copy(supabase = s.supabase.copy(lastSyncAt = now, lastBackendFingerprint = fp))
    }

    private fun dependencyOrder(type: String): Int = when(type) {
        TYPE_USERS, TYPE_CATALOGS, TYPE_APP_CONFIG -> 0
        TYPE_TRANSACTIONS, TYPE_MESSAGES, TYPE_REMINDERS -> 1
        TYPE_LEDGER -> 2
        TYPE_AUDIT -> 3
        else -> 4
    }

    private fun dependencyReady(row: EntityRow): Boolean {
        if (row.type != TYPE_LEDGER) return true
        val p = row.payload
        return when (p.optString("sourceType")) {
            "FORMULARIO" -> db.get(TYPE_TRANSACTIONS, p.optString("sourceId"))?.syncStatus == SYNCED
            "TRANSFERENCIA" -> db.get(TYPE_MESSAGES, p.optString("sourceId"))?.syncStatus == SYNCED
            else -> true
        }
    }

    private fun appStorageConfig(): StorageConfig =
        db.get(TYPE_APP_CONFIG, "global")?.payload?.let(AppConfig::fromJson)?.storage ?: StorageConfig()

    private fun service(provider: String, config: SupabaseConfig, session: AuthSession, storage: StorageConfig = appStorageConfig()): StorageService =
        StorageServiceFactory.create(provider.ifBlank { storage.provider }, client, config, session, storage)


    private fun hasLegacyEvidenceSyncError(type: String, payload: JSONObject): Boolean {
        if (type != TYPE_TRANSACTIONS && type != TYPE_MESSAGES) return false
        val topLevelError = payload.optString("syncStatus") == SYNC_ERROR || payload.optString("syncError").isNotBlank()
        if (!topLevelError) return false
        val attachments = payload.optJSONArray("attachments") ?: return false
        for (i in 0 until attachments.length()) {
            if (attachments.optJSONObject(i)?.optString("storageStatus") == STORAGE_FAILED) return true
        }
        return false
    }

    private fun normalizeLegacyEvidencePayload(payload: JSONObject): JSONObject {
        val normalized = JSONObject(payload.toString())
        val attachments = normalized.optJSONArray("attachments") ?: JSONArray()
        for (i in 0 until attachments.length()) {
            val item = attachments.optJSONObject(i) ?: continue
            if (item.optString("storageStatus") != STORAGE_FAILED) continue
            val localPath = item.optString("localPath")
            val localExists = localPath.isNotBlank() && File(localPath).exists()
            item.put("storageStatus", if (localExists) STORAGE_PENDING else STORAGE_UNAVAILABLE)
            item.put("storageError", "")
        }
        normalized.put("attachments", attachments)
        normalized.put("syncStatus", SYNC_PENDING)
        normalized.put("syncError", "")
        return normalized
    }

    private fun queueLegacyStorageRepairs() {
        listOf(TYPE_TRANSACTIONS, TYPE_MESSAGES).forEach { type ->
            db.all(type).forEach { row ->
                val payload = JSONObject(row.payload.toString())
                val attachments = payload.optJSONArray("attachments") ?: JSONArray()
                var attachmentChanged = false

                for (i in 0 until attachments.length()) {
                    val item = attachments.optJSONObject(i) ?: continue
                    if (item.optString("storageStatus") != STORAGE_FAILED) continue

                    val localPath = item.optString("localPath")
                    val localExists = localPath.isNotBlank() && File(localPath).exists()
                    val previousError = item.optString("storageError")
                    val signatureFailure = previousError.contains("SignatureDoesNotMatch", ignoreCase = true) ||
                        previousError.contains("Storage HTTP 403", ignoreCase = true)

                    when {
                        localExists && (row.syncStatus == SYNC_ERROR || signatureFailure) -> {
                            // Errores históricos de firma R2 se reparan automáticamente en 3.15.2.
                            // uploadAttachment intentará R2 corregido y, si la función antigua sigue
                            // desplegada, hará fallback seguro a Supabase Storage.
                            item.put("storageStatus", STORAGE_PENDING)
                            item.put("storageError", "")
                            attachmentChanged = true
                        }
                        !localExists -> {
                            // No quedan bytes locales para reintentar; la evidencia no bloquea el dato.
                            item.put("storageStatus", STORAGE_UNAVAILABLE)
                            item.put("storageError", "")
                            attachmentChanged = true
                        }
                    }
                }

                if (attachmentChanged) {
                    payload.put("attachments", attachments)
                    payload.put("syncError", "")
                    db.put(type, row.id, payload, SYNC_PENDING, row.updatedAt)
                }
            }
        }
    }

    private fun prepareForRemote(row: EntityRow, config: SupabaseConfig, session: AuthSession): PreparedRemote {
        var payload = JSONObject(row.payload.toString())
        val storageConfig = appStorageConfig()
        require(session.authUserId.isNotBlank()) { "La sesión de Supabase no contiene un UID válido para almacenar archivos" }
        var storageWarning = ""

        fun addStorageWarning(message: String) {
            storageWarning = if (storageWarning.isBlank()) message else "$storageWarning · $message"
        }

        fun uploadAttachment(
            a: Attachment,
            index: Int,
            kind: String,
            date: String,
            code: String,
            allowUpload: Boolean = true
        ): Attachment {
            val existingKey = a.storageKey.ifBlank { a.remotePath }
            if (existingKey.isNotBlank() && a.storageStatus == STORAGE_AVAILABLE) {
                a.localPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf(File::exists)?.delete()
                return a.copy(
                    localPath = "",
                    remotePath = existingKey,
                    storageKey = existingKey,
                    storageProvider = a.storageProvider.ifBlank { STORAGE_SUPABASE },
                    storageError = ""
                )
            }

            // Una factura revocada no necesita volver a cargar su evidencia para poder
            // sincronizar la revocación. Se conserva la referencia remota si existía.
            if (!allowUpload) {
                return a.copy(
                    remotePath = existingKey,
                    storageKey = existingKey,
                    storageStatus = if (a.storageStatus == STORAGE_AVAILABLE) STORAGE_AVAILABLE else STORAGE_UNAVAILABLE,
                    storageError = ""
                )
            }

            val file = a.localPath.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::exists)
            if (file == null) {
                val message = "Evidencia local no disponible: ${a.name}"
                addStorageWarning(message)
                return a.copy(
                    localPath = "",
                    remotePath = existingKey,
                    storageKey = existingKey,
                    storageStatus = STORAGE_UNAVAILABLE,
                    storageError = ""
                )
            }

            val provider = a.storageProvider.ifBlank { storageConfig.provider }
            val key = existingKey.ifBlank {
                FileUtils.evidenceStorageKey(storageConfig.basePrefix, session.authUserId, kind, date, code, index, a)
            }
            val actualSize = file.length()
            val actualChecksum = FileUtils.sha256(file)
            val prepared = a.copy(
                size = actualSize,
                sha256 = actualChecksum,
                storageProvider = provider,
                storageKey = key,
                remotePath = key,
                storageStatus = STORAGE_UPLOADING,
                storageError = ""
            )
            return runCatching {
                fun uploadWith(selectedProvider: String): StorageObjectMetadata =
                    service(selectedProvider, config, session, storageConfig).upload(file, key, a.mime, actualChecksum)

                val remote = try {
                    uploadWith(provider)
                } catch (primaryError: Exception) {
                    val detail = primaryError.message.orEmpty()
                    val r2SignatureFailure = provider == STORAGE_R2 &&
                        (detail.contains("SignatureDoesNotMatch", ignoreCase = true) || detail.contains("Storage HTTP 403", ignoreCase = true))
                    if (!r2SignatureFailure) throw primaryError

                    // 3.15.2 safety net: una función R2 antigua puede seguir firmando
                    // metadatos incompatibles y devolver 403. Nunca perdemos una foto por eso:
                    // conservamos el mismo storage key y usamos Supabase Storage como respaldo.
                    uploadWith(STORAGE_SUPABASE)
                }
                require(remote.sizeBytes == actualSize) { "El proveedor no confirmó el tamaño del archivo enviado" }
                if (remote.checksum.isNotBlank()) {
                    require(remote.checksum.equals(actualChecksum, true)) { "El proveedor no confirmó la integridad del archivo enviado" }
                }
                file.delete()
                prepared.copy(
                    size = remote.sizeBytes,
                    sha256 = remote.checksum.ifBlank { actualChecksum },
                    storageProvider = remote.provider,
                    storageObjectId = remote.providerObjectId,
                    localPath = "",
                    storageStatus = STORAGE_AVAILABLE,
                    storageError = ""
                )
            }.getOrElse { e ->
                val message = e.message ?: "Error al subir ${a.name}"
                addStorageWarning(message)
                prepared.copy(storageStatus = STORAGE_FAILED, storageError = message)
            }
        }

        if (row.type == TYPE_TRANSACTIONS) {
            val tx = Transaction.fromJson(payload)
            val allowEvidenceUpload = tx.recordStatus != "REVOCADA"
            val uploaded = tx.attachments.mapIndexed { index, a ->
                uploadAttachment(a, index, "facturas", tx.purchaseDate, tx.code, allowEvidenceUpload)
            }
            val localPayload = tx.copy(
                attachments = uploaded,
                syncStatus = row.syncStatus,
                syncError = ""
            ).toJson()
            db.put(TYPE_TRANSACTIONS, tx.id, localPayload, row.syncStatus, row.updatedAt)
            payload = tx.copy(
                attachments = uploaded.map { it.copy(localPath = "") },
                syncStatus = SYNCED,
                syncError = ""
            ).toJson()
        } else if (row.type == TYPE_MESSAGES) {
            val msg = Message.fromJson(payload)
            val date = msg.createdAt.take(10).ifBlank { TimeUtils.todayIso() }
            val uploaded = msg.attachments.mapIndexed { index, a ->
                uploadAttachment(a, index, "transferencias", date, msg.code)
            }
            val localPayload = msg.copy(attachments = uploaded, syncStatus = row.syncStatus, syncError = "").toJson()
            db.put(TYPE_MESSAGES, msg.id, localPayload, row.syncStatus, row.updatedAt)
            payload = msg.copy(
                attachments = uploaded.map { it.copy(localPath = "") },
                syncStatus = SYNCED,
                syncError = ""
            ).toJson()
        }

        // syncStatus/syncError describen únicamente el estado del registro en Supabase.
        // Los errores de evidencia viven dentro de cada Attachment.
        payload.put("syncStatus", SYNCED)
        payload.put("syncError", "")
        return PreparedRemote(payload, storageWarning)
    }

    private fun currentProfileId(): String {
        val settings = state.settings()
        if (settings.profileUserId.isNotBlank()) return settings.profileUserId
        val session = state.session() ?: return ""
        val uid = session.authUserId.trim()
        val email = session.email.trim()
        return db.all(TYPE_USERS)
            .map { UserProfile.fromJson(it.payload) }
            .firstOrNull { user ->
                (uid.isNotBlank() && user.authUserId == uid) ||
                    (email.isNotBlank() && user.email.equals(email, ignoreCase = true))
            }?.id.orEmpty()
    }

    private fun mergeRemote(events: List<RemoteEvent>): Int {
        var changed = 0
        val currentUserId = currentProfileId()
        events.forEach { remote ->
            if (remote.entityType !in SYNC_ENTITY_TYPES) return@forEach

            // A tiny remote tombstone prevents an old/offline device from resurrecting
            // an invoice that an administrator already deleted.
            if (remote.payload.optBoolean("_deleted", false)) {
                val local = db.get(remote.entityType, remote.entityId)
                if (local != null) {
                    if (remote.entityType == TYPE_TRANSACTIONS || remote.entityType == TYPE_MESSAGES) {
                        val attachments = local.payload.optJSONArray("attachments") ?: JSONArray()
                        for (i in 0 until attachments.length()) {
                            attachments.optJSONObject(i)?.optString("localPath")
                                ?.takeIf { it.isNotBlank() }
                                ?.let(::File)
                                ?.let { file ->
                                    runCatching { file.delete() }
                                    runCatching { file.parentFile?.takeIf { dir -> dir.isDirectory && dir.list().isNullOrEmpty() }?.delete() }
                                }
                        }
                    }
                    db.delete(remote.entityType, remote.entityId)
                    changed++
                }
                return@forEach
            }

            val local = db.get(remote.entityType, remote.entityId)
            val previousMessage = if (remote.entityType == TYPE_MESSAGES) {
                local?.payload?.let { runCatching { Message.fromJson(it) }.getOrNull() }
            } else null
            var remoteApplied = false
            val legacyEvidenceError = hasLegacyEvidenceSyncError(remote.entityType, remote.payload)
            val remotePayload = JSONObject(remote.payload.toString()).apply {
                put("syncStatus", SYNCED)
                put("syncError", "")
            }
            if (local == null) {
                if (legacyEvidenceError) {
                    db.put(
                        remote.entityType,
                        remote.entityId,
                        normalizeLegacyEvidencePayload(remotePayload),
                        SYNC_PENDING,
                        remote.updatedAt
                    )
                } else {
                    db.put(remote.entityType, remote.entityId, remotePayload, SYNCED, remote.updatedAt)
                }
                remoteApplied = true
                changed++
            } else {
                val localTime = instant(local.updatedAt); val remoteTime = instant(remote.updatedAt)
                val seededLocal = local.payload.optBoolean("seeded", false)

                // Versiones anteriores guardaban fallos de R2/Storage como ERROR del registro.
                // Si el registro local ya está confirmado, lo reencolamos una sola vez para
                // limpiar ese estado remoto y dejar el fallo únicamente en la evidencia.
                if (legacyEvidenceError && local.syncStatus == SYNCED && !seededLocal) {
                    val base = if (localTime > remoteTime) {
                        JSONObject(local.payload.toString())
                    } else {
                        preserveLocalPaths(remote.entityType, local.payload, remotePayload)
                    }
                    val normalized = normalizeLegacyEvidencePayload(base)
                    val mergedAt = if (localTime > remoteTime) local.updatedAt else remote.updatedAt
                    db.put(remote.entityType, remote.entityId, normalized, SYNC_PENDING, mergedAt)
                    changed++
                    return@forEach
                }
                // A local PENDIENTE/ERROR row contains changes that have not been confirmed by
                // the backend yet. Never erase those edits during the pull phase; they must get
                // their chance to be pushed first. This prevents saved configuration from
                // snapping back to the previous remote version.
                val hasUnsyncedLocalChanges = local.syncStatus != SYNCED && !seededLocal
                if (!hasUnsyncedLocalChanges && (seededLocal || remoteTime > localTime)) {
                    val pathsMerged = preserveLocalPaths(remote.entityType, local.payload, remotePayload)
                    val sectionMerge = mergeNewerLocalFormSection(remote.entityType, local.payload, pathsMerged)
                    val status = if (sectionMerge.keepPending) SYNC_PENDING else SYNCED
                    val mergedAt = if (sectionMerge.keepPending) {
                        val now = TimeUtils.nowIso()
                        if (instant(now) > remoteTime) now else remote.updatedAt
                    } else remote.updatedAt
                    val payload = if (sectionMerge.keepPending) JSONObject(sectionMerge.payload.toString()).apply {
                        put("updatedAt", mergedAt)
                        put("syncStatus", SYNC_PENDING)
                        put("syncError", "")
                    } else sectionMerge.payload
                    db.put(remote.entityType, remote.entityId, payload, status, mergedAt)
                    remoteApplied = true
                    changed++
                }
                // Equal/older remote timestamps keep the local copy.
            }

            if (remote.entityType == TYPE_MESSAGES) {
                if (remoteApplied) {
                    db.get(TYPE_MESSAGES, remote.entityId)?.payload
                        ?.let { runCatching { Message.fromJson(it) }.getOrNull() }
                        ?.let { incoming -> messageNotifications?.onRemoteMessage(previousMessage, incoming, currentUserId) }
                }
            }
        }
        return changed
    }

    /**
     * App configuration is one remote row, but Forms are edited independently from Excel,
     * catalogs and storage. A newer unrelated remote setting must not erase a form revision that
     * was saved later on this device. formsUpdatedAt gives that section its own conflict clock.
     * If local Forms win we keep the merged row pending so the preserved form is pushed back.
     */
    private fun mergeNewerLocalFormSection(type: String, localPayload: JSONObject, remotePayload: JSONObject): RemoteMerge {
        if (type != TYPE_APP_CONFIG) return RemoteMerge(remotePayload)
        val local = runCatching { AppConfig.fromJson(localPayload) }.getOrNull() ?: return RemoteMerge(remotePayload)
        val remote = runCatching { AppConfig.fromJson(remotePayload) }.getOrNull() ?: return RemoteMerge(remotePayload)
        if (local.formsUpdatedAt.isBlank()) return RemoteMerge(remotePayload)
        val localFormsTime = instant(local.formsUpdatedAt)
        val remoteFormsTime = if (remote.formsUpdatedAt.isBlank()) Instant.EPOCH else instant(remote.formsUpdatedAt)
        if (remote.formsUpdatedAt.isNotBlank() && remoteFormsTime >= localFormsTime) return RemoteMerge(remotePayload)

        val merged = remote.copy(
            forms = local.forms,
            revocationKey = local.revocationKey,
            formsUpdatedAt = local.formsUpdatedAt,
            formsSchemaVersion = maxOf(remote.formsSchemaVersion, local.formsSchemaVersion, FORM_SCHEMA_VERSION),
            schemaVersion = maxOf(remote.schemaVersion, local.schemaVersion, DATA_SCHEMA_VERSION),
            seeded = false
        ).toJson()
        return RemoteMerge(merged, keepPending = true)
    }

    private fun preserveLocalPaths(type: String, local: JSONObject, remote: JSONObject): JSONObject {
        if (type != TYPE_TRANSACTIONS && type != TYPE_MESSAGES) return remote
        val localArray = local.optJSONArray("attachments") ?: return remote
        val localPaths = mutableMapOf<String,String>()
        for (i in 0 until localArray.length()) localArray.optJSONObject(i)?.let { localPaths[it.optString("id")] = it.optString("localPath") }
        val remoteArray = remote.optJSONArray("attachments") ?: JSONArray()
        for (i in 0 until remoteArray.length()) remoteArray.optJSONObject(i)?.let { a ->
            val path = localPaths[a.optString("id")].orEmpty(); if(path.isNotBlank()) a.put("localPath", path)
        }
        remote.put("attachments", remoteArray); return remote
    }

    fun downloadEvidenceTo(attachment: Attachment, output: OutputStream) {
        attachment.localPath.takeIf { it.isNotBlank() }?.let { p ->
            File(p).takeIf(File::exists)?.let { file -> file.inputStream().use { it.copyTo(output, 64 * 1024) }; return }
        }
        val key = attachment.storageKey.ifBlank { attachment.remotePath }
        require(key.isNotBlank()) { "La evidencia todavía no está sincronizada" }
        val (config, session) = ensureSession()
        val storage = appStorageConfig()
        val provider = attachment.storageProvider.ifBlank { STORAGE_SUPABASE }
        service(provider, config, session, storage).downloadTo(key, output)
    }

    fun downloadEvidence(attachment: Attachment): ByteArray = java.io.ByteArrayOutputStream().use { out ->
        downloadEvidenceTo(attachment, out)
        out.toByteArray()
    }

    fun uploadStorageFile(file: File, key: String, mime: String, checksum: String = "", provider: String = ""): StorageObjectMetadata {
        val (config, session) = ensureSession()
        val storage = appStorageConfig()
        return service(provider.ifBlank { storage.provider }, config, session, storage).upload(file, key, mime, checksum)
    }

    fun downloadStorage(key: String, provider: String): ByteArray {
        val (config, session) = ensureSession()
        return service(provider, config, session).download(key)
    }

    fun downloadLegacySupabaseObject(bucket: String, key: String): ByteArray {
        val (config, session) = ensureSession()
        val storage = appStorageConfig().copy(supabaseBucket = bucket)
        return service(STORAGE_SUPABASE, config, session, storage).download(key)
    }

    fun deleteStorage(key: String, provider: String) {
        val (config, session) = ensureSession()
        service(provider, config, session).delete(key)
    }

    fun testStorageConnection(provider: String = ""): StorageTestResult {
        val (config, session) = ensureSession()
        val storage = appStorageConfig()
        return service(provider.ifBlank { storage.provider }, config, session, storage).testConnection()
    }

    fun testConnection() { val (config,session)=ensureSession(); RemoteDataServiceFactory.create(DATA_BACKEND_SUPABASE, client, config, session).ping() }

    companion object {
        private val SYNC_LOCK = Any()
        private val SESSION_LOCK = Any()
    }
}
