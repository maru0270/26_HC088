package com.bikeeyes.app.ui.screens.mypage

import com.bikeeyes.app.AdminAccidentRecord
import com.bikeeyes.app.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminAccidentPagerTest {
    private fun event(id: String, time: Long = 1L, status: String = "ACCIDENT", safe: Boolean = false) =
        AdminAccidentRecord(id, time, 35.0, 128.0, 5.0, 3.0, 25.0, 2.0, status, safe)

    @Test
    fun `navigation targets admin accident detail and carries ride and response filter`() {
        assertEquals("admin_accident_detail/ride-a/ALL", Screen.AdminAccidentDetail.createRoute("ride-a"))
        assertEquals("admin_accident_detail/ride-b/SAFE", Screen.AdminAccidentDetail.createRoute("ride-b", "SAFE"))
        assertEquals(
            "admin_accident_detail/ride-c/ALL?eventUuid=event-3",
            Screen.AdminAccidentDetail.createRoute("ride-c", "ALL", "event-3")
        )
        assertFalse(Screen.AdminAccidentDetail.route == Screen.AdminRideDetail.route)
        assertFalse(Screen.AdminAccidentDetail.route == Screen.AccidentDetail.route)
    }

    @Test
    fun `events are newest first and navigation is bounded`() {
        val pager = AdminAccidentPager.create(listOf(event("old", 1), event("new", 2)), AdminAccidentFilter.ALL)
        assertEquals("new", pager.current?.event_uuid)
        assertEquals(pager, pager.previous())
        val next = pager.next()
        assertEquals("old", next.current?.event_uuid)
        assertEquals(1, next.index)
        assertEquals(next, next.next())
        assertEquals(pager, next.previous())
    }

    @Test
    fun `requested accident is selected when opening detail`() {
        val pager = AdminAccidentPager.create(
            listOf(event("old", 1), event("new", 2)),
            AdminAccidentFilter.ALL,
            initialEventUuid = "old"
        )
        assertEquals("old", pager.current?.event_uuid)
        assertEquals(1, pager.index)
    }

    @Test
    fun `safe and no response are included in all and separated by filter`() {
        val events = listOf(event("confirmed"), event("safe", status = "SAFE"), event("timeout", status = "NO_RESPONSE"))
        assertEquals(3, AdminAccidentPager.create(events, AdminAccidentFilter.ALL).events.size)
        assertEquals("safe", AdminAccidentPager.create(events, AdminAccidentFilter.SAFE).current?.event_uuid)
        assertEquals("timeout", AdminAccidentPager.create(events, AdminAccidentFilter.NO_RESPONSE).current?.event_uuid)
        assertEquals("confirmed", AdminAccidentPager.create(events, AdminAccidentFilter.ACCIDENT).current?.event_uuid)
    }

    @Test
    fun `false positive flag overrides legacy confirmed or timeout status`() {
        val events = listOf(event("a", status = "CONFIRMED", safe = true), event("b", status = "TIMEOUT", safe = true))
        assertTrue(AdminAccidentPager.create(events, AdminAccidentFilter.ACCIDENT).events.isEmpty())
        assertTrue(AdminAccidentPager.create(events, AdminAccidentFilter.NO_RESPONSE).events.isEmpty())
        assertEquals(2, AdminAccidentPager.create(events, AdminAccidentFilter.SAFE).events.size)
        assertTrue(events.all { it.isSafeResponse() })
    }

    @Test
    fun `legacy and lower case responses have consistent filtering and labels`() {
        assertEquals("괜찮아요 · 오탐", event("a", status = "cancelled").responseLabel())
        assertEquals("사고입니다", event("b", status = "emergency").responseLabel())
        assertEquals("30초 무응답", event("c", status = "unresponded").responseLabel())
        assertFalse(event("c", status = "unresponded").isSafeResponse())
        assertEquals("UNKNOWN", event("d", status = "UNKNOWN").responseLabel())
    }

    @Test
    fun `empty or unmatched list does not show an unrelated event`() {
        val empty = AdminAccidentPager.create(emptyList(), AdminAccidentFilter.ALL)
        assertNull(empty.current)
        assertFalse(empty.hasPrevious)
        assertFalse(empty.hasNext)
        assertEquals(empty, empty.next().previous())
        assertNull(AdminAccidentPager.create(listOf(event("a")), AdminAccidentFilter.SAFE).current)
    }

    @Test
    fun `new ride or filter starts from first event`() {
        val previous = AdminAccidentPager.create(listOf(event("a", 1), event("b", 2)), AdminAccidentFilter.ALL).next()
        assertEquals(1, previous.index)
        val nextRide = AdminAccidentPager.create(listOf(event("c", 3)), AdminAccidentFilter.ALL)
        assertEquals(0, nextRide.index)
        assertEquals("c", nextRide.current?.event_uuid)
        val newFilter = AdminAccidentPager.create(previous.events, AdminAccidentFilter.ACCIDENT)
        assertEquals(0, newFilter.index)
        assertEquals("b", newFilter.current?.event_uuid)
    }

    @Test
    fun `invalid filter defaults to all`() {
        assertEquals(AdminAccidentFilter.ALL, AdminAccidentFilter.fromRoute("unknown"))
        assertEquals(AdminAccidentFilter.SAFE, AdminAccidentFilter.fromRoute("safe"))
    }
}
