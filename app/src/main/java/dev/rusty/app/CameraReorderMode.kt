package dev.rusty.app

import android.app.Activity
import android.content.res.Configuration
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Reorder mode for the Cameras settings list: the rows become [R.layout.view_camera_reorder_row]
 * (number, name, "Top left"-style spot from [CameraOrder.positionLabel], drag handle).
 *
 * Two ways to move a camera, both landing in [onMoved] immediately:
 *  - Touch: drag the handle; the row under the finger becomes the target.
 *  - D-pad: centre on a row lifts it (activated state), ▲/▼ move it one step, centre or Back drops it.
 *
 * A touch drag never rebuilds the list mid-gesture: removing the dragged row from its parent would
 * cancel the touch stream ([android.view.ViewGroup] sends ACTION_CANCEL to a removed touch target),
 * so a step moves the NEIGHBOUR row past the dragged one instead, and the numbers and spot labels
 * are repainted in place. The full rebuild happens on drop.
 */
internal class CameraReorderMode(
    private val activity: Activity,
    private val container: LinearLayout,
    /** The strip's Done pill: linked into the rows' ▲/▼ chain, since it sits off to the right and
     *  Android's own focus search would otherwise hop from the first row straight to the header. */
    private val doneButton: View,
    private val cameras: () -> List<CameraRecord>,
    private val pageSize: () -> Int?,
    private val onMoved: (List<CameraRecord>) -> Unit,
    private val onModeChanged: (active: Boolean) -> Unit,
) {
    var active: Boolean = false
        private set

    private var liftedId: String? = null
    private var dragPending = false
    private var queuedRawY: Float? = null
    private val rows = ArrayList<View>()

    fun enter() {
        if (active) return
        active = true
        liftedId = null
        onModeChanged(true)
        render()
        focusRow(0)
    }

    fun exit() {
        if (!active) return
        active = false
        liftedId = null
        onModeChanged(false)
    }

    /** Rebuilds the rows from [cameras]. Safe to call while the mode is on and no drag is running. */
    fun render() {
        container.removeAllViews()
        rows.clear()
        val sorted = cameras().sortedBy { it.position }
        sorted.forEachIndexed { index, cam ->
            val row = activity.layoutInflater.inflate(R.layout.view_camera_reorder_row, container, false)
            row.findViewById<TextView>(R.id.tvCamReorderName).text = cam.name
            row.tag = cam.id
            row.setOnClickListener {
                liftedId = if (liftedId == cam.id) null else cam.id
                paintAll()
            }
            row.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN || liftedId != cam.id) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { stepLifted(-1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { stepLifted(+1); true }
                    KeyEvent.KEYCODE_BACK -> { liftedId = null; paintAll(); true }
                    else -> false
                }
            }
            row.findViewById<View>(R.id.btnCamReorderHandle).setOnTouchListener { handle, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // The settings sheet scrolls; a drag must own the gesture from the first move.
                        handle.parent?.requestDisallowInterceptTouchEvent(true)
                        liftedId = cam.id
                        paintAll()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> { dragTo(ev.rawY); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // A position still queued behind a pending hop is the finger's last word:
                        // commit it to the model now; the rebuild below draws from the model.
                        queuedRawY?.let { queuedRawY = null; dragPending = false; dragTo(it) }
                        liftedId = null
                        dragPending = false
                        queuedRawY = null
                        // Deferred: rebuilding the list from inside its own touch dispatch left the
                        // hierarchy with a null child slot (NPE in collectViewAttributes on the Show).
                        container.post { if (active) render() }
                        true
                    }
                    else -> false
                }
            }
            rows.add(row)
            container.addView(row)
        }
        rows.firstOrNull()?.nextFocusUpId = doneButton.id
        doneButton.nextFocusDownId = R.id.rowCamReorderRoot
        paintAll()
    }

    /** Numbers, spot labels and the lifted state, in place — the only paint a drag does. */
    private fun paintAll() {
        val list = cameras().sortedBy { it.position }
        val cols = wallColumns(pageSize() ?: list.size)
        for (row in rows) {
            val index = container.indexOfChild(row)
            row.findViewById<TextView>(R.id.tvCamReorderIndex).text = (index + 1).toString()
            row.findViewById<TextView>(R.id.tvCamReorderSpot).text =
                CameraOrder.positionLabel(index, list.size, pageSize(), cols)
            row.isActivated = row.tag == liftedId
        }
    }

    private fun stepLifted(delta: Int) {
        val id = liftedId ?: return
        val row = rows.firstOrNull { it.tag == id } ?: return
        val from = container.indexOfChild(row)
        val to = (from + delta).coerceIn(0, container.childCount - 1)
        if (to == from) return
        commit(id, to)
        render()
        focusRow(to)
    }

    private fun dragTo(rawY: Float) {
        val id = liftedId ?: return
        val dragged = rows.firstOrNull { it.tag == id } ?: return
        val from = container.indexOfChild(dragged)
        // Slots, not children: after a hop the rows keep their pre-layout screen bounds until the
        // next frame, so "which child is under the finger" answers with the row that just moved
        // and the pair flip-flops. The SET of row rectangles is the same whichever row sits where,
        // so sorting the bounds gives stable slots.
        val loc = IntArray(2)
        val slots = (0 until container.childCount).map { i ->
            val child = container.getChildAt(i)
            child.getLocationOnScreen(loc)
            loc[1] to loc[1] + child.height
        }.sortedBy { it.first }
        val target = slots.indexOfFirst { rawY >= it.first && rawY < it.second }.takeIf { it >= 0 } ?: from
        if (dragPending) { queuedRawY = rawY; return }
        if (target == from) return
        commit(id, target)
        // The view hop runs after this touch event, not inside it (see ACTION_UP): the list is not
        // touched while it is dispatching. One step at a time so each step is a neighbour hop —
        // the neighbour is what gets re-added, never the row under the finger.
        dragPending = true
        container.post {
            dragPending = false
            if (!active || liftedId != id) return@post
            val step = if (target > from) 1 else -1
            var at = from
            while (at != target) {
                val neighbour = container.getChildAt(at + step) ?: break
                container.removeView(neighbour)
                container.addView(neighbour, at)
                at += step
            }
            paintAll()
            // A move that arrived while the hop was pending is replayed, so the finger's last
            // position always wins — dropping it left the list one step short of the drop point.
            queuedRawY?.let { queuedRawY = null; dragTo(it) }
        }
    }

    private fun commit(id: String, index: Int) = onMoved(CameraOrder.moveTo(cameras(), id, index))

    private fun focusRow(index: Int) {
        if (container.isInTouchMode) return
        container.getChildAt(index.coerceIn(0, (container.childCount - 1).coerceAtLeast(0)))?.requestFocus()
    }

    /** The column count the wall would pick for [count] tiles: same GridFit call as
     *  CameraFragment.relayoutGrid, fed the display size minus the shell's pads. Insets are not
     *  known here; immersive mode makes them zero on the devices this runs on. */
    private fun wallColumns(count: Int): Int {
        val res = activity.resources
        // Real size, not displayMetrics: the latter excludes the system bars the wall hides.
        val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") activity.windowManager.defaultDisplay.getRealMetrics(it) }
        val d = m.density
        val landscape = res.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val w = m.widthPixels - (20 * d).toInt()
        val h = m.heightPixels - res.getDimensionPixelSize(R.dimen.shell_clock_clearance) - (10 * d).toInt()
        return GridFit.columns(count, w, h, (12 * d).toInt(), minCols = if (landscape) 2 else 1)
    }
}
