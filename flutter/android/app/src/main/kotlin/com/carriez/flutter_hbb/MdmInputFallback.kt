package com.carriez.flutter_hbb

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import kotlin.math.max

object MdmInputFallback {
    private const val TAG = "MdmInputFallback"
    private const val AUTHORITY = "com.decard.mdm.agent.rustdesk.input"
    private const val METHOD_POINTER = "pointer"
    private const val KEY_SUCCESS = "success"

    fun pointer(context: Context, kind: Int, mask: Int, x: Int, y: Int): Boolean {
        val scaledX = max(0, x) * SCREEN_INFO.scale
        val scaledY = max(0, y) * SCREEN_INFO.scale
        return runCatching {
            val result = context.contentResolver.call(
                Uri.parse("content://$AUTHORITY"),
                METHOD_POINTER,
                null,
                Bundle().apply {
                    putInt("kind", kind)
                    putInt("mask", mask)
                    putInt("x", scaledX)
                    putInt("y", scaledY)
                }
            )
            result?.getBoolean(KEY_SUCCESS, false) == true
        }.getOrElse {
            Log.w(TAG, "MDM input fallback unavailable: ${it.message}")
            false
        }
    }
}
