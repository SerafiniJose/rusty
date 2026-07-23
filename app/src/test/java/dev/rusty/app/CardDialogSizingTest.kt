package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Popup cards are sized from the display metrics they are handed, so re-running the calculation
 * after a rotation yields the NEW orientation's size. The regression this guards: a card opened in
 * landscape kept its landscape pixel width in portrait and overflowed the screen.
 */
class CardDialogSizingTest {

    private val landscapeW = 1920
    private val landscapeH = 1200
    private val portraitW = 1200
    private val portraitH = 1920

    @Test
    fun `width tracks the display it is given`() {
        val (land, _) = cardDialogSizePx(landscapeW, landscapeH)
        val (port, _) = cardDialogSizePx(portraitW, portraitH)
        assertEquals(1382, land)
        assertEquals(864, port)
    }

    @Test
    fun `portrait size never overflows the portrait display`() {
        val (width, _) = cardDialogSizePx(portraitW, portraitH)
        assertTrue("card of $width px must fit in a $portraitW px display", width <= portraitW)
    }

    @Test
    fun `landscape width would overflow a portrait display if left unrecalculated`() {
        // Pins the reason the recalculation exists.
        val (landWidth, _) = cardDialogSizePx(landscapeW, landscapeH)
        assertTrue(landWidth > portraitW)
    }

    @Test
    fun `height is wrap content unless a fraction is requested`() {
        assertEquals(WRAP_CONTENT, cardDialogSizePx(portraitW, portraitH).second)
        assertEquals(960, cardDialogSizePx(portraitW, portraitH, heightFraction = 0.5f).second)
    }

    private companion object {
        /** android.view.WindowManager.LayoutParams.WRAP_CONTENT */
        const val WRAP_CONTENT = -2
    }
}
