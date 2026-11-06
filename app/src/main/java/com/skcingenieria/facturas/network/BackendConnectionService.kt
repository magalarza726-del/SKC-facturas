package com.skcingenieria.facturas.network

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Verifies a future/alternate data backend through an HTTPS API bridge.
 *
 * The Android app intentionally does not open direct PostgreSQL/MySQL/SQL Server
 * sockets and never stores raw database passwords. A server-side API/bridge keeps
 * those credentials outside the APK and exposes the contract used by SKC Facturas.
 */
class BackendConnectionService {
    fun test(baseUrl: String, healthPath: String, bearerToken: String): String {
        val cleanBase = baseUrl.trim().trimEnd('/')
        require(cleanBase.isNotBlank()) { "Configura la URL HTTPS del conector" }
        require(cleanBase.startsWith("https://")) { "El conector debe usar HTTPS" }
        val cleanPath = healthPath.trim().ifBlank { "/health" }.let { if (it.startsWith('/')) it else "/$it" }
        val connection = (URL(cleanBase + cleanPath).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 30_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            if (bearerToken.isNotBlank()) setRequestProperty("Authorization", "Bearer ${bearerToken.trim()}")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
                error("El conector respondió HTTP $code${detail.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
            }
            runCatching {
                val json = JSONObject(body.ifBlank { "{}" })
                json.optString("message").ifBlank { json.optString("status") }.ifBlank { "API accesible" }
            }.getOrDefault("API accesible")
        } finally {
            connection.disconnect()
        }
    }
}
