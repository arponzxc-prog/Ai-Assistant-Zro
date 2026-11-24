package com.arponzxc.novaai.automation.telegram

/** Lightweight, offline, deterministic message classification (English + Bengali). */
object TelegramMessageClassifier {

    enum class Category { URGENT, QUESTION, REQUEST, MEETING, GREETING, THANKS, OTHER }

    private val URGENT = listOf("urgent", "emergency", "asap", "immediately", "help me", "জরুরি", "জরুরী", "এখনই", "বিপদ")
    private val MEETING = listOf("meet", "meeting", "call me", "available", "schedule", "মিটিং", "দেখা", "কখন", "কল দাও", "কল করো")
    private val THANKS = listOf("thank", "thanks", "thx", "ধন্যবাদ", "থ্যাঙ্কস", "শুকরিয়া")
    private val GREETING = listOf("hello", "hi", "hey", "good morning", "good evening", "assalamu", "salam", "সালাম", "আসসালামু", "হ্যালো", "হাই", "কেমন আছ", "কেমন আছেন")
    private val REQUEST = listOf("please", "can you", "could you", "send me", "need", "দয়া করে", "প্লিজ", "পাঠাও", "দরকার", "লাগবে")

    fun classify(text: String): Category {
        val lower = text.lowercase().trim()
        if (lower.isBlank()) return Category.OTHER
        return when {
            hasAny(lower, URGENT) -> Category.URGENT
            hasAny(lower, MEETING) -> Category.MEETING
            lower.endsWith("?") || lower.endsWith("？") || lower.contains("কি ") || lower.contains("কেন") ||
                lower.contains("কোথায়") || lower.startsWith("what") || lower.startsWith("where") ||
                lower.startsWith("when") || lower.startsWith("why") || lower.startsWith("how") -> Category.QUESTION
            hasAny(lower, REQUEST) -> Category.REQUEST
            hasAny(lower, THANKS) -> Category.THANKS
            hasAny(lower, GREETING) -> Category.GREETING
            else -> Category.OTHER
        }
    }

    private fun hasAny(lower: String, words: List<String>): Boolean = words.any { w ->
        if (w.all { it.code < 128 } && w.length <= 4) {
            Regex("(^|[^a-z])${Regex.escape(w)}([^a-z]|$)").containsMatchIn(lower)
        } else lower.contains(w)
    }

    fun isBengali(text: String): Boolean = text.any { it in '\u0980'..'\u09FF' }
}
