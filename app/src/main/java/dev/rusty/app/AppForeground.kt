package dev.rusty.app

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings

/**
 * The one place that knows how to put Rusty's window in front of whatever else is on screen.
 *
 * Two callers need this and must not drift apart: [PlaybackTakeoverCoordinator] (a cast started,
 * show the now-playing page) and the remote-control API's `POST /api/foreground` (the user flipped
 * the switch on their phone). They were the same three lines twice; the ordering rule below is
 * subtle enough that having two copies of it was a latent bug.
 *
 * ## Wake BEFORE launch, always
 * With the display genuinely asleep a launched Activity may never resume, so the panel has to be
 * lighting up first. The takeover coordinator enforces this by emitting `WAKE_SCREEN` alongside
 * `BRING_TO_FRONT` and running the wake first; [bringToFront] composes them in the same order for
 * callers that have no policy layer of their own.
 *
 * ## The permission, and why it is checked rather than assumed
 * Android 10+ blocks activity starts from the background. The exemption Rusty relies on is the
 * "Display over other apps" (`SYSTEM_ALERT_WINDOW`) grant, which the user can revoke at any moment
 * — so [canBringForward] is a live question asked per command, never a value cached when some
 * settings row was drawn. Even holding it, a few OEM builds block the launch anyway and do so
 * SILENTLY: [launchHome] cannot report that, which is why every caller treats a bring-forward as a
 * request to be confirmed later rather than an action that succeeded.
 */
object AppForeground {

    /** Matches the takeover's wake: long enough to outlast the launch, short enough that a failed
     *  launch does not hold the panel awake for minutes. */
    private const val WAKE_TIMEOUT_MS = 5_000L

    /** Whether a bring-to-front would even be attempted. Asked per command — the grant can be
     *  revoked long after any UI that offered the action was drawn. */
    fun canBringForward(context: Context): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    /**
     * Relights the panel. [fakeOffActive] is the remote-control fake-off (a black overlay held by
     * a live window), which must be cleared through [ScreenControlModel] so the API snapshot and
     * any open control page see it happen.
     *
     * The wake lock is UNCONDITIONAL rather than an alternative to that branch: the desired state
     * outlives the Activity, so a fake-off sitting behind a stopped window has already let the
     * panel really sleep, and flipping the model to "on" alone only notifies a renderer that a
     * genuinely dark display cannot act on.
     */
    @Suppress("DEPRECATION") // SCREEN_BRIGHT_WAKE_LOCK is the only wake-without-activity mechanism
    fun wakeScreen(context: Context, fakeOffActive: Boolean) {
        if (fakeOffActive) ScreenControlModel.set(on = true, brightness = null)
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "Rusty::PlaybackWake",
        ).acquire(WAKE_TIMEOUT_MS)
        // ON_AFTER_RELEASE pokes the user-activity timer on release, not just at acquire: without
        // it the display-off timeout is evaluated against whatever user activity last happened —
        // possibly long before the device went to sleep — so the panel can drop straight back to
        // black the instant this 5s lock expires.
    }

    /**
     * Starts [HomeActivity] over whatever is in front. Best-effort by nature: SAW holders are
     * exempt from background-activity-launch blocks on most builds, and where an OEM blocks it
     * anyway the failure is silent, so there is deliberately no fallback attempt here.
     */
    fun launchHome(context: Context) {
        val intent = Intent(context, HomeActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
        runCatching { context.startActivity(intent) }
    }

    /**
     * Wake, then launch — for callers with no policy layer to sequence the two themselves
     * (the remote-control route). Does NOT check [canBringForward]: the caller decides what a
     * missing grant means for it, and the API answers 409 rather than firing a launch the system
     * will drop on the floor.
     */
    fun bringToFront(context: Context) {
        wakeScreen(context, fakeOffActive = !ScreenControlModel.desired().on)
        launchHome(context)
    }
}
