package com.arponzxc.novaai.automation.whatsapp.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        WhatsAppConversationEntity::class,
        WhatsAppContactRuleEntity::class,
        WhatsAppLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class WhatsAppDatabase : RoomDatabase() {

    abstract fun conversationDao(): WhatsAppConversationDao
    abstract fun ruleDao(): WhatsAppRuleDao
    abstract fun logDao(): WhatsAppLogDao

    companion object {
        private const val DATABASE_NAME = "nova_whatsapp_automation.db"

        @Volatile
        private var instance: WhatsAppDatabase? = null

        fun getInstance(context: Context): WhatsAppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WhatsAppDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
