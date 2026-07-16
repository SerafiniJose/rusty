package dev.rusty.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * Process-wide, synchronized bookkeeping for the two foreground-service notifications
 * (Spotify receiver, DLNA player) and their shared group summary.
 *
 * Why a coordinator: Android auto-bundles only at 4+ notifications, so the 2-child group needs
 * an explicitly posted summary — and posting/cancelling it from each service's own lifecycle
 * races (two concurrent onDestroys can each see the other's child and leave an orphan summary).
 * Both services run in one process, so a single lock removes the race entirely.
 *
 * POST_NOTIFICATIONS (API 33+) may be denied: notify() can throw SecurityException, and FGS
 * notifications then appear only in the task manager. Degrades to invisible, never to a crash.
 */
object ServiceNotifications {

    const val GROUP_KEY = "dev.rusty.services"
    private const val SUMMARY_ID = 3
    private const val SUMMARY_CHANNEL_ID = "rusty_services_group"

    enum class Kind { SPOTIFY, DLNA }

    private val active = mutableSetOf<Kind>()

    @Synchronized
    fun started(context: Context, kind: Kind) {
        active += kind
        postSummary(context)
    }

    @Synchronized
    fun stopped(context: Context, kind: Kind) {
        active -= kind
        if (active.isEmpty()) {
            runCatching { manager(context).cancel(SUMMARY_ID) }
        } else {
            postSummary(context)
        }
    }

    private fun postSummary(context: Context) {
        val manager = manager(context)
        manager.createNotificationChannel(
            NotificationChannel(SUMMARY_CHANNEL_ID, "Rusty services", NotificationManager.IMPORTANCE_LOW)
        )
        val summary: Notification = NotificationCompat.Builder(context, SUMMARY_CHANNEL_ID)
            .setContentTitle("Rusty")
            .setContentText("Services running")
            .setSmallIcon(R.drawable.ic_music_note)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setOngoing(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .build()
        runCatching { manager.notify(SUMMARY_ID, summary) }   // POST_NOTIFICATIONS may be denied
    }

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)
}
