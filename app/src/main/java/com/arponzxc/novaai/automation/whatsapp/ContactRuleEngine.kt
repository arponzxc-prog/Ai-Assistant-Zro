package com.arponzxc.novaai.automation.whatsapp

import com.arponzxc.novaai.automation.whatsapp.db.WhatsAppContactRuleEntity

object ContactRuleEngine {

    sealed class RuleDecision {
        data class Ignore(val rule: WhatsAppContactRuleEntity) : RuleDecision()
        data class FixedReply(val replyText: String, val rule: WhatsAppContactRuleEntity) : RuleDecision()
        data class CustomInstruction(val instruction: String, val rule: WhatsAppContactRuleEntity) : RuleDecision()
        object NoRuleMatched : RuleDecision()
    }

    /**
     * Finds the highest-priority active rule matching the given sender name or phone number.
     */
    fun evaluateRules(
        senderName: String,
        rules: List<WhatsAppContactRuleEntity>
    ): RuleDecision {
        val cleanSender = senderName.trim().lowercase()
        if (cleanSender.isBlank() || rules.isEmpty()) return RuleDecision.NoRuleMatched

        // Filter enabled rules and sort by priority descending
        val activeRules = rules.filter { it.isEnabled }.sortedByDescending { it.priority }

        for (rule in activeRules) {
            val pattern = rule.contactPattern.trim().lowercase()
            if (pattern.isBlank()) continue

            val isMatch = when {
                pattern == "*" || pattern == "all" -> true
                pattern == cleanSender -> true
                cleanSender.contains(pattern) || pattern.contains(cleanSender) -> true
                matchesWildcard(cleanSender, pattern) -> true
                else -> false
            }

            if (isMatch) {
                return when (rule.ruleType) {
                    WhatsAppContactRuleEntity.RULE_TYPE_IGNORE -> RuleDecision.Ignore(rule)
                    WhatsAppContactRuleEntity.RULE_TYPE_FIXED_REPLY -> {
                        val reply = rule.customContent.trim().ifBlank { "Hello, I will reply shortly." }
                        RuleDecision.FixedReply(reply, rule)
                    }
                    WhatsAppContactRuleEntity.RULE_TYPE_INSTRUCTION -> {
                        RuleDecision.CustomInstruction(rule.customContent.trim(), rule)
                    }
                    else -> RuleDecision.NoRuleMatched
                }
            }
        }

        return RuleDecision.NoRuleMatched
    }

    private fun matchesWildcard(text: String, pattern: String): Boolean {
        return try {
            val regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".")
            Regex(regex, RegexOption.IGNORE_CASE).containsMatchIn(text)
        } catch (_: Exception) {
            false
        }
    }
}
