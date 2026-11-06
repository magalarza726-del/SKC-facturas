package com.skcingenieria.facturas.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.skcingenieria.facturas.R
import com.skcingenieria.facturas.data.*
import com.skcingenieria.facturas.util.TimeUtils

class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val db = LocalDatabase(applicationContext)
        val state = LocalStateStore(db)
        val settings = state.settings()
        if (!settings.notificationsEnabled) return Result.success()

        val session = state.session()
        val profile = settings.profileUserId.ifBlank {
            val users = db.all(TYPE_USERS).map { UserProfile.fromJson(it.payload) }.filter { it.active }
            val uid = session?.authUserId.orEmpty().trim()
            val email = session?.email.orEmpty().trim()
            users.firstOrNull { uid.isNotBlank() && it.authUserId == uid }?.id
                ?: users.firstOrNull { email.isNotBlank() && it.email.equals(email, ignoreCase = true) }?.id
                ?: ""
        }
        if (profile.isBlank()) return Result.success()

        val due = db.all(TYPE_REMINDERS)
            .map { Reminder.fromJson(it.payload) }
            .filter { it.userId == profile && it.status == "PENDIENTE" && TimeUtils.isDue(it.nextNotifyAt) }
        if (due.isEmpty()) return Result.success()

        val canNotify = android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        // Do not consume/advance reminders when Android has denied notification permission.
        // Once permission is granted, the same due reminder can still be delivered.
        if (!canNotify) return Result.success()

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(CHANNEL_ID, "Recordatorios SKC", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Avisos de compras pendientes de registrar"
            setSound(sound, audio)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)

        val config = db.get(TYPE_APP_CONFIG, "global")?.payload?.let(AppConfig::fromJson) ?: AppConfig()
        due.forEach { reminder ->
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_skc)
                .setContentTitle("Compra pendiente")
                .setContentText(reminder.reason)
                .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.reason))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .build()
            manager.notify(reminder.id.hashCode(), notification)

            val now = TimeUtils.nowIso()
            val next = TimeUtils.plusMinutesIso(config.reminderIntervalMinutes)
            val updated = reminder.copy(
                nextNotifyAt = next,
                notificationCount = reminder.notificationCount + 1,
                updatedAt = now,
                syncStatus = SYNC_PENDING
            )
            db.put(TYPE_REMINDERS, reminder.id, updated.toJson(), SYNC_PENDING, now)
        }
        return Result.success()
    }

    companion object {
        private const val CHANNEL_ID = "skc_reminders_v2"
    }
}
