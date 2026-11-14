package com.hazbu.xcam.utils

import android.util.Log

object Logger {
    private const val TAG = "xCam"

    fun i(source: Any, msg: String) {
        Log.i("xCam", "[$TAG] [${source.javaClass.simpleName}] $msg")
    }

    fun d(source: Any, msg: String) {
        Log.d("xCam", "[$TAG] [${source.javaClass.simpleName}] $msg")
    }

    fun w(source: Any, msg: String) {
        Log.w("xCam", "[$TAG] [${source.javaClass.simpleName}] $msg")
    }

    fun e(source: Any, msg: String, tr: Throwable? = null) {
        if (tr != null) {
            Log.e("xCam", "[$TAG] [${source.javaClass.simpleName}] $msg", tr)
        } else {
            Log.e("xCam", "[$TAG] [${source.javaClass.simpleName}] $msg")
        }
    }
}
