package com.arponzxc.novaai.automation.telegram

import com.arponzxc.novaai.automation.telegram.db.TelegramRuleEntity

/**
 * Pure rule evaluation (no Android dependencies, fully unit-testable).
 *
 * Matching uses only identifiers that come from the notification itself: the stable chat key
 * and the chat display name. Matching is exact (case-insensitive) or uses explicit `*` wildcards;
 * there is deliberately no fuzzy "contains" matching so a rule can never hit the wrong chat.
 *
 * Evaluation order:
 *  1. BLOCK rules for this chat (always win)
 *  2. Allowlist-only mode: chat must have an ALLOW rule
 *  3. KEYWORD_IGNORE rules
 *  4. KEYWORD_REPLY rules (fixed reply when keyword appears)
 *  5. FIXED_REPLY rule for this chat
 *  6. INSTRUCTION / ALLOW rule for this chat (AI, optional custom instruction)
 *  7. no rule -> default AI behaviour
 */
object TelegramContactRuleEngine {

    enum class Action { BLOCK, NOT_ALLOWLISTED, IGNORE_KEYWORD, FIXED_REPLY, AI }

    data class Outcome(
        val action: Action,
        val fixedReply: String? = null,
        val instruction: String? = null,
        val aiEnabled: Boolean = true,
        val cooldownSeconds: Int? = null,
        val matchedRule: TelegramRuleEntity? = null
    )

    fun evaluate(
        chatKey: String,
        chatName: String,
        message: String,
        rules: List<TelegramRuleEntity>,
        allowlistOnly: Boolean
    ): Outcome {
        val active = rules.filter { it.isEnabled }
            .sortedWith(compareByDescending<TelegramRuleEntity> { it.priority }.thenByDescending { it.id })

        val chatRules = active.filter { matchesChat(it.chatPattern, chatKey, chatName) }
        val chatCooldown = chatRules.firstOrNull { it.cooldownSeconds > 0 }?.cooldownSeconds

        // 1. Blocklist
        chatRules.firstOrNull { it.ruleType == TelegramRuleEntity.RULE_BLOCK }?.let {
            return Outcome(Action.BLOCK, matchedRule = it)
        }

        // 2. Allowlist-only mode
        if (allowlistOnly && chatRules.none { it.ruleType == TelegramRuleEntity.RULE_ALLOW }) {
            return Outcome(Action.NOT_ALLOWLISTED)
        }

        val lowerMessage = message.lowercase()

        // 3. Keyword ignore
        chatRules.firstOrNull {
            it.ruleType == TelegramRuleEntity.RULE_KEYWORD_IGNORE && containsAnyKeyword(lowerMessage, it.keywords)
        }?.let { return Outcome(Action.IGNORE_KEYWORD, matchedRule = it) }

        // 4. Keyword reply
        chatRules.firstOrNull {
            it.ruleType == TelegramRuleEntity.RULE_KEYWORD_REPLY &&
                it.customContent.isNotBlank() &&
                containsAnyKeyword(lowerMessage, it.keywords)
        }?.let {
            return Outcome(Action.FIXED_REPLY, fixedReply = it.customContent.trim(),
                cooldownSeconds = it.cooldownSeconds.takeIf { c -> c > 0 } ?: chatCooldown, matchedRule = it)
        }

        // 5. Fixed reply for this chat
        chatRules.firstOrNull { it.ruleType == TelegramRuleEntity.RULE_FIXED_REPLY && it.customContent.isNotBlank() }?.let {
            return Outcome(Action.FIXED_REPLY, fixedReply = it.customContent.trim(),
                cooldownSeconds = it.cooldownSeconds.takeIf { c -> c > 0 } ?: chatCooldown, matchedRule = it)
        }

        // 6. Instruction / allow rule
        chatRules.firstOrNull {
            it.ruleType == TelegramRuleEntity.RULE_INSTRUCTION || it.ruleType == TelegramRuleEntity.RULE_ALLOW
        }?.let {
            return Outcome(
                Action.AI,
                instruction = it.customContent.trim().ifBlank { null },
                aiEnabled = it.aiEnabled,
                cooldownSeconds = it.cooldownSeconds.takeIf { c -> c > 0 } ?: chatCooldown,
                matchedRule = it
            )
        }

        return Outcome(Action.AI, cooldownSeconds = chatCooldown)
    }

    fun matchesChat(pattern: String, chatKey: String, chatName: String): Boolean {
        val p = pattern.trim().lowercase()
        if (p.isBlank()) return false
        if (p == "*") return true
        val candidates = listOf(chatKey.trim().lowercase(), chatName.trim().lowercase()).filter { it.isNotBlank() }
        return candidates.any { c ->
            if (p.contains('*')) wildcardToRegex(p).matches(c) else p == c
        }
    }

    private fun wildcardToRegex(pattern: String): Regex {
        val regex = pattern.split('*').joinToString(".*") { Regex.escape(it) }
        return Regex(regex, RegexOption.IGNORE_CASE)
    }

    private fun containsAnyKeyword(lowerMessage: String, keywordsCsv: String): Boolean {
        return keywordsCsv.split(',', '،', '\n')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .any { lowerMessage.contains(it) }
    }
}
