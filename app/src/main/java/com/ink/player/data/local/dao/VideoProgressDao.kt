package com.ink.player.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ink.player.data.local.entity.VideoProgressEntity

@Dao
interface VideoProgressDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: VideoProgressEntity)

    @Query("SELECT positionMs FROM video_progress WHERE videoUri = :videoUri")
    suspend fun getPosition(videoUri: String): Long?

    @Query("DELETE FROM video_progress WHERE videoUri = :videoUri")
    suspend fun delete(videoUri: String)
}
