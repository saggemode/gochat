package com.example.gochat.di

import android.content.Context
import com.example.gochat.data.db.AppDatabase
import com.example.gochat.data.db.ChatDao
import com.example.gochat.data.db.MarketplaceDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    @Provides
    fun provideChatDao(database: AppDatabase): ChatDao {
        return database.chatDao()
    }

    @Provides
    fun provideMarketplaceDao(database: AppDatabase): MarketplaceDao {
        return database.marketplaceDao()
    }
}
