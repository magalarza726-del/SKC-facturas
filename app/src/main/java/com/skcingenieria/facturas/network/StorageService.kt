package com.skcingenieria.facturas.network

import com.skcingenieria.facturas.data.AuthSession
import com.skcingenieria.facturas.data.STORAGE_R2
import com.skcingenieria.facturas.data.STORAGE_SUPABASE
import com.skcingenieria.facturas.data.StorageConfig
import com.skcingenieria.facturas.data.SupabaseConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest


data class StorageCapabilities(
    val signedUrls: Boolean = false,
    val streamingDownload: Boolean = true,
    val remoteChecksum: Boolean = false,
    val folders: Boolean = false
)

data class StorageObjectMetadata(
    val provider: String,
    val key: String,
    val sizeBytes: Long,
    val mimeType: String = "",
    val checksum: String = "",
    val providerObjectId: String = key
)

data class StorageTestResult(
    val provider: String,
    val message: String,
    val bucket: String = "",
    val readOk: Boolean = true,
    val writeOk: Boolean = true
)

interface StorageService {
    val provider: String
    val capabilities: StorageCapabilities
    fun upload(file: File, key: String, mimeType: String, checksum: String = ""): StorageObjectMetadata
    fun downloadTo(key: String, output: OutputStream)
    fun download(key: String): ByteArray = ByteArrayOutputStream().use { out -> downloadTo(key, out); out.toByteArray() }
    fun delete(key: String)
    fun list(prefix: String = ""): List<StorageObjectMetadata>
    fun exists(key: String): Boolean
    fun getMetadata(key: String): StorageObjectMetadata?
    fun getTemporaryUrl(key: String): String?
    fun testConnection(): StorageTestResult
}

