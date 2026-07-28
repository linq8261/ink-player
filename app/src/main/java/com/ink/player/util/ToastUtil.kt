package com.ink.player.util

import android.content.Context
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Toast 工具类 - 支持在任意线程调用，复用 Toast 实例
 *
 * 使用步骤：
 * 1. 初始化：在 Application.onCreate() 中调用 ToastUtil.init(context)
 * 2. 调用：ToastUtil.show("消息") 或 ToastUtil.show(R.string.app_name)
 *
 * 使用示例：
 * ```kotlin
 * // 1. 在 Application 中初始化
 * class MyApplication : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         ToastUtil.init(this)
 *     }
 * }
 *
 * // 2. 在任意地方（包括子线程）调用
 * ToastUtil.show("短消息")
 * ToastUtil.showLong("长消息")
 * ToastUtil.show(R.string.app_name)
 * ToastUtil.showLong(R.string.app_name)
 * ToastUtil.cancel() // 取消当前显示的 Toast
 * ```
 */
object ToastUtil {

    private lateinit var applicationContext: Context
    private var toast: Toast? = null

    fun init(context: Context) {
        applicationContext = context.applicationContext
    }

    fun show(message: String) {
        showToast(message, Toast.LENGTH_SHORT)
    }

    fun showLong(message: String) {
        showToast(message, Toast.LENGTH_LONG)
    }

    fun show(resId: Int) {
        showToast(resId, Toast.LENGTH_SHORT)
    }

    fun showLong(resId: Int) {
        showToast(resId, Toast.LENGTH_LONG)
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun showToast(text: Any, duration: Int) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showToastInternal(text, duration)
        } else {
            GlobalScope.launch(Dispatchers.Main) {
                showToastInternal(text, duration)
            }
        }
    }

    private fun showToastInternal(text: Any, duration: Int) {
        cancel()
        toast = if (text is String) {
            Toast.makeText(applicationContext, text, duration)
        } else {
            @Suppress("UNCHECKED_CAST")
            Toast.makeText(applicationContext, text as Int, duration)
        }
        toast?.show()
    }

    fun cancel() {
        toast?.cancel()
        toast = null
    }
}
