package com.skcingenieria.facturas.worker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.skcingenieria.facturas.data.LocalDatabase
import com.skcingenieria.facturas.data.LocalStateStore
import com.skcingenieria.facturas.repository.AppRepository

class DailyBackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val state = LocalStateStore(LocalDatabase(applicationContext))
        val settings = state.settings()
        if (!settings.infrastructure.dailyDriveBackupEnabled) return Result.success()
        val remoteProviders = setOf(
            com.skcingenieria.facturas.data.BACKUP_GOOGLE_DRIVE,
            com.skcingenieria.facturas.data.BACKUP_ONEDRIVE,
            com.skcingenieria.facturas.data.BACKUP_VPS
        )
        if (settings.infrastructure.backupProviders.none { it in remoteProviders }) return Result.success()
        if (state.session() == null || settings.profileUserId.isBlank()) return Result.retry()

        return try {
            val repo = AppRepository(applicationContext)
            if (!repo.snapshot().isAdmin()) return Result.success()
            repo.createAutomaticBackup()
            Result.success()
        } catch (_: SecurityException) {
            // A selected document provider no longer grants access; the user must reconnect it.
            Result.failure()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
