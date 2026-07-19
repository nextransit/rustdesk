package com.carriez.flutter_hbb

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent as KeyEventAndroid
import hbb.KeyEventConverter
import hbb.MessageOuterClass.KeyEvent
import hbb.MessageOuterClass.KeyboardMode

object MdmInputFallback {
    private const val TAG = "MdmInputFallback"
    private const val AUTHORITY = "com.decard.mdm.agent.rustdesk.input"
    private const val METHOD_POINTER = "pointer"
    private const val METHOD_KEY = "key"
    private const val METHOD_STATUS = "status"
    private const val KEY_SUCCESS = "success"

    fun isAvailable(context: Context): Boolean {
        return callProvider(context, METHOD_STATUS, Bundle())?.getBoolean(KEY_SUCCESS, false) == true
    }

    fun pointer(context: Context, kind: Int, mask: Int, x: Int, y: Int): Boolean {
        val result = callProvider(
            context,
            METHOD_POINTER,
            Bundle().apply {
                putInt("kind", kind)
                putInt("mask", mask)
                // x/y are already Android logical display coordinates. The
                // provider owns physical-display bounds validation and must see
                // the original values so it can reject, rather than hide, an
                // out-of-bounds mapping defect.
                putInt("x", x)
                putInt("y", y)
            }
        )
        return result?.getBoolean(KEY_SUCCESS, false) == true
    }

    fun key(context: Context, input: ByteArray): Boolean {
        val event = runCatching { KeyEvent.parseFrom(input) }.getOrElse {
            Log.w(TAG, "MDM input fallback key parse failed: ${it.message}")
            return false
        }

        textToCommit(event)?.let { text ->
            val result = callProvider(
                context,
                METHOD_KEY,
                Bundle().apply {
                    putString("text", text)
                }
            )
            val ok = result?.getBoolean(KEY_SUCCESS, false) == true
            Log.i(TAG, "MDM input fallback text len=${text.length} ok=$ok")
            return ok
        }

        val androidEvent = KeyEventConverter.toAndroidKeyEvent(event)
        val keyCode = androidEvent.keyCode
        if (keyCode <= 0) {
            Log.w(TAG, "MDM input fallback key ignored: keyCode=$keyCode event=$event")
            return false
        }
        if (androidEvent.action == KeyEventAndroid.ACTION_UP && !event.getPress()) {
            return true
        }
        val result = callProvider(
            context,
            METHOD_KEY,
            Bundle().apply {
                putInt("key_code", keyCode)
            }
        )
        val ok = result?.getBoolean(KEY_SUCCESS, false) == true
        Log.i(TAG, "MDM input fallback key keyCode=$keyCode ok=$ok")
        return ok
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

    private fun textToCommit(event: KeyEvent): String? {
        if (!event.getDown() && !event.getPress()) {
            return null
        }
        if (event.hasSeq()) {
            return event.getSeq().takeIf { it.isNotEmpty() }
        }
        if (event.getMode() == KeyboardMode.Legacy && event.hasChr()) {
            return runCatching { String(Character.toChars(event.getChr())) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
        }
        return null
    }
}
