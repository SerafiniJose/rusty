package dev.rusty.app

import org.junit.Assert.*
import org.junit.Test

class ControlFiltersTest {
    private val u1 = "11111111-2222-3333-4444-555555555555"
    private val u2 = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    @Test fun parsesValidBody() {
        val f = ControlFilters.parse("""{"albumIds":["$u1"],"personIds":[],"tagIds":["$u2"]}""")!!
        assertEquals(listOf(u1), f.albumIds); assertEquals(listOf(u2), f.tagIds)
    }
    @Test fun missingCategoryMeansEmpty() {
        assertEquals(emptyList<String>(), ControlFilters.parse("""{"albumIds":["$u1"]}""")!!.personIds)
    }
    @Test fun rejectsNonUuid() { assertNull(ControlFilters.parse("""{"albumIds":["not-a-uuid"]}""")) }
    @Test fun rejectsCommaSmuggling() { assertNull(ControlFilters.parse("""{"albumIds":["$u1,$u2"]}""")) }
    @Test fun trimsAndDedupes() {
        assertEquals(listOf(u1), ControlFilters.parse("""{"albumIds":[" $u1 ","$u1"]}""")!!.albumIds)
    }
    @Test fun rejectsOversizedCategory() {
        val many = (0 until 501).joinToString(",") { "\"%08x-2222-3333-4444-555555555555\"".format(it) }
        assertNull(ControlFilters.parse("""{"albumIds":[$many]}"""))
    }
    @Test fun rejectsMalformedJson() { assertNull(ControlFilters.parse("{")) }
}
