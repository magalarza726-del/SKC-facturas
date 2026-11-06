package com.skcingenieria.facturas.network

import com.skcingenieria.facturas.data.AuthSession
import com.skcingenieria.facturas.data.RemoteEvent
import com.skcingenieria.facturas.data.SupabaseConfig
import com.skcingenieria.facturas.data.STORAGE_SUPABASE
import com.skcingenieria.facturas.util.TimeUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class SupabaseException(message: String) : Exception(message)

class SupabaseRestClient {
    private fun root(config: SupabaseConfig): String = config.projectUrl.trim().trimEnd('/').removeSuffix("/rest/v1")

    private fun connection(url: String, method: String, config: SupabaseConfig, accessToken: String? = null): HttpURLConnection {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 15_000; c.readTimeout = 30_000; c.doInput = true
        c.setRequestProperty("apikey", config.publishableKey.trim())
        c.setRequestProperty("Accept", "application/json")
        accessToken?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        return c
    }

    private fun read(c: HttpURLConnection): ByteArray {
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        if (c.responseCode !in 200..299) {
            val body = bytes.toString(Charsets.UTF_8).take(1000)
            throw SupabaseException("Supabase HTTP ${c.responseCode}: $body")
        }
        return bytes
    }

    fun signIn(config: SupabaseConfig, email: String, password: String): AuthSession {
        require(config.projectUrl.isNotBlank() && config.publishableKey.isNotBlank()) { "Configure Supabase primero" }
        val c = connection("${root(config)}/auth/v1/token?grant_type=password", "POST", config).apply {
            doOutput = true; setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write(JSONObject().put("email", email.trim()).put("password", password).toString().toByteArray()) }
        val j = JSONObject(read(c).toString(Charsets.UTF_8))
        val user = j.optJSONObject("user") ?: JSONObject()
        return AuthSession(
            accessToken = j.optString("access_token"), refreshToken = j.optString("refresh_token"),
            expiresAtEpochSeconds = TimeUtils.nowEpochSeconds() + j.optLong("expires_in", 3600) - 30,
            email = user.optString("email", email.trim()), authUserId = user.optString("id")
        )
    }

    fun signUp(config: SupabaseConfig, email: String, password: String): AuthSession? {
        val c = connection("${root(config)}/auth/v1/signup", "POST", config).apply { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        c.outputStream.use { it.write(JSONObject().put("email", email.trim()).put("password", password).toString().toByteArray()) }
        val j = JSONObject(read(c).toString(Charsets.UTF_8))
        val token = j.optString("access_token")
        if (token.isBlank()) return null
        val user = j.optJSONObject("user") ?: JSONObject()
        return AuthSession(token, j.optString("refresh_token"), TimeUtils.nowEpochSeconds()+j.optLong("expires_in",3600)-30, user.optString("email",email), user.optString("id"))
    }

    fun refresh(config: SupabaseConfig, session: AuthSession): AuthSession {
        val c = connection("${root(config)}/auth/v1/token?grant_type=refresh_token", "POST", config).apply { doOutput=true; setRequestProperty("Content-Type", "application/json") }
        c.outputStream.use { it.write(JSONObject().put("refresh_token", session.refreshToken).toString().toByteArray()) }
        val j = JSONObject(read(c).toString(Charsets.UTF_8)); val user = j.optJSONObject("user") ?: JSONObject()
        return AuthSession(j.optString("access_token"), j.optString("refresh_token",session.refreshToken), TimeUtils.nowEpochSeconds()+j.optLong("expires_in",3600)-30, user.optString("email",session.email), user.optString("id",session.authUserId))
    }

    fun bindMyProfile(config: SupabaseConfig, session: AuthSession): String? {
        val c = connection("${root(config)}/rest/v1/rpc/skc_bind_my_profile", "POST", config, session.accessToken).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write("{}".toByteArray()) }
        val body = read(c).toString(Charsets.UTF_8).trim()
        if (body.isBlank() || body == "null") return null
        return runCatching { org.json.JSONTokener(body).nextValue() as? String }.getOrNull()
    }

