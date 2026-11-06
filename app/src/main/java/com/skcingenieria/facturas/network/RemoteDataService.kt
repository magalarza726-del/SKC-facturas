package com.skcingenieria.facturas.network

import com.skcingenieria.facturas.data.AuthSession
import com.skcingenieria.facturas.data.RemoteEvent
import com.skcingenieria.facturas.data.SupabaseConfig
import com.skcingenieria.facturas.data.DATA_BACKEND_SUPABASE

interface RemoteDataService {
    val provider: String
    fun bindMyProfile(): String?
    fun fetchEvents(): List<RemoteEvent>
    fun upsertEvent(event: RemoteEvent)
    fun ping()
}

class SupabaseRemoteDataAdapter(
    private val client: SupabaseRestClient,
    private val config: SupabaseConfig,
    private val session: AuthSession
) : RemoteDataService {
    override val provider: String = "SUPABASE"
    override fun bindMyProfile(): String? = client.bindMyProfile(config, session)
    override fun fetchEvents(): List<RemoteEvent> = client.fetchEvents(config, session)
    override fun upsertEvent(event: RemoteEvent) = client.upsertEvent(config, session, event)
    override fun ping() = client.ping(config, session)
}

object RemoteDataServiceFactory {
    /**
     * Single registration point for the shared-data backend. 3.12 keeps Supabase active,
     * while the UI can already configure/test alternate HTTPS connectors. Their data adapters
     * can be registered here later without changing repositories or the backup layer.
     */
    fun create(provider: String, client: SupabaseRestClient, config: SupabaseConfig, session: AuthSession): RemoteDataService =
        when (provider.uppercase()) {
            DATA_BACKEND_SUPABASE -> SupabaseRemoteDataAdapter(client, config, session)
            else -> error("El backend $provider todavía no está disponible en esta versión")
        }
}
