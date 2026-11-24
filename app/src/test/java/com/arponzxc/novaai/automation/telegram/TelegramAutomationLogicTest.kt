package com.arponzxc.novaai.automation.telegram

import com.arponzxc.novaai.automation.common.NotificationDeduplicator
import com.arponzxc.novaai.automation.telegram.TelegramContactRuleEngine.Action
import com.arponzxc.novaai.automation.telegram.TelegramMessageProcessor.ParseResult
import com.arponzxc.novaai.automation.telegram.TelegramMessageProcessor.RawNotification
import com.arponzxc.novaai.automation.telegram.TelegramMessageProcessor.SkipReason
import com.arponzxc.novaai.automation.telegram.db.TelegramRuleEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramSafetyFilterTest {

    @Test
    fun `blocks otp login codes passwords and card numbers`() {
        assertFalse(TelegramSafetyFilter.inspectIncomingMessage("Please send me your OTP 482910 right now").isSafe)
        assertFalse(TelegramSafetyFilter.inspectIncomingMessage("Login code: 12345. Do not share it").isSafe)
        assertFalse(TelegramSafetyFilter.inspectIncomingMessage("What is your netbanking password?").isSafe)
        assertFalse(TelegramSafetyFilter.inspectIncomingMessage("আপনার পাসওয়ার্ড কী?").isSafe)
        assertFalse(TelegramSafetyFilter.inspectIncomingMessage("card 4111 1111 1111 1111 exp 12/29").isSafe)
    }

    @Test
    fun `allows normal english and bengali messages`() {
        assertTrue(TelegramSafetyFilter.inspectIncomingMessage("Hey, are you free for the meeting at 3 PM?").isSafe)
        assertTrue(TelegramSafetyFilter.inspectIncomingMessage("অর্পন ভাই, কেমন আছেন? কাল কখন আসবেন?").isSafe)
        assertTrue(TelegramSafetyFilter.inspectIncomingMessage("call me on 01712345678").isSafe)
        // short keyword must not match inside another word
        assertTrue(TelegramSafetyFilter.inspectIncomingMessage("Let's get hotpot tonight").isSafe)
    }

    @Test
    fun `outgoing reply is sanitized and length limited`() {
        val cleaned = TelegramSafetyFilter.sanitizeOutgoingReply("sure, my password: abc123 ok")
        assertFalse(cleaned.contains("abc123"))
        assertEquals(500, TelegramSafetyFilter.sanitizeOutgoingReply("a".repeat(600)).length)
        assertEquals(900, TelegramSafetyFilter.sanitizeOutgoingReply("a".repeat(2000), 900).length)
    }

    @Test
    fun `redaction removes emails and phone numbers before AI`() {
        val out = TelegramSafetyFilter.redactForAi("mail john@example.com or call +8801712345678 please")
        assertFalse(out.contains("john@example.com"))
        assertFalse(out.contains("01712345678"))
        assertTrue(out.contains("[email]"))
        assertTrue(out.contains("[number]"))
    }
}

class TelegramContactRuleEngineTest {

    private fun rule(
        pattern: String,
        type: String,
        id: Long = 1,
        keywords: String = "",
        content: String = "",
        ai: Boolean = true,
        cooldown: Int = 0,
        priority: Int = 10,
        enabled: Boolean = true
    ) = TelegramRuleEntity(
        id = id, chatPattern = pattern, ruleType = type, keywords = keywords, customContent = content,
        aiEnabled = ai, cooldownSeconds = cooldown, priority = priority, isEnabled = enabled
    )

    private fun eval(rules: List<TelegramRuleEntity>, chat: String = "Alice", msg: String = "hello", allowlist: Boolean = false, key: String = "name:alice") =
        TelegramContactRuleEngine.evaluate(key, chat, msg, rules, allowlist)

    @Test
    fun `no rules means default AI behaviour`() {
        val o = eval(emptyList())
        assertEquals(Action.AI, o.action)
        assertNull(o.matchedRule)
        assertTrue(o.aiEnabled)
    }

    @Test
    fun `blocklist wins over every other rule`() {
        val o = eval(listOf(
            rule("alice", TelegramRuleEntity.RULE_FIXED_REPLY, id = 1, content = "hi", priority = 99),
            rule("Alice", TelegramRuleEntity.RULE_BLOCK, id = 2, priority = 1)
        ))
        assertEquals(Action.BLOCK, o.action)
    }

