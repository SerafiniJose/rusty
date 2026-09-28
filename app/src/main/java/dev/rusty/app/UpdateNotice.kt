package dev.rusty.app

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The app side of [UpdateRepository]: persists its once-a-day answer, remembers which release the
 * user has already looked at in About, and tells the info buttons when their dot should change.
 *
 * The dot shows while an update is available and its version has not been opened in About yet
 * ([UpdateRepository.shouldShowDot]); the Info sheet's chip stays green until the update is
 * installed.
 */
object UpdateNotice {

    private const val PREFS_NAME = "update_prefs"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_SEEN_VERSION = "seen_version"

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var appContext: Context? = null

    /** Once, from [RustyApp.onCreate], before any check runs. */
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        UpdateRepository.store = object : UpdateRepository.CacheStore {
            override fun load(): String? = prefs.getString(KEY_LAST_CHECK, null)
            override fun save(json: String) {
                prefs.edit().putString(KEY_LAST_CHECK, json).apply()
            }
        }
        UpdateRepository.onResult = { main.post(::notifyListeners) }
    }

    /** Main-thread listeners, called after every check and when a release is marked seen. */
    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    fun current(): UpdateRepository.UpdateCheck? = UpdateRepository.peek(BuildConfig.VERSION_NAME)

    fun dotVisible(): Boolean = UpdateRepository.shouldShowDot(current(), seenVersion())

    /** About was opened with [version] on offer: the dot goes, the green chip stays. */
    fun markSeen(version: String) {
        val prefs = prefs() ?: return
        if (prefs.getString(KEY_SEEN_VERSION, null) == version) return
        prefs.edit().putString(KEY_SEEN_VERSION, version).apply()
        notifyListeners()
    }

    private fun seenVersion(): String? = prefs()?.getString(KEY_SEEN_VERSION, null)

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun notifyListeners() {
        listeners.forEach { runCatching { it() } }
    }
}

/**
 * Draws the update dot on an info button: 10 dp of brand green in the top-right corner, cut out
 * from the icon by a 2 dp ring of the screen's background colour. Drawn in the view's overlay, so
 * no layout changes, and it keeps its green when the shell tints the icon to Home Assistant's
 * theme. Follows [UpdateNotice] for as long as the button is attached to a window.
 */
object UpdateDot {

    private const val DOT_DP = 10f
    private const val RING_DP = 2f
    private const val INSET_DP = 4f

    fun attach(button: View) {
        val res = button.resources
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, res.displayMetrics)
        val ring = dp(RING_DP)
        val size = (dp(DOT_DP) + 2 * ring).toInt()
        val inset = dp(INSET_DP).toInt()
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(button.context, R.color.accent_fallback))
            setStroke(ring.toInt(), ContextCompat.getColor(button.context, R.color.bg_base))
        }
        val baseDescription = button.contentDescription
        var shown = false

        fun place() {
            val right = button.width - inset
            dot.setBounds(right - size, inset, right, inset + size)
        }

        val sync: () -> Unit = {
            val want = UpdateNotice.dotVisible()
            if (want != shown) {
                shown = want
                if (want) {
                    place()
                    button.overlay.add(dot)
                } else {
                    button.overlay.remove(dot)
                }
                button.contentDescription =
                    if (want) "$baseDescription, update available" else baseDescription
            }
        }

        button.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (shown) place() }
        button.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                UpdateNotice.addListener(sync)
                sync()
            }

            override fun onViewDetachedFromWindow(v: View) {
                UpdateNotice.removeListener(sync)
            }
        })
        if (button.isAttachedToWindow) {
            UpdateNotice.addListener(sync)
            sync()
        }
    }
}
