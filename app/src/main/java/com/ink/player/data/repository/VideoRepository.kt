package com.ink.player.data.repository

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.ink.player.data.model.VideoInfo

class VideoRepository(private val context: Context) {

    fun scanVideos(): List<VideoInfo> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_ADDED
        )

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val videos = mutableListOf<VideoInfo>()

        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val displayName = cursor.getString(nameCol) ?: "Unknown"
                val durationMs = cursor.getLong(durCol)
                val size = cursor.getLong(sizeCol)
                val mimeType = cursor.getString(mimeCol) ?: ""
                val dateAdded = cursor.getLong(dateCol)
                val uri = Uri.withAppendedPath(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString()
                )

                if (durationMs > 0) {
                    videos.add(
                        VideoInfo(
                            id = id,
                            displayName = displayName,
                            uri = uri,
                            durationMs = durationMs,
                            size = size,
                            mimeType = mimeType,
                            dateAdded = dateAdded
                        )
                    )
                }
            }
        }

        return videos
    }
}
