package com.ink.player.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.ink.player.data.local.dao.VideoProgressDao
import com.ink.player.data.local.entity.VideoProgressEntity

@Database(entities = [VideoProgressEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoProgressDao(): VideoProgressDao
}
