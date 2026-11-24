package com.arponzxc.novaai.automation.whatsapp

import com.arponzxc.novaai.automation.whatsapp.db.WhatsAppContactRuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppAutomationBrainTest {

    @Test
    fun `safety filter catches OTP and sensitive credential requests`() {
        val otpMessage = "Please send me your OTP 482910 right now"
        val otpResult = WhatsAppSafetyFilter.inspectIncomingMessage(otpMessage)
        assertFalse(otpResult.isSafe)
        assertTrue(otpResult.safeFallbackReply?.contains("security", ignoreCase = true) == true)

        val passHarvestMessage = "What is your netbanking password?"
        val passResult = WhatsAppSafetyFilter.inspectIncomingMessage(passHarvestMessage)
        assertFalse(passResult.isSafe)

        val safeBengaliMessage = "অর্পন ভাই, কেমন আছেন? কাল কখন আসবেন?"
        val safeResult = WhatsAppSafetyFilter.inspectIncomingMessage(safeBengaliMessage)
        assertTrue(safeResult.isSafe)

        val safeEnglishMessage = "Hey, are you free for the meeting at 3 PM?"
        val englishResult = WhatsAppSafetyFilter.inspectIncomingMessage(safeEnglishMessage)
        assertTrue(englishResult.isSafe)
    }

    @Test
    fun `safety filter sanitizes outgoing reply from potential leaks`() {
        val leakedKey = "Here is the key: AIzaSyD2849582910485920194859201948592"
        val sanitized = WhatsAppSafetyFilter.sanitizeOutgoingReply(leakedKey)
        assertFalse(sanitized.contains("AIzaSy"))
        assertTrue(sanitized.contains("[REDACTED_API_KEY]"))
    }

    @Test
    fun `rule engine matches fixed reply and ignore rules correctly`() {
        val rules = listOf(
            WhatsAppContactRuleEntity(
                id = 1,
                contactPattern = "Boss Arpon",
                ruleType = WhatsAppContactRuleEntity.RULE_TYPE_INSTRUCTION,
                customContent = "Always address as Boss and be very respectful",
                priority = 20,
                isEnabled = true
            ),
            WhatsAppContactRuleEntity(
                id = 2,
                contactPattern = "Spammer",
                ruleType = WhatsAppContactRuleEntity.RULE_TYPE_IGNORE,
                priority = 15,
                isEnabled = true
            ),
            WhatsAppContactRuleEntity(
                id = 3,
                contactPattern = "Mom",
                ruleType = WhatsAppContactRuleEntity.RULE_TYPE_FIXED_REPLY,
                customContent = "Ammu, I am in office, will call you soon.",
                priority = 10,
                isEnabled = true
            )
        )

        // Evaluate Boss
        val bossDecision = ContactRuleEngine.evaluateRules("Boss Arpon", rules)
        assertTrue(bossDecision is ContactRuleEngine.RuleDecision.CustomInstruction)
        assertEquals(
            "Always address as Boss and be very respectful",
            (bossDecision as ContactRuleEngine.RuleDecision.CustomInstruction).instruction
        )

        // Evaluate Spammer
        val spammerDecision = ContactRuleEngine.evaluateRules("Spammer", rules)
        assertTrue(spammerDecision is ContactRuleEngine.RuleDecision.Ignore)

        // Evaluate Mom
        val momDecision = ContactRuleEngine.evaluateRules("Mom", rules)
        assertTrue(momDecision is ContactRuleEngine.RuleDecision.FixedReply)
        assertEquals(
            "Ammu, I am in office, will call you soon.",
            (momDecision as ContactRuleEngine.RuleDecision.FixedReply).replyText
        )

        // Unknown contact
        val strangerDecision = ContactRuleEngine.evaluateRules("Random Person", rules)
        assertTrue(strangerDecision is ContactRuleEngine.RuleDecision.NoRuleMatched)
    }

    @Test
    fun `offline fallback generates language-congruent replies`() {
        val engine = GeminiWhatsAppReplyEngine(
            apiKeyProvider = { "" }
        )

        // Bengali incoming
        val bengaliReply = engine.generateOfflineFallback("অর্পন, কেমন আছো?", "Arpon")
        assertTrue(bengaliReply.any { it in '\u0980'..'\u09FF' })
        assertTrue(bengaliReply.contains("Arpon"))

        // Hindi incoming
        val hindiReply = engine.generateOfflineFallback("Bhai kaise ho aap?", "Arpon")
        assertTrue(hindiReply.contains("Arpon"))
        assertTrue(hindiReply.contains("busy", ignoreCase = true) || hindiReply.contains("theek", ignoreCase = true))

        // English incoming
        val englishReply = engine.generateOfflineFallback("Hello Arpon, where are you?", "Arpon")
        assertTrue(englishReply.contains("Arpon"))
        assertTrue(englishReply.contains("occupied", ignoreCase = true) || englishReply.contains("away", ignoreCase = true))
    }
}
