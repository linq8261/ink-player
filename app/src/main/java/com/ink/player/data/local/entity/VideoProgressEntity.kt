package com.ink.player.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "video_progress")
data class VideoProgressEntity(
    @PrimaryKey val videoUri: String,
    val positionMs: Long,
    val lastPlayedAt: Long = System.currentTimeMillis()
)
