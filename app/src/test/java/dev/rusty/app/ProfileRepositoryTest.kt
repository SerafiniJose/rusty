package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileRepositoryTest {
    @Test
    fun parsesDisplayNameAndAvatar() {
        val json = """
            {"display_name":"Jose Serafini","images":[
              {"url":"https://i.scdn.co/image/small","height":64,"width":64},
              {"url":"https://i.scdn.co/image/big","height":300,"width":300}]}
        """.trimIndent()
        val profile = ProfileRepository.parse(json)
        assertEquals("Jose Serafini", profile?.displayName)
        assertEquals("https://i.scdn.co/image/small", profile?.avatarUrl)
    }

    @Test
    fun missingDisplayNameAndImagesYieldNulls() {
        val profile = ProfileRepository.parse("""{"id":"31abc"}""")
        assertNull(profile?.displayName)
        assertNull(profile?.avatarUrl)
    }

    @Test
    fun malformedJsonReturnsNull() {
        assertNull(ProfileRepository.parse("not json"))
    }
}
