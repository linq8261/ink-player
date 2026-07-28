package com.ink.player.navigation

import android.net.Uri

object NavRoutes {
    const val VIDEO_LIST = "video_list"
    const val PLAYER = "player/{videoUri}"
    const val SETTINGS = "settings"
    const val ABOUT = "about"

    fun playerRoute(videoUri: String): String {
        return "player/${Uri.encode(videoUri)}"
    }
}