    fun fetchEvents(config: SupabaseConfig, session: AuthSession): List<RemoteEvent> {
        val result = mutableListOf<RemoteEvent>(); var offset = 0
        while (true) {
            val url = "${root(config)}/rest/v1/skc_events?select=id,entity_type,entity_id,payload,updated_at&order=updated_at.asc&limit=1000&offset=$offset"
            val c = connection(url, "GET", config, session.accessToken)
            val arr = JSONArray(read(c).toString(Charsets.UTF_8))
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { j ->
                result += RemoteEvent(j.optString("id"), j.optString("entity_type"), j.optString("entity_id"), j.optJSONObject("payload") ?: JSONObject(), j.optString("updated_at"))
            }
            if (arr.length() < 1000) break
            offset += arr.length()
        }
        return result
    }

    fun upsertEvent(config: SupabaseConfig, session: AuthSession, event: RemoteEvent) {
        val url = "${root(config)}/rest/v1/skc_events?on_conflict=id"
        val c = connection(url, "POST", config, session.accessToken).apply {
            doOutput=true; setRequestProperty("Content-Type", "application/json"); setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
        }
        val body = JSONObject().apply {
            put("id", event.id); put("entity_type",event.entityType); put("entity_id",event.entityId); put("payload",event.payload); put("updated_at",event.updatedAt)
        }
        c.outputStream.use { it.write(body.toString().toByteArray()) }; read(c)
    }

