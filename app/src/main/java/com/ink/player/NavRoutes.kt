package com.ink.player

import android.net.Uri

object NavRoutes {
    const val VIDEO_LIST = "video_list"
    const val PLAYER = "player/{videoUri}"
    const val SETTINGS = "settings"
    const val EXPERIMENTAL = "experimental"
    const val ABOUT = "about"
    const val SET_PASSWORD = "set_password"
    const val VERIFY_PASSWORD = "verify_password"
    const val PRIVATE = "private"
    const val WUKONG_CONVERT = "wukong_convert"

    fun playerRoute(videoUri: String): String {
        return "player/${Uri.encode(videoUri)}"
    }
}