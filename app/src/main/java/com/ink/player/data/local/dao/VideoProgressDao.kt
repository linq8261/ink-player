package com.ink.player.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ink.player.data.local.entity.VideoProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoProgressDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: VideoProgressEntity)

    @Query("SELECT positionMs FROM video_progress WHERE videoUri = :videoUri")
    suspend fun getPosition(videoUri: String): Long?

    /** 最近播放记录：按最后播放时间倒序，最多 50 条（Flow 实时响应） */
    @Query("SELECT * FROM video_progress ORDER BY lastPlayedAt DESC LIMIT 50")
    fun getRecentPlayed(): Flow<List<VideoProgressEntity>>
}
