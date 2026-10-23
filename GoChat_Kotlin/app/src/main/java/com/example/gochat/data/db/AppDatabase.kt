package com.example.gochat.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.gochat.data.model.*

/**
 * Local message/chat/marketplace store (WhatsApp-style: the UI reads from here, works offline,
 * and history survives app restarts).
 *
 * KEEPING HISTORY ACROSS APP UPDATES:
 * Schemas are exported to app/schemas (exportSchema = true). On each future schema change,
 * bump [version] and add an @AutoMigration here, e.g.:
 *
 *     @Database(
 *         ...,
 *         version = 20,
 *         autoMigrations = [AutoMigration(from = 19, to = 20)]
 *     )
 *
 * Room then migrates the existing database and KEEPS all messages for additive changes
 * (new column/table). For a destructive change (dropped/renamed column) Room will ask for an
 * @DeleteColumn/@RenameColumn spec. We no longer wipe the database on upgrade; only a DOWNGRADE
 * (rare, dev-only) falls back to recreating it.
 */
@Database(
    entities = [
        Conversation::class,
        Message::class,
        CallRecord::class,
        Store::class,
        Product::class,
        CartItem::class,
        Order::class,
        ProductRemoteKeys::class,
        BlockedUser::class
    ],
    version = 19,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun marketplaceDao(): MarketplaceDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gochat_database"
                )
                    // Upgrades keep history (via auto-migrations above). Only a downgrade recreates.
                    .fallbackToDestructiveMigrationOnDowngrade(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
