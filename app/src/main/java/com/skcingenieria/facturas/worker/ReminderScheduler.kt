package com.skcingenieria.facturas.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object ReminderScheduler {
    fun ensureScheduled(context: Context) {
        val work=WorkManager.getInstance(context)
        val request=PeriodicWorkRequestBuilder<ReminderWorker>(15,TimeUnit.MINUTES).build()
        work.enqueueUniquePeriodicWork("skc-hourly-reminders",ExistingPeriodicWorkPolicy.UPDATE,request)
        val sync=PeriodicWorkRequestBuilder<SyncWorker>(15,TimeUnit.MINUTES).build()
        work.enqueueUniquePeriodicWork("skc-background-sync",ExistingPeriodicWorkPolicy.UPDATE,sync)
        val settings = com.skcingenieria.facturas.data.LocalStateStore(com.skcingenieria.facturas.data.LocalDatabase(context)).settings()
        val remoteProvider = settings.infrastructure.preferredBackupProvider in setOf(
            com.skcingenieria.facturas.data.BACKUP_GOOGLE_DRIVE,
            com.skcingenieria.facturas.data.BACKUP_ONEDRIVE,
            com.skcingenieria.facturas.data.BACKUP_VPS
        )
        BackupScheduler.configure(
            context,
            settings.infrastructure.dailyDriveBackupEnabled && remoteProvider,
            settings.infrastructure.dailyBackupHour
        )
    }
}
