package com.ink.player

import android.app.Application
import com.ink.player.util.ToastUtil
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MyVideoPlayerApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        ToastUtil.init(this)
    }
}