    fun canDeleteInvoice(config: SupabaseConfig, session: AuthSession): Boolean {
        val c = connection("${root(config)}/rest/v1/rpc/skc_can_delete_invoice", "POST", config, session.accessToken).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write("{}".toByteArray()) }
        val body = read(c).toString(Charsets.UTF_8).trim()
        if (body.isBlank() || body == "null") return false
        return runCatching {
            when (val value = org.json.JSONTokener(body).nextValue()) {
                is Boolean -> value
                else -> value.toString().toBooleanStrictOrNull() ?: false
            }
        }.getOrDefault(false)
    }

    /**
     * Deletes an invoice through a server-side RPC.
     * The database function verifies the authenticated user is an ADMIN,
     * so the Android app never needs a service-role key.
     */
    fun deleteInvoice(config: SupabaseConfig, session: AuthSession, transactionId: String): Boolean {
        require(transactionId.isNotBlank()) { "Factura inválida" }
        val c = connection("${root(config)}/rest/v1/rpc/skc_delete_invoice", "POST", config, session.accessToken).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use {
            it.write(JSONObject().put("p_transaction_id", transactionId).toString().toByteArray())
        }
        val body = read(c).toString(Charsets.UTF_8).trim()
        if (body.isBlank() || body == "null") return false
        return runCatching {
            when (val value = org.json.JSONTokener(body).nextValue()) {
                is Boolean -> value
                else -> value.toString().toBooleanStrictOrNull() ?: false
            }
        }.getOrDefault(false)
    }



    /**
     * Physically removes a conversation message. Authorization is verified by the
     * security-definer RPC: sender or ADMIN only. No tombstone is kept in skc_events.
     */
    fun deleteMessagePhysically(config: SupabaseConfig, session: AuthSession, messageId: String): Boolean {
        require(messageId.isNotBlank()) { "Mensaje inválido" }
        val c = connection("${root(config)}/rest/v1/rpc/skc_delete_message_physical", "POST", config, session.accessToken).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use {
            it.write(JSONObject().put("p_message_id", messageId).toString().toByteArray())
        }
        val body = read(c).toString(Charsets.UTF_8).trim()
        if (body.isBlank() || body == "null") return false
        return runCatching {
            when (val value = org.json.JSONTokener(body).nextValue()) {
                is Boolean -> value
                else -> value.toString().toBooleanStrictOrNull() ?: false
            }
        }.getOrDefault(false)
    }

    fun canPhysicalCleanup3152(config: SupabaseConfig, session: AuthSession): Boolean {
        val c = connection("${root(config)}/rest/v1/rpc/skc_can_physical_cleanup_3152", "POST", config, session.accessToken).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write("{}".toByteArray()) }
        val body = read(c).toString(Charsets.UTF_8).trim()
        if (body.isBlank() || body == "null") return false
        return runCatching {
            when (val value = org.json.JSONTokener(body).nextValue()) {
                is Boolean -> value
                else -> value.toString().toBooleanStrictOrNull() ?: false
            }
        }.getOrDefault(false)
    }

    fun canAdminCleanup(config: SupabaseConfig, session: AuthSession): Boolean {
        val c = connection("${root(config)}/rest/v1/rpc/skc_can_admin_cleanup", "POST", config, session.accessToken).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write("{}".toByteArray()) }
        val body = read(c).toString(Charsets.UTF_8).trim()
        if (body.isBlank() || body == "null") return false
        return runCatching {
            when (val value = org.json.JSONTokener(body).nextValue()) {
                is Boolean -> value
                else -> value.toString().toBooleanStrictOrNull() ?: false
            }
        }.getOrDefault(false)
    }

    fun adminDeleteEntity(config: SupabaseConfig, session: AuthSession, entityType: String, entityId: String): Boolean {
        require(entityType.isNotBlank() && entityId.isNotBlank()) { "Entidad inválida" }
        val c = connection("${root(config)}/rest/v1/rpc/skc_admin_delete_entity", "POST", config, session.accessToken).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        val body = JSONObject()
            .put("p_entity_type", entityType)
            .put("p_entity_id", entityId)
            .toString()
            .toByteArray()
        c.outputStream.use { it.write(body) }
        val response = read(c).toString(Charsets.UTF_8).trim()
        if (response.isBlank() || response == "null") return false
        return runCatching {
            when (val value = org.json.JSONTokener(response).nextValue()) {
                is Boolean -> value
                else -> value.toString().toBooleanStrictOrNull() ?: false
            }
        }.getOrDefault(false)
    }

    fun adminSpaceReport(config: SupabaseConfig, session: AuthSession): JSONObject {
        val c = connection("${root(config)}/rest/v1/rpc/skc_admin_space_report", "POST", config, session.accessToken).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write("{}".toByteArray()) }
        val body = read(c).toString(Charsets.UTF_8).trim()
        if (body.isBlank() || body == "null") return JSONObject()
        return when (val value = org.json.JSONTokener(body).nextValue()) {
            is JSONObject -> value
            is JSONArray -> value.optJSONObject(0) ?: JSONObject()
            else -> JSONObject()
        }
    }

    fun purgeAdminTombstones(config: SupabaseConfig, session: AuthSession, olderThanDays: Int): Int {
        require(olderThanDays >= 30) { "Por seguridad, las eliminaciones técnicas deben conservarse al menos 30 días" }
        val c = connection("${root(config)}/rest/v1/rpc/skc_admin_purge_tombstones", "POST", config, session.accessToken).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use {
            it.write(JSONObject().put("p_older_than_days", olderThanDays).toString().toByteArray())
        }
        val body = read(c).toString(Charsets.UTF_8).trim()
        if (body.isBlank() || body == "null") return 0
        return runCatching {
            when (val value = org.json.JSONTokener(body).nextValue()) {
                is Number -> value.toInt()
                else -> value.toString().toIntOrNull() ?: 0
            }
        }.getOrDefault(0)
    }

    fun listStorageObjects(
        config: SupabaseConfig,
        session: AuthSession,
        bucket: String,
        prefix: String = ""
    ): List<StorageObjectMetadata> {
        val result = mutableListOf<StorageObjectMetadata>()
        var offset = 0
        val pageSize = 1000
        while (true) {
            val encodedBucket = URLEncoder.encode(bucket, StandardCharsets.UTF_8.toString()).replace("+", "%20")
            val c = connection("${root(config)}/storage/v1/object/list/$encodedBucket", "POST", config, session.accessToken).apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val request = JSONObject()
                .put("limit", pageSize)
                .put("offset", offset)
                .put("prefix", prefix.trim('/'))
                .put("sortBy", JSONObject().put("column", "name").put("order", "asc"))
            c.outputStream.use { it.write(request.toString().toByteArray()) }
            val arr = JSONArray(read(c).toString(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val name = item.optString("name")
                if (name.isBlank() || item.optString("id").isBlank() && item.has("name") && !item.has("metadata")) continue
                val cleanPrefix = prefix.trim('/')
                val key = when {
                    cleanPrefix.isBlank() -> name
                    name == cleanPrefix || name.startsWith("$cleanPrefix/") -> name
                    else -> "$cleanPrefix/$name"
                }
                val metadata = item.optJSONObject("metadata") ?: JSONObject()
                result += StorageObjectMetadata(
                    provider = STORAGE_SUPABASE,
                    key = key,
                    sizeBytes = metadata.optLong("size", metadata.optLong("ContentLength", 0L)),
                    mimeType = metadata.optString("mimetype", metadata.optString("contentType")),
                    checksum = metadata.optString("sha256"),
                    providerObjectId = item.optString("id", key)
                )
            }
            if (arr.length() < pageSize) break
            offset += arr.length()
        }
        return result
    }

    private fun encodedPath(path: String): String = path.split('/').joinToString("/") {
        URLEncoder.encode(it, StandardCharsets.UTF_8.toString()).replace("+", "%20")
    }

    fun uploadFile(config: SupabaseConfig, session: AuthSession, bucket: String, path: String, file: File, mime: String) {
        val c = connection("${root(config)}/storage/v1/object/$bucket/${encodedPath(path)}", "POST", config, session.accessToken).apply {
            doOutput = true
            setRequestProperty("Content-Type", mime)
            setRequestProperty("x-upsert", "true")
            setFixedLengthStreamingMode(file.length())
        }
        FileInputStream(file).use { input -> c.outputStream.use { output -> input.copyTo(output, 64 * 1024) } }
        read(c)
    }

    fun deleteObject(config: SupabaseConfig, session: AuthSession, bucket: String, path: String) {
        val c = connection("${root(config)}/storage/v1/object/$bucket/${encodedPath(path)}", "DELETE", config, session.accessToken)
        if (c.responseCode == 404) {
            c.errorStream?.close()
            return
        }
        read(c)
    }

    fun invokeFunction(config: SupabaseConfig, session: AuthSession, functionName: String, body: JSONObject): JSONObject {
        val c = connection("${root(config)}/functions/v1/${functionName.trim()}", "POST", config, session.accessToken).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val text = read(c).toString(Charsets.UTF_8)
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    fun putSigned(url: String, file: File, mime: String, headers: Map<String, String> = emptyMap()) {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.requestMethod = "PUT"
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", mime)
        headers.forEach { (name, value) -> if (name.isNotBlank() && value.isNotBlank()) c.setRequestProperty(name, value) }
        c.setFixedLengthStreamingMode(file.length())
        FileInputStream(file).use { input -> c.outputStream.use { output -> input.copyTo(output, 64 * 1024) } }
        val bytes = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        if (c.responseCode !in 200..299) throw SupabaseException("Storage HTTP ${c.responseCode}: ${bytes.toString(Charsets.UTF_8).take(1000)}")
    }

    fun getSignedTo(url: String, output: OutputStream) {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        if (c.responseCode !in 200..299) {
            val bytes = c.errorStream?.use { it.readBytes() } ?: ByteArray(0)
            throw SupabaseException("Storage HTTP ${c.responseCode}: ${bytes.toString(Charsets.UTF_8).take(1000)}")
        }
        c.inputStream.use { input -> input.copyTo(output, 64 * 1024) }
    }

    fun getSigned(url: String): ByteArray = java.io.ByteArrayOutputStream().use { out -> getSignedTo(url, out); out.toByteArray() }

    fun downloadObjectTo(config: SupabaseConfig, session: AuthSession, bucket: String, path: String, output: OutputStream) {
        val c = connection("${root(config)}/storage/v1/object/$bucket/${encodedPath(path)}", "GET", config, session.accessToken)
        if (c.responseCode !in 200..299) {
            val bytes = c.errorStream?.use { it.readBytes() } ?: ByteArray(0)
            throw SupabaseException("HTTP ${c.responseCode}: ${bytes.toString(Charsets.UTF_8).take(1000)}")
        }
        c.inputStream.use { input -> input.copyTo(output, 64 * 1024) }
    }

    fun downloadObject(config: SupabaseConfig, session: AuthSession, bucket: String, path: String): ByteArray =
        java.io.ByteArrayOutputStream().use { out -> downloadObjectTo(config, session, bucket, path, out); out.toByteArray() }

    fun ping(config: SupabaseConfig, session: AuthSession) {
        read(connection("${root(config)}/rest/v1/skc_events?select=id&limit=1", "GET", config, session.accessToken))
    }
}