private fun sha256(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

class SupabaseStorageAdapter(
    private val client: SupabaseRestClient,
    private val supabase: SupabaseConfig,
    private val session: AuthSession,
    private val storage: StorageConfig
) : StorageService {
    override val provider: String = STORAGE_SUPABASE
    override val capabilities = StorageCapabilities(streamingDownload = true, folders = true)

    override fun upload(file: File, key: String, mimeType: String, checksum: String): StorageObjectMetadata {
        val expectedSize = file.length()
        client.uploadFile(supabase, session, storage.supabaseBucket, key, file, mimeType)
        return StorageObjectMetadata(provider, key, expectedSize, mimeType, checksum)
    }

    override fun downloadTo(key: String, output: OutputStream) =
        client.downloadObjectTo(supabase, session, storage.supabaseBucket, key, output)

    override fun delete(key: String) {
        client.deleteObject(supabase, session, storage.supabaseBucket, key)
    }

    override fun list(prefix: String): List<StorageObjectMetadata> =
        client.listStorageObjects(supabase, session, storage.supabaseBucket, prefix)

    override fun exists(key: String): Boolean = runCatching {
        val sink = object : OutputStream() { override fun write(b: Int) = Unit; override fun write(b: ByteArray, off: Int, len: Int) = Unit }
        downloadTo(key, sink)
        true
    }.getOrDefault(false)

    override fun getMetadata(key: String): StorageObjectMetadata? = runCatching {
        val bytes = download(key)
        StorageObjectMetadata(provider, key, bytes.size.toLong(), checksum = sha256(bytes))
    }.getOrNull()

    override fun getTemporaryUrl(key: String): String? = null

    override fun testConnection(): StorageTestResult {
        val testKey = "${storage.basePrefix.trim('/')}/${session.authUserId}/_connection_test/test.pdf"
        val temp = File.createTempFile("skc-storage-", ".pdf")
        try {
            temp.writeText("SKC storage test")
            upload(temp, testKey, "application/pdf")
            val readOk = download(testKey).isNotEmpty()
            delete(testKey)
            return StorageTestResult(provider, "Conexión correcta con Supabase Storage", storage.supabaseBucket, readOk, true)
        } finally {
            temp.delete()
        }
    }
}

class R2StorageAdapter(
    private val client: SupabaseRestClient,
    private val supabase: SupabaseConfig,
    private val session: AuthSession,
    private val storage: StorageConfig
) : StorageService {
    override val provider: String = STORAGE_R2
    override val capabilities = StorageCapabilities(signedUrls = true, streamingDownload = true, remoteChecksum = true, folders = true)

    private fun call(action: String, key: String = "", mime: String = "", size: Long = 0, checksum: String = ""): JSONObject {
        val body = JSONObject().apply {
            put("action", action)
            if (key.isNotBlank()) put("key", key)
            if (mime.isNotBlank()) put("mimeType", mime)
            if (size > 0) put("sizeBytes", size)
            if (checksum.isNotBlank()) put("checksum", checksum)
        }
        val result = client.invokeFunction(supabase, session, storage.r2FunctionName, body)
        if (!result.optBoolean("ok", false)) error(result.optString("error", "Cloudflare R2 no respondió correctamente"))
        return result
    }

    override fun upload(file: File, key: String, mimeType: String, checksum: String): StorageObjectMetadata {
        val expectedSize = file.length()
        require(expectedSize > 0) { "El archivo está vacío" }
        val expectedChecksum = checksum.ifBlank { StorageIntegrity.sha256(file) }
        try {
            val signed = call("presignUpload", key, mimeType, expectedSize, expectedChecksum)
            val url = signed.optString("url")
            require(url.startsWith("https://")) { "R2 no devolvió una URL de subida válida" }
            val headers = signed.optJSONObject("headers")?.let { json ->
                buildMap { json.keys().forEach { name -> put(name, json.optString(name)) } }
            }.orEmpty()
            client.putSigned(url, file, mimeType, headers)

            val head = call("head", key)
            val remoteSize = head.optLong("sizeBytes", -1)
            val remoteChecksum = head.optString("checksum")
            if (remoteSize == expectedSize && remoteChecksum.isNotBlank() && remoteChecksum.equals(expectedChecksum, true)) {
                return StorageObjectMetadata(provider, key, expectedSize, mimeType, expectedChecksum, head.optString("objectId", key))
            }

            // R2 3.15.2 deja de firmar x-amz-meta-sha256 para evitar firmas inválidas.
            // Si HEAD no trae checksum, verificamos una vez descargando el objeto y comparando SHA-256.
            val downloaded = download(key)
            val downloadedChecksum = StorageIntegrity.sha256(downloaded)
            require(downloaded.size.toLong() == expectedSize && downloadedChecksum.equals(expectedChecksum, true)) {
                "R2 no pudo verificar la integridad del archivo subido"
            }
            return StorageObjectMetadata(provider, key, expectedSize, mimeType, expectedChecksum, head.optString("objectId", key))
        } catch (e: Exception) {
            runCatching { call("delete", key) }
            throw e
        }
    }

    override fun downloadTo(key: String, output: OutputStream) {
        val signed = call("presignDownload", key)
        val url = signed.optString("url")
        require(url.startsWith("https://")) { "R2 no devolvió una URL de descarga válida" }
        client.getSignedTo(url, output)
    }

    override fun delete(key: String) {
        call("delete", key)
    }

    override fun list(prefix: String): List<StorageObjectMetadata> {
        val objects = mutableListOf<StorageObjectMetadata>()
        var token = ""
        do {
            val body = JSONObject().apply {
                put("action", "list")
                put("prefix", prefix.trim('/'))
                put("maxKeys", 1000)
                if (token.isNotBlank()) put("continuationToken", token)
            }
            val result = client.invokeFunction(supabase, session, storage.r2FunctionName, body)
            if (!result.optBoolean("ok", false)) error(result.optString("error", "Cloudflare R2 no permitió listar archivos"))
            val items = result.optJSONArray("objects") ?: org.json.JSONArray()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val key = item.optString("key")
                if (key.isBlank()) continue
                objects += StorageObjectMetadata(
                    provider = provider,
                    key = key,
                    sizeBytes = item.optLong("sizeBytes", 0L),
                    mimeType = item.optString("mimeType"),
                    checksum = item.optString("checksum"),
                    providerObjectId = item.optString("objectId", key)
                )
            }
            token = result.optString("nextContinuationToken")
        } while (token.isNotBlank())
        return objects
    }

    override fun exists(key: String): Boolean = runCatching { call("head", key); true }.getOrDefault(false)

    override fun getMetadata(key: String): StorageObjectMetadata? = runCatching {
        val meta = call("head", key)
        StorageObjectMetadata(
            provider = provider,
            key = key,
            sizeBytes = meta.optLong("sizeBytes", 0),
            mimeType = meta.optString("mimeType"),
            checksum = meta.optString("checksum"),
            providerObjectId = meta.optString("objectId", key)
        )
    }.getOrNull()

    override fun getTemporaryUrl(key: String): String? = runCatching { call("presignDownload", key).optString("url") }.getOrNull()

    override fun testConnection(): StorageTestResult {
        val result = call("test")
        return StorageTestResult(
            provider = provider,
            message = result.optString("message", "Cloudflare R2 conectado"),
            bucket = result.optString("bucket"),
            readOk = result.optBoolean("readOk", true),
            writeOk = result.optBoolean("writeOk", true)
        )
    }
}

object StorageIntegrity {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

object StorageServiceFactory {
    private val supported = setOf(STORAGE_SUPABASE, STORAGE_R2)

    fun supportedProviders(): Set<String> = supported

    fun create(
        provider: String,
        client: SupabaseRestClient,
        supabase: SupabaseConfig,
        session: AuthSession,
        storage: StorageConfig
    ): StorageService = when (provider.uppercase()) {
        STORAGE_R2 -> R2StorageAdapter(client, supabase, session, storage)
        STORAGE_SUPABASE -> SupabaseStorageAdapter(client, supabase, session, storage)
        else -> error("Proveedor de almacenamiento no soportado: $provider")
    }
}
