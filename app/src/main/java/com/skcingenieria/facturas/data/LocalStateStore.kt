package com.skcingenieria.facturas.data

import org.json.JSONObject

class LocalStateStore(private val db: LocalDatabase) {
    fun settings(): LocalSettings = LocalSettings.fromJson(db.meta("local_settings")?.let(::JSONObject))
    fun saveSettings(settings: LocalSettings) = db.setMeta("local_settings", settings.toJson().toString())
    fun session(): AuthSession? = AuthSession.fromJson(db.meta("auth_session")?.let(::JSONObject))
    fun saveSession(session: AuthSession?) {
        if (session == null) db.clearMeta("auth_session") else db.setMeta("auth_session", session.toJson().toString())
    }

    /**
     * Keeps the Supabase bootstrap editor open only for a genuinely new installation.
     *
     * - Fresh install without a connection: bootstrap stays open until the first successful login.
     * - Upgrade with an already configured Supabase project: bootstrap starts locked.
     *
     * The marker is intentionally independent from the current auth session so logging out later
     * never re-opens infrastructure settings to a normal user.
     */
    fun initialSetupOpen(): Boolean {
        db.meta("initial_setup_open")?.let { return it == "1" }
        val config = settings().supabase
        val open = config.projectUrl.isBlank() || config.publishableKey.isBlank()
        db.setMeta("initial_setup_open", if (open) "1" else "0")
        return open
    }

    fun completeInitialSetup() = db.setMeta("initial_setup_open", "0")

    fun bundledSuppliersSeedVersion(): Int = db.meta("bundled_suppliers_seed_version")?.toIntOrNull() ?: 0

    fun markBundledSuppliersSeeded(version: Int = 1) =
        db.setMeta("bundled_suppliers_seed_version", version.coerceAtLeast(1).toString())

    fun vpsBackupToken(): String = db.meta("vps_backup_token").orEmpty()
    fun saveVpsBackupToken(token: String) {
        if (token.isBlank()) db.clearMeta("vps_backup_token") else db.setMeta("vps_backup_token", token.trim())
    }

    fun backendApiToken(): String = db.meta("backend_api_token").orEmpty()
    fun saveBackendApiToken(token: String) {
        if (token.isBlank()) db.clearMeta("backend_api_token") else db.setMeta("backend_api_token", token.trim())
    }

    fun backendApiToken(provider: String): String = db.meta("backend_api_token_${provider.lowercase()}").orEmpty()
    fun saveBackendApiToken(provider: String, token: String) {
        val key = "backend_api_token_${provider.lowercase()}"
        if (token.isBlank()) db.clearMeta(key) else db.setMeta(key, token.trim())
    }
}
