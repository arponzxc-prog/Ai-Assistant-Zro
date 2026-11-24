package com.arponzxc.novaai.automation.common

import java.util.Collections

/**
 * Thread-safe LRU + time-window de-duplicator.
 * Messaging apps re-post the same notification whenever their bundle is updated, so the
 * same (chat, text, timestamp) can arrive several times. [firstSeen] returns true only once
 * per key inside the window.
 */
class NotificationDeduplicator(
    private val windowMs: Long = 120_000L,
    private val maxEntries: Int = 200,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val seen: MutableMap<String, Long> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
                size > maxEntries
        }
    )

    /** @return true if this key has not been seen within the window (and records it). */
    fun firstSeen(key: String): Boolean {
        val now = clock()
        synchronized(seen) {
            val last = seen[key]
            if (last != null && now - last < windowMs) return false
            seen[key] = now
            return true
        }
    }

    fun clear() = seen.clear()
}