    @Test
    fun `matching is exact so a partial name never hits the wrong chat`() {
        val o = eval(listOf(rule("ali", TelegramRuleEntity.RULE_BLOCK)), chat = "Alice")
        assertEquals(Action.AI, o.action)
        assertFalse(TelegramContactRuleEngine.matchesChat("Alice", "name:alice2", "Alice 2"))
    }

    @Test
    fun `wildcards need an explicit star`() {
        assertTrue(TelegramContactRuleEngine.matchesChat("team *", "name:team alpha", "Team Alpha"))
        assertFalse(TelegramContactRuleEngine.matchesChat("team *", "name:my team", "My Team"))
        assertTrue(TelegramContactRuleEngine.matchesChat("*", "x", "y"))
        assertFalse(TelegramContactRuleEngine.matchesChat("", "x", "y"))
    }

    @Test
    fun `can match on stable chat key`() {
        assertTrue(TelegramContactRuleEngine.matchesChat("sc:123", "sc:123", "Whatever"))
    }

    @Test
    fun `allowlist only mode requires an ALLOW rule`() {
        assertEquals(Action.NOT_ALLOWLISTED, eval(emptyList(), allowlist = true).action)
        assertEquals(Action.NOT_ALLOWLISTED,
            eval(listOf(rule("bob", TelegramRuleEntity.RULE_ALLOW)), allowlist = true).action)
        assertEquals(Action.AI,
            eval(listOf(rule("alice", TelegramRuleEntity.RULE_ALLOW)), allowlist = true).action)
    }

    @Test
    fun `keyword reply and keyword ignore`() {
        val reply = eval(
            listOf(rule("*", TelegramRuleEntity.RULE_KEYWORD_REPLY, keywords = "price, cost", content = "See our website")),
            msg = "What is the PRICE?"
        )
        assertEquals(Action.FIXED_REPLY, reply.action)
        assertEquals("See our website", reply.fixedReply)

        val noMatch = eval(
            listOf(rule("*", TelegramRuleEntity.RULE_KEYWORD_REPLY, keywords = "price", content = "x")),
            msg = "Hello there"
        )
        assertEquals(Action.AI, noMatch.action)

        val ignore = eval(listOf(rule("*", TelegramRuleEntity.RULE_KEYWORD_IGNORE, keywords = "spam,promo")), msg = "big PROMO today")
        assertEquals(Action.IGNORE_KEYWORD, ignore.action)
    }

    @Test
    fun `fixed reply instruction ai flag and cooldown override`() {
        val fixed = eval(listOf(rule("alice", TelegramRuleEntity.RULE_FIXED_REPLY, content = "I'm busy")))
        assertEquals(Action.FIXED_REPLY, fixed.action)
        assertEquals("I'm busy", fixed.fixedReply)

        val instr = eval(listOf(rule("alice", TelegramRuleEntity.RULE_INSTRUCTION, content = "Be formal", cooldown = 300)))
        assertEquals(Action.AI, instr.action)
        assertEquals("Be formal", instr.instruction)
        assertEquals(300, instr.cooldownSeconds)

        val noAi = eval(listOf(rule("alice", TelegramRuleEntity.RULE_ALLOW, ai = false)))
        assertFalse(noAi.aiEnabled)
    }

    @Test
    fun `priority decides between fixed replies and disabled rules are ignored`() {
        val o = eval(listOf(
            rule("alice", TelegramRuleEntity.RULE_FIXED_REPLY, id = 1, content = "low", priority = 1),
            rule("alice", TelegramRuleEntity.RULE_FIXED_REPLY, id = 2, content = "high", priority = 50),
            rule("alice", TelegramRuleEntity.RULE_BLOCK, id = 3, enabled = false)
        ))
        assertEquals("high", o.fixedReply)
    }
}

class TelegramMessageClassifierTest {
    private fun c(t: String) = TelegramMessageClassifier.classify(t)

    @Test
    fun `classifies english and bengali`() {
        assertEquals(TelegramMessageClassifier.Category.URGENT, c("URGENT call me back"))
        assertEquals(TelegramMessageClassifier.Category.URGENT, c("জরুরি কথা আছে"))
        assertEquals(TelegramMessageClassifier.Category.QUESTION, c("What time is it"))
        assertEquals(TelegramMessageClassifier.Category.QUESTION, c("তুমি কোথায়?"))
        assertEquals(TelegramMessageClassifier.Category.GREETING, c("Hi there"))
        assertEquals(TelegramMessageClassifier.Category.GREETING, c("হ্যালো"))
        assertEquals(TelegramMessageClassifier.Category.THANKS, c("Thanks a lot"))
        assertEquals(TelegramMessageClassifier.Category.REQUEST, c("Can you send me the file"))
        assertEquals(TelegramMessageClassifier.Category.OTHER, c("ok"))
    }
}

