package com.ink.player.di

import android.content.Context
import androidx.room.Room
import com.ink.player.data.local.AppDatabase
import com.ink.player.data.local.dao.VideoProgressDao
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
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "ink_player.db"
        ).build()
    }

    @Provides
    fun provideVideoProgressDao(database: AppDatabase): VideoProgressDao {
        return database.videoProgressDao()
    }
}
