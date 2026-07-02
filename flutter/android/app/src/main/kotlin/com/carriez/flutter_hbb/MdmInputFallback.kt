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
    private const val METHOD_STATUS = "status"
    private const val KEY_SUCCESS = "success"

    fun isAvailable(context: Context): Boolean {
        return callProvider(context, METHOD_STATUS, Bundle())?.getBoolean(KEY_SUCCESS, false) == true
    }

    fun pointer(context: Context, kind: Int, mask: Int, x: Int, y: Int): Boolean {
        val scaledX = max(0, x) * SCREEN_INFO.scale
        val scaledY = max(0, y) * SCREEN_INFO.scale
        val result = callProvider(
            context,
            METHOD_POINTER,
            Bundle().apply {
                putInt("kind", kind)
                putInt("mask", mask)
                putInt("x", scaledX)
                putInt("y", scaledY)
            }
        )
        return result?.getBoolean(KEY_SUCCESS, false) == true
    }

    private fun callProvider(context: Context, method: String, extras: Bundle): Bundle? {
        return runCatching {
            context.contentResolver.call(
                Uri.parse("content://$AUTHORITY"),
                method,
                null,
                extras
            )
        }.getOrElse {
            Log.w(TAG, "MDM input fallback unavailable: ${it.message}")
            null
        }
    }
}
