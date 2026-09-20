package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Geometry model: the picture is [ZoomFrame.contentW] x [ZoomFrame.contentH] (the letterboxed
 * video rect) centred in a [ZoomFrame.viewW] x [ZoomFrame.viewH] viewport, scaled about the
 * viewport centre and then translated. A pan may never pull an edge of the picture inside the
 * viewport, so the translation bound on each axis is (content * scale - view) / 2, floored at 0.
 */
class CameraZoomTest {
    /** Square picture filling a square viewport — no letterboxing to reason about. */
    private val square = ZoomFrame(contentW = 1000, contentH = 1000, viewW = 1000, viewH = 1000)

    /** 16:9 picture letterboxed into a 16:10 viewport: 1000x563 inside 1000x625. */
    private val letterboxed = ZoomFrame(contentW = 1000, contentH = 563, viewW = 1000, viewH = 625)

    @Test
    fun `no zoom is unit scale, centred`() {
        assertEquals(1f, Zoom.NONE.scale, 0.001f)
        assertEquals(0f, Zoom.NONE.tx, 0.001f)
        assertEquals(0f, Zoom.NONE.ty, 0.001f)
    }

    @Test
    fun `pinching out multiplies the scale`() {
        val z = CameraZoom.pinch(Zoom.NONE, factor = 2f, focusX = 500f, focusY = 500f, frame = square)
        assertEquals(2f, z.scale, 0.001f)
    }

    @Test
    fun `pinching out stops at the maximum scale`() {
        val z = CameraZoom.pinch(Zoom.NONE, factor = 99f, focusX = 500f, focusY = 500f, frame = square)
        assertEquals(CameraZoom.MAX_SCALE, z.scale, 0.001f)
    }

    @Test
    fun `pinching in never goes below fit`() {
        val z = CameraZoom.pinch(Zoom.NONE, factor = 0.1f, focusX = 500f, focusY = 500f, frame = square)
        assertEquals(CameraZoom.MIN_SCALE, z.scale, 0.001f)
        assertEquals(0f, z.tx, 0.001f)
        assertEquals(0f, z.ty, 0.001f)
    }

    @Test
    fun `pinching about the centre keeps the picture centred`() {
        val z = CameraZoom.pinch(Zoom.NONE, factor = 2f, focusX = 500f, focusY = 500f, frame = square)
        assertEquals(0f, z.tx, 0.001f)
        assertEquals(0f, z.ty, 0.001f)
    }

    @Test
    fun `pinching about a point keeps that point under the fingers`() {
        val focusX = 700f
        val focusY = 400f
        val z = CameraZoom.pinch(Zoom.NONE, factor = 2f, focusX = focusX, focusY = focusY, frame = square)
        // Where the picture point that was under the fingers has moved to.
        assertEquals(focusX, screenX(z, pictureXAt(Zoom.NONE, focusX, square), square), 0.001f)
        assertEquals(focusY, screenY(z, pictureYAt(Zoom.NONE, focusY, square), square), 0.001f)
    }

    @Test
    fun `pinching in from a corner re-centres as it reaches fit`() {
        val zoomed = CameraZoom.pan(CameraZoom.pinch(Zoom.NONE, 2f, 500f, 500f, square), 400f, 400f, square)
        val out = CameraZoom.pinch(zoomed, factor = 0.25f, focusX = 0f, focusY = 0f, frame = square)
        assertEquals(CameraZoom.MIN_SCALE, out.scale, 0.001f)
        assertEquals(0f, out.tx, 0.001f)
        assertEquals(0f, out.ty, 0.001f)
    }

    @Test
    fun `panning does nothing at fit`() {
        val z = CameraZoom.pan(Zoom.NONE, dx = 200f, dy = 200f, frame = square)
        assertSame(Zoom.NONE, z)
    }

    @Test
    fun `panning moves the picture by the drag`() {
        val zoomed = CameraZoom.pinch(Zoom.NONE, 2f, 500f, 500f, square)
        val z = CameraZoom.pan(zoomed, dx = 100f, dy = -50f, frame = square)
        assertEquals(100f, z.tx, 0.001f)
        assertEquals(-50f, z.ty, 0.001f)
    }

    @Test
    fun `panning stops before an edge of the picture enters the viewport`() {
        val zoomed = CameraZoom.pinch(Zoom.NONE, 2f, 500f, 500f, square)
        // At 2x the picture is 2000 wide in a 1000 viewport, so 500 px of slack each way.
        val z = CameraZoom.pan(zoomed, dx = 9999f, dy = -9999f, frame = square)
        assertEquals(500f, z.tx, 0.001f)
        assertEquals(-500f, z.ty, 0.001f)
    }

    @Test
    fun `a letterboxed picture has no vertical slack until it outgrows the viewport`() {
        // 563 * 1.1 = 619 < 625, so the picture is still shorter than the viewport: no pan.
        val slight = CameraZoom.pinch(Zoom.NONE, 1.1f, 500f, 312f, letterboxed)
        assertEquals(0f, CameraZoom.pan(slight, 0f, 9999f, letterboxed).ty, 0.001f)
        // 563 * 2 = 1126, i.e. 501 px taller than the viewport: 250.5 px of slack each way.
        val full = CameraZoom.pinch(Zoom.NONE, 2f, 500f, 312f, letterboxed)
        assertEquals(250.5f, CameraZoom.pan(full, 0f, 9999f, letterboxed).ty, 0.001f)
    }

    @Test
    fun `a letterboxed picture still pans horizontally`() {
        val full = CameraZoom.pinch(Zoom.NONE, 2f, 500f, 312f, letterboxed)
        assertEquals(500f, CameraZoom.pan(full, 9999f, 0f, letterboxed).tx, 0.001f)
    }

    @Test
    fun `a frame with no size cannot be zoomed`() {
        val empty = ZoomFrame(0, 0, 0, 0)
        assertSame(Zoom.NONE, CameraZoom.pinch(Zoom.NONE, 2f, 0f, 0f, empty))
        assertSame(Zoom.NONE, CameraZoom.pan(Zoom.NONE, 10f, 10f, empty))
    }

    @Test
    fun `a zoom is re-clamped when the frame shrinks under it`() {
        val wide = ZoomFrame(1000, 1000, 1000, 1000)
        val zoomed = CameraZoom.pan(CameraZoom.pinch(Zoom.NONE, 2f, 500f, 500f, wide), 500f, 0f, wide)
        assertEquals(500f, zoomed.tx, 0.001f)
        // Same 2x zoom in a viewport as large as the picture now is: no slack left at all.
        val tight = ZoomFrame(1000, 1000, 2000, 2000)
        assertEquals(0f, CameraZoom.clamp(zoomed, tight).tx, 0.001f)
    }

    // ---- Model helpers: where a picture point lands on screen, and the inverse ------------------

    private fun pictureXAt(z: Zoom, screenX: Float, f: ZoomFrame): Float {
        val cx = f.viewW / 2f
        return cx + (screenX - cx - z.tx) / z.scale
    }

    private fun pictureYAt(z: Zoom, screenY: Float, f: ZoomFrame): Float {
        val cy = f.viewH / 2f
        return cy + (screenY - cy - z.ty) / z.scale
    }

    private fun screenX(z: Zoom, pictureX: Float, f: ZoomFrame): Float {
        val cx = f.viewW / 2f
        return cx + (pictureX - cx) * z.scale + z.tx
    }

    private fun screenY(z: Zoom, pictureY: Float, f: ZoomFrame): Float {
        val cy = f.viewH / 2f
        return cy + (pictureY - cy) * z.scale + z.ty
    }
}
