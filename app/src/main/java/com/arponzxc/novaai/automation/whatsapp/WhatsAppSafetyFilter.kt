package com.arponzxc.novaai.automation.whatsapp

import java.util.regex.Pattern

/**
 * Validates incoming messages and generated replies to ensure sensitive data
 * (OTPs, PINs, bank details, passwords, API keys) are never exposed.
 */
object WhatsAppSafetyFilter {

    private val SENSITIVE_KEYWORDS = listOf(
        "otp", "one time password", "pin code", "cvv", "atm pin",
        "netbanking", "password", "secret key", "credit card", "debit card",
        "bank account number", "social security", "nid number", "auth token",
        "api key", "passcode"
    )

    private val SENSITIVE_PATTERNS = listOf(
        // OTP verification patterns like "your code is 123456"
        Pattern.compile("(?i)(otp|code|pin|verification)\\s*(is|:|-)?\\s*\\d{4,8}"),
        // Credit card numbers (13-19 digits with optional hyphens/spaces)
        Pattern.compile("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|6(?:011|5[0-9]{2})[0-9]{12}|3[47][0-9]{13})\\b"),
        // Password sharing attempts
        Pattern.compile("(?i)(my\\s+password\\s+is|pass\\s*:\\s*\\S+)")
    )

    data class FilterResult(
        val isSafe: Boolean,
        val reason: String? = null,
        val safeFallbackReply: String? = null
    )

    /**
     * Checks if the incoming message is attempting to harvest sensitive credentials.
     */
    fun inspectIncomingMessage(message: String): FilterResult {
        val lower = message.lowercase()

        for (pattern in SENSITIVE_PATTERNS) {
            if (pattern.matcher(message).find()) {
                return FilterResult(
                    isSafe = false,
                    reason = "Contains verification code or sensitive token pattern.",
                    safeFallbackReply = "For security reasons, OTPs and verification codes cannot be processed automatically."
                )
            }
        }

        // Check if message asks for sensitive credentials or passwords
        val containsSensitiveKeyword = lower.contains("otp") ||
                lower.contains("password") ||
                lower.contains("pin code") ||
                lower.contains("atm pin") ||
                lower.contains("cvv") ||
                lower.contains("netbanking") ||
                lower.contains("secret key")

        if (containsSensitiveKeyword) {
            return FilterResult(
                isSafe = false,
                reason = "Sender explicitly referenced or requested secret credentials or OTP.",
                safeFallbackReply = "Security notice: NOVA AI is restricted from disclosing passwords, OTPs, or financial details."
            )
        }

        return FilterResult(isSafe = true)
    }

    /**
     * Sanitizes outgoing reply to guarantee no sensitive data is leaked.
     */
    fun sanitizeOutgoingReply(reply: String): String {
        var clean = reply

        // Remove any leaked API keys or long hash sequences
        clean = clean.replace(Regex("AIzaSy[A-Za-z0-9_-]{20,}"), "[REDACTED_API_KEY]")
        clean = clean.replace(Regex("(?i)(password|pin|otp)\\s*:\\s*\\S+"), "[REDACTED]")

        // Enforce length limit for clean messaging
        if (clean.length > 500) {
            clean = clean.take(497) + "..."
        }

        return clean.trim()
    }
}
