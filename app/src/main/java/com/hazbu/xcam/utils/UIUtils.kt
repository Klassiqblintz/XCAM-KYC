package com.hazbu.xcam.utils

import android.content.Context
import android.widget.Toast

object UIUtils {
    fun showToast(context: Context?, message: String, logError: ((String?) -> Unit)? = null) {
        if (context != null) {
            try {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                logError?.invoke(e.message)
            }
        }
    }
}
