package com.arponzxc.novaai.util

import java.util.Calendar

object TimeGreetingHelper {

    fun greetingForHour(hourOfDay: Int): String {
        val h = hourOfDay.coerceIn(0, 23)
        return when (h) {
            in 5..11 -> "Good Morning"
            in 12..16 -> "Good Afternoon"
            in 17..20 -> "Good Evening"
            else -> "Good Night"
        }
    }

    fun formatGreeting(hourOfDay: Int, userName: String): String {
        val cleanName = userName.trim().ifBlank { "Arpon Gang" }
        return "${greetingForHour(hourOfDay)}, $cleanName"
    }

    fun currentGreeting(userName: String): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return formatGreeting(hour, userName)
    }
}
