package com.example.lumen.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class ReminderRulesTest {

    @Test
    fun quietHour_insideWindow() {
        assertTrue(ReminderRules.isQuietHour(LocalTime.of(22, 0)))   // start inclusive
        assertTrue(ReminderRules.isQuietHour(LocalTime.of(23, 30)))
        assertTrue(ReminderRules.isQuietHour(LocalTime.MIDNIGHT))
        assertTrue(ReminderRules.isQuietHour(LocalTime.of(7, 59)))
    }

    @Test
    fun quietHour_outsideWindow() {
        assertFalse(ReminderRules.isQuietHour(LocalTime.of(8, 0)))   // end exclusive
        assertFalse(ReminderRules.isQuietHour(LocalTime.of(12, 0)))
        assertFalse(ReminderRules.isQuietHour(LocalTime.of(21, 59)))
    }

    @Test
    fun reminderText_singularVsPlural() {
        assertEquals("1 new story — catch up on the latest news", ReminderRules.reminderText(1))
        assertEquals("5 new stories — catch up on the latest news", ReminderRules.reminderText(5))
    }
}
