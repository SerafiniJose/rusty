package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for [CameraSettingsModel] — no Robolectric, no mocking. */
class CameraSettingsModelTest {

    private fun edit(
        id: String? = null,
        name: String = "Front door",
        rtspUrl: String = "rtsp://192.168.1.50/stream1",
        snapshotUrl: String? = null,
        username: String? = null,
        password: String? = null,
        audioEnabled: Boolean = false,
        forceTcp: Boolean = true,
    ) = CameraEdit(id, name, rtspUrl, snapshotUrl, username, password, audioEnabled, forceTcp)

    // ---- add ------------------------------------------------------------------------------

    @Test
    fun `add assigns a fresh cam_ id with 8 hex chars`() {
        val result = CameraSettingsModel.applyEdit(emptyList(), edit())
        val added = result.cameras.single()
        assertTrue(added.id.startsWith("cam_"))
        assertEquals(12, added.id.length) // "cam_" (4) + 8 hex chars
        assertTrue(added.id.removePrefix("cam_").all { it in "0123456789abcdef" })
    }

    @Test
    fun `add assigns a fresh id distinct from an existing camera`() {
        val existing = listOf(sampleRecord(id = "cam_11111111", position = 0))
        val result = CameraSettingsModel.applyEdit(existing, edit(name = "Back yard"))
        val added = result.cameras.first { it.id != "cam_11111111" }
        assertTrue(added.id != "cam_11111111")
    }

    @Test
    fun `add appends at the last position`() {
        val existing = listOf(
            sampleRecord(id = "cam_11111111", position = 0),
            sampleRecord(id = "cam_22222222", position = 1),
        )
        val result = CameraSettingsModel.applyEdit(existing, edit(name = "Third camera"))
        assertEquals(3, result.cameras.size)
        val added = result.cameras.first { it.name == "Third camera" }
        assertEquals(2, added.position)
    }

    @Test
    fun `add on an empty list starts at position 0`() {
        val result = CameraSettingsModel.applyEdit(emptyList(), edit())
        assertEquals(0, result.cameras.single().position)
    }

    // ---- edit -------------------------------------------------------------------------------

    @Test
    fun `edit preserves the existing id and position`() {
        val existing = listOf(
            sampleRecord(id = "cam_aaaaaaaa", position = 0, name = "Old name"),
            sampleRecord(id = "cam_bbbbbbbb", position = 1),
        )
        val result = CameraSettingsModel.applyEdit(
            existing,
            edit(id = "cam_bbbbbbbb", name = "New name", rtspUrl = "rtsp://10.0.0.9/stream"),
        )
        assertEquals(2, result.cameras.size)
        val edited = result.cameras.first { it.id == "cam_bbbbbbbb" }
        assertEquals("New name", edited.name)
        assertEquals("rtsp://10.0.0.9/stream", edited.rtspUrl)
        assertEquals(1, edited.position)
        // The untouched camera is unchanged.
        val untouched = result.cameras.first { it.id == "cam_aaaaaaaa" }
        assertEquals("Old name", untouched.name)
        assertEquals(0, untouched.position)
    }

    @Test
    fun `editing a camera does not change the total count`() {
        val existing = listOf(sampleRecord(id = "cam_aaaaaaaa", position = 0))
        val result = CameraSettingsModel.applyEdit(existing, edit(id = "cam_aaaaaaaa", name = "Renamed"))
        assertEquals(1, result.cameras.size)
    }

    // ---- inline credentials -----------------------------------------------------------------

    @Test
    fun `inline credentials in the rtsp url are split into a clean url plus a credential write`() {
        val result = CameraSettingsModel.applyEdit(
            emptyList(),
            edit(rtspUrl = "rtsp://admin:secret@192.168.1.60/stream1"),
        )
        val added = result.cameras.single()
        assertEquals("rtsp://192.168.1.60/stream1", added.rtspUrl)
        val creds = result.credentials
        assertEquals(added.id, creds?.cameraId)
        assertEquals("admin", creds?.username)
        assertEquals("secret", creds?.password)
    }

    @Test
    fun `inline credentials in the snapshot url are split into a clean url plus a credential write`() {
        val result = CameraSettingsModel.applyEdit(
            emptyList(),
            edit(
                rtspUrl = "rtsp://192.168.1.60/stream1", // no inline creds here
                snapshotUrl = "http://viewer:peek@192.168.1.60/snapshot.jpg",
            ),
        )
        val added = result.cameras.single()
        assertEquals("http://192.168.1.60/snapshot.jpg", added.snapshotUrl)
        val creds = result.credentials
        assertEquals(added.id, creds?.cameraId)
        assertEquals("viewer", creds?.username)
        assertEquals("peek", creds?.password)
    }

