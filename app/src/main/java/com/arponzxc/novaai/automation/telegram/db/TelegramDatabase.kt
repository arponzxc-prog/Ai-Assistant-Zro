package com.arponzxc.novaai.automation.telegram.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * Telegram automation database. Completely separate file/tables from [com.arponzxc.novaai.automation.whatsapp.db.WhatsAppDatabase].
 *
 * Unlike the WhatsApp database this one does NOT use destructive migration: user rules
 * must survive upgrades. Add a [Migration] to [MIGRATIONS] whenever [VERSION] is bumped.
 */
@Database(
    entities = [
        TelegramRuleEntity::class,
        TelegramLogEntity::class,
        TelegramChatStatsEntity::class,
        TelegramErrorEntity::class
    ],
    version = TelegramDatabase.VERSION,
    exportSchema = false
)
abstract class TelegramDatabase : RoomDatabase() {

    abstract fun ruleDao(): TelegramRuleDao
    abstract fun logDao(): TelegramLogDao
    abstract fun chatStatsDao(): TelegramChatStatsDao
    abstract fun errorDao(): TelegramErrorDao

    companion object {
        const val VERSION = 1
        private const val DATABASE_NAME = "nova_telegram_automation.db"

        /** Version 1 is the initial schema, so there are no migrations yet. */
        val MIGRATIONS: Array<Migration> = emptyArray()

        @Volatile
        private var instance: TelegramDatabase? = null

        fun getInstance(context: Context): TelegramDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TelegramDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(*MIGRATIONS)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
