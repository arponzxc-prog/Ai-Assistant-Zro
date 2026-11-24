package com.arponzxc.novaai.automation.telegram.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TelegramRuleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: TelegramRuleEntity): Long

    @Update
    suspend fun updateRule(rule: TelegramRuleEntity)

    @Delete
    suspend fun deleteRule(rule: TelegramRuleEntity)

    @Query("SELECT * FROM telegram_rules ORDER BY priority DESC, id DESC")
    fun getAllRulesFlow(): Flow<List<TelegramRuleEntity>>

    @Query("SELECT * FROM telegram_rules WHERE isEnabled = 1 ORDER BY priority DESC, id DESC")
    suspend fun getActiveRules(): List<TelegramRuleEntity>
}

@Dao
interface TelegramLogDao {
    @Insert
    suspend fun insertLog(log: TelegramLogEntity): Long

    @Query("SELECT * FROM telegram_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogsFlow(limit: Int = 60): Flow<List<TelegramLogEntity>>

    @Query("DELETE FROM telegram_logs")
    suspend fun clearLogs()

    /** Keeps the log small: removes everything older than [cutoff] and anything beyond the newest [keep] rows. */
    @Query("DELETE FROM telegram_logs WHERE timestamp < :cutoff OR id NOT IN (SELECT id FROM telegram_logs ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun prune(cutoff: Long, keep: Int)

    @Query("SELECT COUNT(*) FROM telegram_logs WHERE status = 'REPLIED' AND timestamp >= :since")
    fun repliedSinceFlow(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM telegram_logs WHERE status LIKE 'SKIPPED%' AND timestamp >= :since")
    fun skippedSinceFlow(since: Long): Flow<Int>
}

@Dao
interface TelegramChatStatsDao {
    @Query("SELECT * FROM telegram_chat_stats WHERE chatKey = :chatKey")
    suspend fun get(chatKey: String): TelegramChatStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stats: TelegramChatStatsEntity)

    @Query("SELECT COALESCE(SUM(replyCount), 0) FROM telegram_chat_stats")
    fun totalRepliesFlow(): Flow<Int>

    @Query("SELECT COALESCE(MAX(lastProcessedTime), 0) FROM telegram_chat_stats")
    fun lastProcessedFlow(): Flow<Long>

    @Query("DELETE FROM telegram_chat_stats")
    suspend fun clearAll()
}

@Dao
interface TelegramErrorDao {
    @Insert
    suspend fun insertError(error: TelegramErrorEntity): Long

    @Query("SELECT * FROM telegram_errors ORDER BY timestamp DESC LIMIT 1")
    fun latestErrorFlow(): Flow<TelegramErrorEntity?>

    @Query("SELECT COUNT(*) FROM telegram_errors WHERE timestamp >= :since")
    fun errorsSinceFlow(since: Long): Flow<Int>

    @Query("DELETE FROM telegram_errors")
    suspend fun clearErrors()

    @Query("DELETE FROM telegram_errors WHERE timestamp < :cutoff OR id NOT IN (SELECT id FROM telegram_errors ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun prune(cutoff: Long, keep: Int)
}
