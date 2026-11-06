package com.skcingenieria.facturas.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.skcingenieria.facturas.repository.PortableExportResult
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class ExternalBackupService(private val context: Context) {
    private val resolver get() = context.contentResolver

    fun persistTreePermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.takePersistableUriPermission(uri, flags)
    }

    fun releaseTreePermission(uriText: String) {
        if (uriText.isBlank()) return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(uriText), flags) }
    }

    private fun directoryUri(treeUri: Uri): Uri {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    }

    fun testTree(treeUriText: String): String {
        val treeUri = Uri.parse(treeUriText.takeIf { it.isNotBlank() } ?: error("Primero conecta una carpeta"))
        val testName = "SKC_conexion_${System.currentTimeMillis()}.txt"
        val created = DocumentsContract.createDocument(resolver, directoryUri(treeUri), "text/plain", testName)
            ?: error("El proveedor no permitió crear el archivo de prueba")
        resolver.openOutputStream(created, "w")?.use { it.write("SKC Facturas: conexión correcta".toByteArray()) }
            ?: error("No se pudo escribir en la carpeta seleccionada")
        runCatching { DocumentsContract.deleteDocument(resolver, created) }
        return "Carpeta accesible para lectura/escritura"
    }

    fun createTreeBackup(
        treeUriText: String,
        fileName: String,
        writer: (OutputStream) -> PortableExportResult
    ): PortableExportResult {
        val treeUri = Uri.parse(treeUriText.takeIf { it.isNotBlank() } ?: error("Primero conecta una carpeta"))
        val created = DocumentsContract.createDocument(resolver, directoryUri(treeUri), "application/zip", fileName)
            ?: error("El proveedor no permitió crear el respaldo")
        return try {
            resolver.openOutputStream(created, "w")?.use(writer)
                ?: error("No se pudo abrir el archivo de respaldo")
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw e
        }
    }

    fun testVps(baseUrl: String, token: String): String {
        val connection = open(baseUrl, "/health", "GET", token)
        return try {
            val code = connection.responseCode
            val body = readResponse(connection)
            if (code !in 200..299) error(vpsError(code, body))
            JSONObject(body.ifBlank { "{}" }).optString("message", "API accesible")
        } finally {
            connection.disconnect()
        }
    }

    fun uploadVpsBackup(
        baseUrl: String,
        token: String,
        fileName: String,
        writer: (OutputStream) -> PortableExportResult
    ): PortableExportResult {
        val encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8.name())
        val connection = open(baseUrl, "/backup?name=$encoded", "PUT", token).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/zip")
            setChunkedStreamingMode(64 * 1024)
        }
        return try {
            val result = connection.outputStream.use(writer)
            val code = connection.responseCode
            val body = readResponse(connection)
            if (code !in 200..299) error(vpsError(code, body))
            result
        } finally {
            connection.disconnect()
        }
    }

    private fun open(baseUrl: String, path: String, method: String, token: String): HttpURLConnection {
        val cleanBase = baseUrl.trim().trimEnd('/')
        require(cleanBase.isNotBlank()) { "Configura la URL de la API del VPS" }
        require(cleanBase.startsWith("https://")) { "La URL de una futura API/VPS debe usar https://" }
        return (URL(cleanBase + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 60_000
            useCaches = false
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer ${token.trim()}")
            setRequestProperty("Accept", "application/json")
        }
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun vpsError(code: Int, body: String): String {
        val parsed = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
        return "La API del VPS respondió HTTP $code${parsed.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}"
    }
}
