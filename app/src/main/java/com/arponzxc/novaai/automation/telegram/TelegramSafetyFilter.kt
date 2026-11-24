package com.arponzxc.novaai.automation.telegram

import java.util.regex.Pattern

/**
 * Keeps credentials / financial data out of the AI pipeline and out of outgoing replies.
 * Incoming messages that look sensitive are NOT answered at all (no auto-reply, no AI call).
 */
object TelegramSafetyFilter {

    private val SENSITIVE_KEYWORDS = listOf(
        "otp", "one time password", "one-time password", "verification code", "login code",
        "password", "passcode", "pin code", "atm pin", "cvv", "cvc", "netbanking",
        "secret key", "private key", "seed phrase", "recovery phrase", "api key", "auth token",
        "credit card", "debit card", "card number", "bank account", "account number",
        "nid number", "social security",
        "ওটিপি", "পাসওয়ার্ড", "পিন কোড", "পিন নম্বর", "সিভিভি", "ভেরিফিকেশন কোড",
        "কার্ড নম্বর", "একাউন্ট নম্বর", "অ্যাকাউন্ট নম্বর"
    )

    private val SENSITIVE_PATTERNS: List<Pattern> = listOf(
        Pattern.compile("(?i)(otp|code|pin|verification|passcode)\\s*(is|:|-)?\\s*\\d{4,8}"),
        Pattern.compile("(?i)(my\\s+password\\s+is|pass\\s*:\\s*\\S+)"),
        // 13-19 digit card-like numbers, optionally separated by spaces or hyphens
        Pattern.compile("\\b(?:\\d[ -]?){15,19}\\b"),
        // IBAN-like
        Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z0-9]{11,30}\\b")
    )

    data class FilterResult(val isSafe: Boolean, val reason: String? = null)

    fun inspectIncomingMessage(message: String): FilterResult {
        val lower = message.lowercase()
        for (pattern in SENSITIVE_PATTERNS) {
            if (pattern.matcher(message).find()) {
                return FilterResult(false, "Message matches a verification-code / card / credential pattern.")
            }
        }
        for (keyword in SENSITIVE_KEYWORDS) {
            if (containsKeyword(lower, keyword)) {
                return FilterResult(false, "Message references sensitive credentials or financial data.")
            }
        }
        return FilterResult(true)
    }

    /** Short ASCII keywords are matched on word boundaries so "otp" does not match "hotpot". */
    private fun containsKeyword(lowerText: String, keyword: String): Boolean {
        if (keyword.length > 4 || keyword.any { it.code > 127 } || keyword.contains(' ')) {
            return lowerText.contains(keyword)
        }
        return Regex("(^|[^a-z0-9])${Regex.escape(keyword)}([^a-z0-9]|$)").containsMatchIn(lowerText)
    }

    /** Removes anything that should never reach the Gemini API. */
    fun redactForAi(text: String): String {
        var clean = text
        clean = clean.replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[email]")
        clean = clean.replace(Regex("\\+?\\d[\\d\\s().-]{6,}\\d"), "[number]")
        clean = clean.replace(Regex("AIzaSy[A-Za-z0-9_-]{20,}"), "[key]")
        return clean.take(600)
    }

    fun sanitizeOutgoingReply(reply: String, maxLength: Int = 500): String {
        var clean = reply
        clean = clean.replace(Regex("AIzaSy[A-Za-z0-9_-]{20,}"), "[REDACTED_API_KEY]")
        clean = clean.replace(Regex("(?i)(password|pin|otp|passcode)\\s*[:=]\\s*\\S+"), "[REDACTED]")
        clean = clean.replace(Regex("\\b(?:\\d[ -]?){15,19}\\b"), "[REDACTED]")
        if (clean.length > maxLength) clean = clean.take(maxLength - 3) + "..."
        return clean.trim()
    }
}