class TelegramMessageProcessorTest {
    private val processor = TelegramMessageProcessor()

    private fun raw(
        title: String = "Alice",
        text: String = "Hello",
        conversationTitle: String? = null,
        isGroup: Boolean? = null,
        sender: String? = null,
        shortcut: String? = null,
        summary: Boolean = false,
        ongoing: Boolean = false,
        fromSelf: Boolean = false
    ) = RawNotification(
        packageName = "org.telegram.messenger", key = "k1", notificationId = 1, postTime = 1000L,
        title = title, text = text, conversationTitle = conversationTitle, isGroupFlag = isGroup,
        latestSenderName = sender, latestMessageText = null, latestMessageTimestamp = null,
        latestFromSelf = fromSelf, shortcutId = shortcut, isGroupSummary = summary,
        isOngoing = ongoing, isProgress = false
    )

    private fun accepted(r: ParseResult) = (r as ParseResult.Accepted).message
    private fun skipped(r: ParseResult) = (r as ParseResult.Skipped).reason

    @Test
    fun `official telegram package ids are recognised`() {
        assertTrue(TelegramMessageProcessor.isTelegramPackage("org.telegram.messenger"))
        assertTrue(TelegramMessageProcessor.isTelegramPackage("org.telegram.messenger.web"))
        assertFalse(TelegramMessageProcessor.isTelegramPackage("com.whatsapp"))
        assertFalse(TelegramMessageProcessor.isTelegramPackage("org.telegram.plus"))
        assertFalse(TelegramMessageProcessor.isTelegramPackage(null))
    }

    @Test
    fun `private message is accepted with a name based key`() {
        val m = accepted(processor.parse(raw()))
        assertEquals("Alice", m.chatName)
        assertEquals("name:alice", m.chatKey)
        assertFalse(m.isGroup)
        assertFalse(m.isUnknownSender)
    }

    @Test
    fun `shortcut id becomes the stable chat key`() {
        assertEquals("sc:abc", accepted(processor.parse(raw(shortcut = "abc"))).chatKey)
    }

    @Test
    fun `group messages are detected`() {
        val m = accepted(processor.parse(raw(title = "Bob", conversationTitle = "Family", isGroup = true, sender = "Bob")))
        assertTrue(m.isGroup)
        assertEquals("Family", m.chatName)
        assertEquals("Bob", m.senderName)
    }

    @Test
    fun `summary ongoing echo and service chat are skipped`() {
        assertEquals(SkipReason.SUMMARY, skipped(processor.parse(raw(summary = true))))
        assertEquals(SkipReason.ONGOING, skipped(processor.parse(raw(ongoing = true))))
        assertEquals(SkipReason.OUTGOING_ECHO, skipped(processor.parse(raw(fromSelf = true))))
        assertEquals(SkipReason.OUTGOING_ECHO, skipped(processor.parse(raw(text = "You: hi"))))
        assertEquals(SkipReason.SERVICE_CHAT, skipped(processor.parse(raw(title = "Telegram", text = "Login code: 12345"))))
        assertEquals(SkipReason.EMPTY_TEXT, skipped(processor.parse(raw(text = "  "))))
    }

    @Test
    fun `unsupported media types are skipped safely`() {
        listOf("📷 Photo", "🎤 Voice message", "Voice message", "Photo, nice view", "Sticker", "Bob pinned a message").forEach {
            assertEquals(it, SkipReason.UNSUPPORTED_MESSAGE_TYPE, skipped(processor.parse(raw(text = it))))
        }
        assertEquals(SkipReason.HIDDEN_PREVIEW, skipped(processor.parse(raw(text = "New message"))))
    }

    @Test
    fun `ordinary text that merely starts with a media word is accepted`() {
        assertNotNull(accepted(processor.parse(raw(text = "Photography class at 5 today?"))))
        assertNotNull(accepted(processor.parse(raw(text = "Video call tonight?"))))
    }

    @Test
    fun `unknown sender detection`() {
        assertTrue(processor.isUnknownSender("+8801712345678"))
        assertTrue(processor.isUnknownSender("01712-345678"))
        assertTrue(processor.isUnknownSender(""))
        assertFalse(processor.isUnknownSender("Alice"))
    }

