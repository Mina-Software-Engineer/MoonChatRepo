package com.mina.moonchat.utils

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

sealed class ChatDateLabel {
    object Today : ChatDateLabel()
    object Yesterday : ChatDateLabel()
    data class Absolute(val value: String) : ChatDateLabel()
}

internal object ChatDateSeparator {
    fun localDayKey(date: Date, timeZone: TimeZone, locale: Locale = Locale.getDefault()): String {
        val calendar = calendarFor(date, timeZone, locale)
        return "${calendar.get(Calendar.ERA)}-${calendar.get(Calendar.YEAR)}-${calendar.get(Calendar.DAY_OF_YEAR)}@${timeZone.id}"
    }

    fun isSameLocalDay(first: Date, second: Date, timeZone: TimeZone, locale: Locale = Locale.getDefault()): Boolean {
        val a = calendarFor(first, timeZone, locale)
        val b = calendarFor(second, timeZone, locale)
        return a.get(Calendar.ERA) == b.get(Calendar.ERA) &&
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }

    fun label(
        date: Date,
        now: Date,
        timeZone: TimeZone,
        locale: Locale = Locale.getDefault()
    ): ChatDateLabel {
        val messageDay = calendarFor(date, timeZone, locale)
        val today = calendarFor(now, timeZone, locale)
        if (sameCalendarDay(messageDay, today)) return ChatDateLabel.Today

        val yesterday = (today.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -1)
        }
        if (sameCalendarDay(messageDay, yesterday)) return ChatDateLabel.Yesterday

        val formatter = SimpleDateFormat("MMM d, yyyy", locale).apply {
            this.timeZone = timeZone
        }
        return ChatDateLabel.Absolute(formatter.format(date))
    }

    private fun calendarFor(date: Date, timeZone: TimeZone, locale: Locale): Calendar =
        Calendar.getInstance(timeZone, locale).apply { time = date }

    private fun sameCalendarDay(first: Calendar, second: Calendar): Boolean =
        first.get(Calendar.ERA) == second.get(Calendar.ERA) &&
            first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
            first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
}
