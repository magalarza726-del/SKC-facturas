package com.skcingenieria.facturas.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.skcingenieria.facturas.MainActivity
import com.skcingenieria.facturas.R
import com.skcingenieria.facturas.data.LocalDatabase
import com.skcingenieria.facturas.data.LocalStateStore
import com.skcingenieria.facturas.data.Message
import com.skcingenieria.facturas.data.TYPE_MESSAGES
import com.skcingenieria.facturas.util.TimeUtils
import java.text.NumberFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

/**
 * Local notifications generated from remote message changes discovered by SyncEngine.
 *
 * This intentionally lives on top of the existing Supabase synchronization instead of
 * maintaining a second source of truth. While the app is open, the 3-second poller can trigger
 * these notifications almost immediately. In background, Android WorkManager triggers the same
 * SyncEngine periodically.
 *
 * Deduplication is persisted in LocalDatabase.meta, so the same remote revision is never notified
 * repeatedly just because the app polls every few seconds.
 */
class MessageNotificationManager(
    private val context: Context,
    private val db: LocalDatabase,
    private val state: LocalStateStore
) {
    data class Notice(val title: String, val body: String)

    fun onRemoteMessage(previous: Message?, incoming: Message, currentUserId: String) {
        if (currentUserId.isBlank()) return
        if (!state.settings().notificationsEnabled) return
        if (incoming.senderUserId != currentUserId && incoming.recipientUserId != currentUserId) return

        val signature = listOf(
            incoming.updatedAt,
            incoming.status,
            incoming.editedAt,
            incoming.responseTransferId,
            incoming.rejectedReason
        ).joinToString("|")
        val signatureKey = "$SIGNATURE_PREFIX${incoming.id}"
        if (db.meta(signatureKey) == signature) return

        // Avoid a storm of notifications when an existing installation first gets this feature.
        // The previous successful sync is a natural watermark: only newer remote changes notify.
        val baseline = notificationBaseline()
        val eventInstant = instant(incoming.updatedAt.ifBlank { incoming.createdAt })
        if (eventInstant <= baseline) {
            db.setMeta(signatureKey, signature)
            return
        }

        val notice = buildNotice(previous, incoming, currentUserId)
        db.setMeta(signatureKey, signature)
        if (notice != null) notify(incoming.id, notice)
    }

    private fun buildNotice(previous: Message?, incoming: Message, currentUserId: String): Notice? {
        val isIncoming = incoming.recipientUserId == currentUserId
        val isMine = incoming.senderUserId == currentUserId
        val amount = money(incoming.amount)

        if (previous == null && isIncoming) {
            return when (incoming.messageType) {
                "SOLICITUD_MONTO" -> Notice(
                    "Solicitud de transferencia",
                    compact("${incoming.senderName} solicitó $amount", incoming.reason)
                )
                "TRANSFERENCIA" -> {
                    if (incoming.sourceRequestId.isNotBlank()) {
                        val requestedAmount = db.get(TYPE_MESSAGES, incoming.sourceRequestId)
                            ?.payload
                            ?.let { runCatching { Message.fromJson(it).amount }.getOrNull() }
                        val amountText = if (requestedAmount != null && kotlin.math.abs(requestedAmount - incoming.amount) >= 0.005) {
                            "Solicitaste ${money(requestedAmount)} · aprobado $amount"
                        } else {
                            "Monto aprobado: $amount"
                        }
                        Notice(
                            "Solicitud aprobada",
                            compact("${incoming.senderName} aprobó tu solicitud. $amountText", incoming.reason)
                        )
                    } else {
                        Notice(
                            "Transferencia recibida",
                            compact("${incoming.senderName} te envió $amount", incoming.reason)
                        )
                    }
                }
                "SOLICITUD_PROVEEDOR" -> Notice(
                    "Nuevo proveedor",
                    incoming.reason.ifBlank { "${incoming.senderName} registró una solicitud de proveedor" }
                )
                else -> Notice(
                    "Nuevo mensaje de ${incoming.senderName}",
                    incoming.reason.ifBlank { "Tienes un nuevo mensaje en SKC Facturas" }
                )
            }
        }

        if (previous != null && previous.status != incoming.status && isMine) {
            return when (incoming.messageType) {
                "TRANSFERENCIA" -> when (incoming.status) {
                    "CONFIRMADO" -> Notice(
                        "Transferencia confirmada",
                        "${incoming.recipientName} confirmó que recibió $amount"
                    )
                    "RECHAZADO" -> Notice(
                        "Transferencia rechazada",
                        compact("${incoming.recipientName} rechazó tu transferencia de $amount", incoming.rejectedReason)
                    )
                    else -> null
                }
                "SOLICITUD_MONTO" -> when (incoming.status) {
                    "RESPONDIDA" -> if (incoming.responseTransferId.isBlank()) Notice(
                        "Solicitud respondida",
                        "${incoming.recipientName} respondió tu solicitud de $amount"
                    ) else null
                    "RECHAZADO" -> Notice(
                        "Solicitud rechazada",
                        compact("${incoming.recipientName} rechazó tu solicitud de $amount", incoming.rejectedReason)
                    )
                    else -> null
                }
                "SOLICITUD_PROVEEDOR" -> when (incoming.status) {
                    "APROBADO" -> Notice("Proveedor aprobado", incoming.reason.ifBlank { "Tu proveedor fue aprobado" })
                    "RECHAZADO" -> Notice("Proveedor rechazado", compact(incoming.reason, incoming.rejectedReason))
                    else -> null
                }
                else -> null
            }
        }

        // If the sender edits an already-delivered request/transfer, the recipient should know.
        if (
            previous != null &&
            isIncoming &&
            incoming.editedAt.isNotBlank() &&
            incoming.editedAt != previous.editedAt &&
            incoming.editedByUserId != currentUserId
        ) {
            return Notice(
                "Mensaje actualizado",
                compact("${incoming.senderName} actualizó el mensaje", incoming.reason)
            )
        }

        return null
    }

    private fun notificationBaseline(): Instant {
        val existing = db.meta(BASELINE_KEY)
        if (!existing.isNullOrBlank()) return instant(existing)

        val previousSync = state.settings().supabase.lastSyncAt
        val seed = previousSync.ifBlank { TimeUtils.nowIso() }
        db.setMeta(BASELINE_KEY, seed)
        return instant(seed)
    }

    private fun notify(messageId: String, notice: Notice) {
        val canNotify = android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!canNotify) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_messages", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            messageId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_skc)
            .setContentTitle(notice.title)
            .setContentText(notice.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(messageId.hashCode(), notification)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Mensajes y transferencias SKC",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Solicitudes, transferencias, confirmaciones y cambios de mensajes"
            setSound(sound, audio)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun money(value: Double): String = runCatching {
        NumberFormat.getCurrencyInstance(Locale("es", "EC")).format(value)
    }.getOrElse { "\$${"%.2f".format(Locale.US, value)}" }

    private fun compact(first: String, second: String): String = when {
        first.isBlank() -> second
        second.isBlank() -> first
        else -> "$first · $second"
    }

    private fun instant(value: String): Instant = runCatching { OffsetDateTime.parse(value).toInstant() }
        .recoverCatching { Instant.parse(value) }
        .getOrDefault(Instant.EPOCH)

    companion object {
        private const val CHANNEL_ID = "skc_messages"
        private const val BASELINE_KEY = "message_notifications_baseline"
        private const val SIGNATURE_PREFIX = "message_notification_signature_"
    }
}
