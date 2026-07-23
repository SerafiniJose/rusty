package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SlideshowSaveModelTest {

    private fun probe(label: String, ok: Boolean) = ImmichProbe(label, ok)
    private val allOk = listOf(probe("Photos", true), probe("Albums", true))

    @Test fun signedInWhenIdentityReadable() {
        val user = ImmichResult.Ok(ImmichUser("u1", "Jose", "jose@mail.com"))
        assertEquals(
            SlideshowSaveResult.SignedIn("Jose", emptyList()),
            SlideshowSaveModel.of(user, allOk),
        )
    }

    @Test fun signedInFallsBackToEmailWhenNameBlank() {
        val user = ImmichResult.Ok(ImmichUser("u1", "", "jose@mail.com"))
        assertEquals(
            SlideshowSaveResult.SignedIn("jose@mail.com", emptyList()),
            SlideshowSaveModel.of(user, allOk),
        )
    }

    @Test fun signedInReportsUnavailableCapabilities() {
        val user = ImmichResult.Ok(ImmichUser("u1", "Jose", "jose@mail.com"))
        val probes = listOf(probe("Photos", true), probe("Tags", false))
        assertEquals(
            SlideshowSaveResult.SignedIn("Jose", listOf("Tags")),
            SlideshowSaveModel.of(user, probes),
        )
    }

    @Test fun blankNameAndEmailIsSavedNoIdentity() {
        val user = ImmichResult.Ok(ImmichUser("u1", "", ""))
        assertEquals(
            SlideshowSaveResult.SavedNoIdentity(emptyList()),
            SlideshowSaveModel.of(user, allOk),
        )
    }

    @Test fun scopedKey403ButCapabilitiesWorkIsSavedNoIdentity() {
        // The real bug: a limited API key 403s on /api/users/me (AUTH) yet reads photos/albums.
        val user = ImmichResult.Error(ImmichErrorKind.AUTH)
        val probes = listOf(probe("Photos", true), probe("Albums", true), probe("Tags", false))
        assertEquals(
            SlideshowSaveResult.SavedNoIdentity(listOf("Tags")),
            SlideshowSaveModel.of(user, probes),
        )
    }

    @Test fun authErrorWithNoWorkingCapabilityIsInvalidKey() {
        val user = ImmichResult.Error(ImmichErrorKind.AUTH)
        val probes = listOf(probe("Photos", false), probe("Albums", false))
        assertEquals(SlideshowSaveResult.InvalidKey, SlideshowSaveModel.of(user, probes))
    }

    @Test fun unreachableIsUnreachableRegardlessOfProbes() {
        val user = ImmichResult.Error(ImmichErrorKind.UNREACHABLE)
        // Caller passes empty probes on the unreachable path; even a stray ok probe stays Unreachable.
        assertEquals(SlideshowSaveResult.Unreachable, SlideshowSaveModel.of(user, emptyList()))
        assertEquals(SlideshowSaveResult.Unreachable, SlideshowSaveModel.of(user, allOk))
    }
}
