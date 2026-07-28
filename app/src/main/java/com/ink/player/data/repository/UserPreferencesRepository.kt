package com.ink.player.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

class UserPreferencesRepository private constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        @Volatile
        private var INSTANCE: UserPreferencesRepository? = null

        fun getInstance(context: Context): UserPreferencesRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserPreferencesRepository(context.applicationContext.dataStore)
                    .also { INSTANCE = it }
            }
        }

        private val SEEK_THROTTLE_MS = longPreferencesKey("seek_throttle_ms")
        private val NORMAL_SPEED = floatPreferencesKey("normal_speed")
        private val GESTURE_BRIGHTNESS = booleanPreferencesKey("gesture_brightness")
        private val GESTURE_VOLUME = booleanPreferencesKey("gesture_volume")
    }

    /** 拖动时间轴 seek 节流间隔（毫秒），默认 100ms */
    val seekThrottleMs: Flow<Long> = dataStore.data.map { preferences ->
        preferences[SEEK_THROTTLE_MS] ?: 100L
    }

    suspend fun setSeekThrottleMs(value: Long) {
        dataStore.edit { preferences ->
            preferences[SEEK_THROTTLE_MS] = value
        }
    }

    /** 默认播放速度，默认 1.0x */
    val normalSpeed: Flow<Float> = dataStore.data.map { preferences ->
        preferences[NORMAL_SPEED] ?: 1f
    }

    suspend fun setNormalSpeed(value: Float) {
        dataStore.edit { preferences ->
            preferences[NORMAL_SPEED] = value
        }
    }

    /** 滑动调节屏幕亮度手势，默认关闭 */
    val gestureBrightnessEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[GESTURE_BRIGHTNESS] ?: false
    }

    suspend fun setGestureBrightnessEnabled(value: Boolean) {
        dataStore.edit { preferences ->
            preferences[GESTURE_BRIGHTNESS] = value
        }
    }

    /** 滑动调节媒体音量手势，默认关闭 */
    val gestureVolumeEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[GESTURE_VOLUME] ?: false
    }

    suspend fun setGestureVolumeEnabled(value: Boolean) {
        dataStore.edit { preferences ->
            preferences[GESTURE_VOLUME] = value
        }
    }
}