    @Test
    fun `dedup key is stable for identical messages and differs for new ones`() {
        val a = raw(); val b = raw(); val c = raw(text = "Another")
        val ka = processor.dedupKey(a, accepted(processor.parse(a)))
        val kb = processor.dedupKey(b, accepted(processor.parse(b)))
        val kc = processor.dedupKey(c, accepted(processor.parse(c)))
        assertEquals(ka, kb)
        assertFalse(ka == kc)
    }
}

class NotificationDeduplicatorTest {
    @Test
    fun `same key is only accepted once inside the window`() {
        var now = 0L
        val d = NotificationDeduplicator(windowMs = 1000, maxEntries = 10, clock = { now })
        assertTrue(d.firstSeen("a"))
        assertFalse(d.firstSeen("a"))
        assertTrue(d.firstSeen("b"))
        now = 1500
        assertTrue(d.firstSeen("a"))
    }

    @Test
    fun `clear forgets everything and cache is bounded`() {
        val d = NotificationDeduplicator(windowMs = 60_000, maxEntries = 3, clock = { 0L })
        listOf("1", "2", "3", "4").forEach { d.firstSeen(it) }
        assertTrue(d.firstSeen("1"))      // evicted by LRU, so treated as new
        d.clear()
        assertTrue(d.firstSeen("4"))
    }
}

class GeminiTelegramReplyEngineTest {

    private fun request(
        mode: TelegramAutomationPreferences.ReplyMode = TelegramAutomationPreferences.ReplyMode.SHORT,
        lang: TelegramAutomationPreferences.LanguageMode = TelegramAutomationPreferences.LanguageMode.AUTO,
        message: String = "hello"
    ) = GeminiTelegramReplyEngine.Request(
        senderName = "Alice", incomingMessage = message,
        category = TelegramMessageClassifier.classify(message),
        operatorName = "Zed", mode = mode, language = lang,
        personality = "warm and funny", customPrompt = "Never promise deadlines", chatInstruction = "She is my sister"
    )

    @Test
    fun `prompt reflects mode language personality and custom instructions`() {
        val bn = GeminiTelegramReplyEngine.buildSystemPrompt(request(lang = TelegramAutomationPreferences.LanguageMode.BENGALI))
        assertTrue(bn.contains("Bengali"))
        assertTrue(bn.contains("warm and funny"))
        assertTrue(bn.contains("Never promise deadlines"))
        assertTrue(bn.contains("She is my sister"))
        assertTrue(bn.contains("untrusted"))

        val en = GeminiTelegramReplyEngine.buildSystemPrompt(request(lang = TelegramAutomationPreferences.LanguageMode.ENGLISH))
        assertTrue(en.contains("clear English"))

        val pro = GeminiTelegramReplyEngine.buildSystemPrompt(request(mode = TelegramAutomationPreferences.ReplyMode.PROFESSIONAL))
        assertTrue(pro.contains("professional"))
        val detailed = GeminiTelegramReplyEngine.buildSystemPrompt(request(mode = TelegramAutomationPreferences.ReplyMode.DETAILED))
        assertTrue(detailed.contains("3 to 5 sentences"))
    }

    @Test
    fun `offline fallback honours language mode`() {
        val engine = GeminiTelegramReplyEngine({ "" })
        val bnText = engine.generateOfflineFallback("কেমন আছেন?", "Zed")
        assertTrue(bnText.any { it in '\u0980'..'\u09FF' })
        val forcedEn = engine.generateOfflineFallback("কেমন আছেন?", "Zed", language = TelegramAutomationPreferences.LanguageMode.ENGLISH)
        assertFalse(forcedEn.any { it in '\u0980'..'\u09FF' })
        val forcedBn = engine.generateOfflineFallback("hello", "Zed", language = TelegramAutomationPreferences.LanguageMode.BENGALI)
        assertTrue(forcedBn.any { it in '\u0980'..'\u09FF' })
        assertTrue(engine.generateOfflineFallback("This is urgent", "Zed").contains("Zed"))
    }

    @Test
    fun `missing api key falls back offline and says so`() = runBlocking {
        val engine = GeminiTelegramReplyEngine({ "  " })
        val result = engine.generateReply(request())
        assertEquals(GeminiTelegramReplyEngine.Source.OFFLINE_FALLBACK, result.source)
        assertNotNull(result.error)
        assertTrue(result.text.isNotBlank())
    }
}