    @Test
    fun `a typed username wins over both urls' inline credentials`() {
        val result = CameraSettingsModel.applyEdit(
            emptyList(),
            edit(
                rtspUrl = "rtsp://rtspuser:rtsppass@192.168.1.60/stream1",
                snapshotUrl = "http://snapuser:snappass@192.168.1.60/snapshot.jpg",
                username = "typed-user",
                password = "typed-pass",
            ),
        )
        val creds = result.credentials
        assertEquals("typed-user", creds?.username)
        assertEquals("typed-pass", creds?.password)
    }

    @Test
    fun `with no typed credentials the rtsp url's inline credentials win over the snapshot url's`() {
        val result = CameraSettingsModel.applyEdit(
            emptyList(),
            edit(
                rtspUrl = "rtsp://rtspuser:rtsppass@192.168.1.60/stream1",
                snapshotUrl = "http://snapuser:snappass@192.168.1.60/snapshot.jpg",
            ),
        )
        val creds = result.credentials
        assertEquals("rtspuser", creds?.username)
        assertEquals("rtsppass", creds?.password)
    }

    @Test
    fun `a url with no inline credentials and no typed credentials writes nothing`() {
        val result = CameraSettingsModel.applyEdit(emptyList(), edit(rtspUrl = "rtsp://192.168.1.60/stream1"))
        assertEquals("rtsp://192.168.1.60/stream1", result.cameras.single().rtspUrl)
        assertNull(result.credentials)
    }

    @Test
    fun `typed username and password fields also produce a credential write`() {
        val result = CameraSettingsModel.applyEdit(
            emptyList(),
            edit(rtspUrl = "rtsp://192.168.1.60/stream1", username = "u", password = "p"),
        )
        assertEquals("u", result.credentials?.username)
        assertEquals("p", result.credentials?.password)
    }

    // ---- move -------------------------------------------------------------------------------

    @Test
    fun `move up swaps positions with the previous camera`() {
        val existing = listOf(
            sampleRecord(id = "cam_a", position = 0),
            sampleRecord(id = "cam_b", position = 1),
            sampleRecord(id = "cam_c", position = 2),
        )
        val moved = CameraSettingsModel.move(existing, "cam_b", up = true)
        assertEquals(1, moved.first { it.id == "cam_a" }.position)
        assertEquals(0, moved.first { it.id == "cam_b" }.position)
        assertEquals(2, moved.first { it.id == "cam_c" }.position)
    }

    @Test
    fun `move down swaps positions with the next camera`() {
        val existing = listOf(
            sampleRecord(id = "cam_a", position = 0),
            sampleRecord(id = "cam_b", position = 1),
            sampleRecord(id = "cam_c", position = 2),
        )
        val moved = CameraSettingsModel.move(existing, "cam_b", up = false)
        assertEquals(0, moved.first { it.id == "cam_a" }.position)
        assertEquals(2, moved.first { it.id == "cam_b" }.position)
        assertEquals(1, moved.first { it.id == "cam_c" }.position)
    }

    @Test
    fun `move up on the first camera is a no-op`() {
        val existing = listOf(
            sampleRecord(id = "cam_a", position = 0),
            sampleRecord(id = "cam_b", position = 1),
        )
        val moved = CameraSettingsModel.move(existing, "cam_a", up = true)
        assertEquals(existing, moved)
    }

    @Test
    fun `move down on the last camera is a no-op`() {
        val existing = listOf(
            sampleRecord(id = "cam_a", position = 0),
            sampleRecord(id = "cam_b", position = 1),
        )
        val moved = CameraSettingsModel.move(existing, "cam_b", up = false)
        assertEquals(existing, moved)
    }

    @Test
    fun `move on an unknown id is a no-op`() {
        val existing = listOf(sampleRecord(id = "cam_a", position = 0))
        val moved = CameraSettingsModel.move(existing, "cam_missing", up = true)
        assertEquals(existing, moved)
    }

    // ---- delete -----------------------------------------------------------------------------

    @Test
    fun `delete removes the camera and renumbers positions contiguously`() {
        val existing = listOf(
            sampleRecord(id = "cam_a", position = 0),
            sampleRecord(id = "cam_b", position = 1),
            sampleRecord(id = "cam_c", position = 2),
        )
        val result = CameraSettingsModel.delete(existing, "cam_b")
        assertEquals(listOf("cam_a", "cam_c"), result.map { it.id })
        assertEquals(0, result.first { it.id == "cam_a" }.position)
        assertEquals(1, result.first { it.id == "cam_c" }.position)
    }

    @Test
    fun `delete on an unknown id leaves the list untouched but still renumbered`() {
        val existing = listOf(
            sampleRecord(id = "cam_a", position = 0),
            sampleRecord(id = "cam_b", position = 1),
        )
        val result = CameraSettingsModel.delete(existing, "cam_missing")
        assertEquals(existing, result)
    }

    private fun sampleRecord(
        id: String,
        position: Int,
        name: String = "Camera $id",
    ) = CameraRecord(
        id = id,
        name = name,
        rtspUrl = "rtsp://192.168.1.1/stream",
        snapshotUrl = null,
        audioEnabled = false,
        forceTcp = true,
        position = position,
    )
}
