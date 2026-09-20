package dev.rusty.app

import kotlin.math.max

/**
 * Where the picture sits: scaled by [scale] about the viewport centre, then shifted by [tx] / [ty]
 * device pixels. [Zoom.NONE] is the untouched, fitted picture.
 */
data class Zoom(val scale: Float, val tx: Float, val ty: Float) {
    val isZoomed: Boolean get() = scale > CameraZoom.MIN_SCALE

    companion object {
        val NONE = Zoom(1f, 0f, 0f)
    }
}

/**
 * The picture rect ([contentW] x [contentH] — the letterboxed video, not the whole view) centred in
 * the [viewW] x [viewH] viewport it is drawn into.
 */
data class ZoomFrame(val contentW: Int, val contentH: Int, val viewW: Int, val viewH: Int) {
    val usable: Boolean get() = contentW > 0 && contentH > 0 && viewW > 0 && viewH > 0
}

/**
 * Pure pan-and-zoom geometry for the live camera view. The fragment owns a single [Zoom] and hands
 * gestures here; nothing in this file touches Android, so the whole model is unit-tested.
 *
 * The one invariant: an edge of the picture may never come inside the viewport. Panning is
 * therefore bounded by the overhang — (picture * scale - viewport) / 2 on each axis, and zero when
 * the picture is still the smaller of the two, which is why a letterboxed stream will not pan
 * vertically until it has been zoomed past the letterbox.
 */
object CameraZoom {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 4f

    /** A pinch by [factor] about the point the fingers are centred on, which stays put under them. */
    fun pinch(current: Zoom, factor: Float, focusX: Float, focusY: Float, frame: ZoomFrame): Zoom {
        if (!frame.usable) return Zoom.NONE
        val target = (current.scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        return clamp(scaledAbout(current, target, focusX, focusY, frame), frame)
    }

    /** A drag of [dx] / [dy], bounded so no edge of the picture is pulled into the viewport. */
    fun pan(current: Zoom, dx: Float, dy: Float, frame: ZoomFrame): Zoom {
        if (!frame.usable) return Zoom.NONE
        // Nothing to pan while the picture has no overhang on either axis — return the zoom
        // unchanged (identity included) rather than manufacture a fresh, identical one.
        if (boundX(current.scale, frame) <= 0f && boundY(current.scale, frame) <= 0f) {
            return clamp(current, frame)
        }
        return clamp(current.copy(tx = current.tx + dx, ty = current.ty + dy), frame)
    }

    /**
     * Forces [zoom] back inside the limits [frame] allows — also the hook for a frame that changed
     * under a live zoom (a rotation, or a stream whose resolution differs). Returns the very same
     * instance when it was already legal, so an unchanged zoom costs no allocation.
     */
    fun clamp(zoom: Zoom, frame: ZoomFrame): Zoom {
        if (!frame.usable) return Zoom.NONE
        val scale = zoom.scale.coerceIn(MIN_SCALE, MAX_SCALE)
        val bx = boundX(scale, frame)
        val by = boundY(scale, frame)
        val tx = zoom.tx.coerceIn(-bx, bx)
        val ty = zoom.ty.coerceIn(-by, by)
        return if (scale == zoom.scale && tx == zoom.tx && ty == zoom.ty) zoom else Zoom(scale, tx, ty)
    }

    /**
     * Re-scales to [target] about ([focusX], [focusY]) in viewport coordinates. Scaling happens
     * about the viewport centre, so holding a point still means moving the translation by the same
     * ratio the scale moved: t' = d - (d - t) * (target / current).
     */
    private fun scaledAbout(current: Zoom, target: Float, focusX: Float, focusY: Float, frame: ZoomFrame): Zoom {
        val ratio = target / current.scale
        val dx = focusX - frame.viewW / 2f
        val dy = focusY - frame.viewH / 2f
        return Zoom(target, dx - (dx - current.tx) * ratio, dy - (dy - current.ty) * ratio)
    }

    /** Half the picture's horizontal overhang past the viewport at [scale]; 0 when it still fits. */
    private fun boundX(scale: Float, frame: ZoomFrame): Float =
        max(0f, (frame.contentW * scale - frame.viewW) / 2f)

    private fun boundY(scale: Float, frame: ZoomFrame): Float =
        max(0f, (frame.contentH * scale - frame.viewH) / 2f)
}
