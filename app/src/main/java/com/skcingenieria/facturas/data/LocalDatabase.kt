package com.skcingenieria.facturas.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

const val TYPE_USERS = "users"
const val TYPE_CATALOGS = "catalogs"
const val TYPE_APP_CONFIG = "appConfig"
const val TYPE_TRANSACTIONS = "transactions"
const val TYPE_REMINDERS = "reminders"
const val TYPE_MESSAGES = "messages"
const val TYPE_LEDGER = "ledger"
const val TYPE_AUDIT = "audit"

val SYNC_ENTITY_TYPES = listOf(TYPE_USERS, TYPE_CATALOGS, TYPE_APP_CONFIG, TYPE_TRANSACTIONS, TYPE_REMINDERS, TYPE_MESSAGES, TYPE_LEDGER, TYPE_AUDIT)

data class EntityRow(
    val type: String,
    val id: String,
    val payload: JSONObject,
    val syncStatus: String,
    val updatedAt: String
)

class LocalDatabase(context: Context) : SQLiteOpenHelper(context, "skc_facturas.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE entities(
              type TEXT NOT NULL,
              id TEXT NOT NULL,
              payload TEXT NOT NULL,
              sync_status TEXT NOT NULL,
              updated_at TEXT NOT NULL,
              PRIMARY KEY(type,id)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_entities_sync ON entities(sync_status,type)")
        db.execSQL("CREATE INDEX idx_entities_updated ON entities(updated_at)")
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun put(type: String, id: String, payload: JSONObject, syncStatus: String, updatedAt: String) {
        val p = JSONObject(payload.toString()).apply {
            put("syncStatus", syncStatus)
            put("syncError", if (syncStatus == SYNC_ERROR) optString("syncError") else "")
            if (!has("updatedAt") || optString("updatedAt").isBlank()) put("updatedAt", updatedAt)
        }
        writableDatabase.insertWithOnConflict("entities", null, ContentValues().apply {
            put("type", type); put("id", id); put("payload", p.toString()); put("sync_status", syncStatus); put("updated_at", updatedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun get(type: String, id: String): EntityRow? = readableDatabase.query(
        "entities", arrayOf("type","id","payload","sync_status","updated_at"), "type=? AND id=?", arrayOf(type,id), null,null,null
    ).use { c -> if (!c.moveToFirst()) null else c.toRow() }

    fun all(type: String): List<EntityRow> = readableDatabase.query(
        "entities", arrayOf("type","id","payload","sync_status","updated_at"), "type=?", arrayOf(type), null,null,"updated_at DESC"
    ).use { c -> buildList { while (c.moveToNext()) add(c.toRow()) } }

    fun allEntities(): List<EntityRow> = readableDatabase.query(
        "entities", arrayOf("type","id","payload","sync_status","updated_at"), null,null,null,null,"updated_at ASC"
    ).use { c -> buildList { while (c.moveToNext()) add(c.toRow()) } }

    fun pending(): List<EntityRow> = readableDatabase.query(
        "entities", arrayOf("type","id","payload","sync_status","updated_at"), "sync_status<>?", arrayOf(SYNCED), null,null,"updated_at ASC"
    ).use { c -> buildList { while (c.moveToNext()) add(c.toRow()) } }

    fun delete(type: String, id: String) { writableDatabase.delete("entities", "type=? AND id=?", arrayOf(type,id)) }

    fun clearAllEntities() { writableDatabase.delete("entities", null, null) }

    fun deleteWhereSyncedAbsent(remoteKeys: Set<String>) {
        writableDatabase.beginTransaction()
        try {
            allEntities().filter { it.syncStatus == SYNCED && "${it.type}:${it.id}" !in remoteKeys }.forEach { delete(it.type, it.id) }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun markSync(type: String, id: String, status: String, error: String = "") {
        val row = get(type, id) ?: return
        val p = JSONObject(row.payload.toString()).apply { put("syncStatus", status); put("syncError", error) }
        put(type, id, p, status, row.updatedAt)
    }

    fun meta(key: String): String? = readableDatabase.query("meta", arrayOf("value"), "key=?", arrayOf(key), null,null,null).use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

    fun setMeta(key: String, value: String) {
        writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearMeta(key: String) { writableDatabase.delete("meta", "key=?", arrayOf(key)) }

    fun <T> transaction(block: () -> T): T {
        writableDatabase.beginTransaction()
        return try { val result = block(); writableDatabase.setTransactionSuccessful(); result } finally { writableDatabase.endTransaction() }
    }

    private fun android.database.Cursor.toRow() = EntityRow(
        type = getString(getColumnIndexOrThrow("type")), id = getString(getColumnIndexOrThrow("id")),
        payload = JSONObject(getString(getColumnIndexOrThrow("payload"))),
        syncStatus = getString(getColumnIndexOrThrow("sync_status")), updatedAt = getString(getColumnIndexOrThrow("updated_at"))
    )
}
