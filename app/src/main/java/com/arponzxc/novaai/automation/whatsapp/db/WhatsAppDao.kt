package com.arponzxc.novaai.automation.whatsapp.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WhatsAppConversationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: WhatsAppConversationEntity): Long

    @Query("SELECT * FROM whatsapp_conversations WHERE contactKey = :contactKey ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(contactKey: String, limit: Int = 10): List<WhatsAppConversationEntity>

    @Query("SELECT * FROM whatsapp_conversations ORDER BY timestamp DESC LIMIT :limit")
    fun getAllMessagesFlow(limit: Int = 100): Flow<List<WhatsAppConversationEntity>>

    @Query("DELETE FROM whatsapp_conversations WHERE contactKey = :contactKey")
    suspend fun deleteConversation(contactKey: String)

    @Query("DELETE FROM whatsapp_conversations")
    suspend fun clearAll()
}

@Dao
interface WhatsAppRuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: WhatsAppContactRuleEntity): Long

    @Update
    suspend fun updateRule(rule: WhatsAppContactRuleEntity)

    @Delete
    suspend fun deleteRule(rule: WhatsAppContactRuleEntity)

    @Query("DELETE FROM whatsapp_rules WHERE id = :id")
    suspend fun deleteRuleById(id: Long)

    @Query("SELECT * FROM whatsapp_rules ORDER BY priority DESC, id DESC")
    fun getAllRulesFlow(): Flow<List<WhatsAppContactRuleEntity>>

    @Query("SELECT * FROM whatsapp_rules WHERE isEnabled = 1 ORDER BY priority DESC")
    suspend fun getActiveRules(): List<WhatsAppContactRuleEntity>

    @Query("SELECT COUNT(*) FROM whatsapp_rules")
    suspend fun getRuleCount(): Int
}

@Dao
interface WhatsAppLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: WhatsAppLogEntity): Long

    @Query("SELECT * FROM whatsapp_activity_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogsFlow(limit: Int = 60): Flow<List<WhatsAppLogEntity>>

    @Query("SELECT * FROM whatsapp_activity_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLogs(limit: Int = 60): List<WhatsAppLogEntity>

    @Query("DELETE FROM whatsapp_activity_logs")
    suspend fun clearLogs()

    @Query("SELECT COUNT(*) FROM whatsapp_activity_logs WHERE status = 'REPLIED'")
    suspend fun getSuccessfulRepliesCount(): Int
}
