package com.skcingenieria.facturas.worker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.skcingenieria.facturas.data.LocalDatabase
import com.skcingenieria.facturas.data.LocalStateStore
import com.skcingenieria.facturas.network.SyncEngine

class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val db=LocalDatabase(applicationContext); val state=LocalStateStore(db); val settings=state.settings()
        if(!settings.supabase.autoSync || settings.supabase.projectUrl.isBlank() || state.session()==null) return Result.success()
        return try { SyncEngine(db, state, context = applicationContext).sync(); Result.success() } catch (_:Exception) { Result.retry() }
    }
}
