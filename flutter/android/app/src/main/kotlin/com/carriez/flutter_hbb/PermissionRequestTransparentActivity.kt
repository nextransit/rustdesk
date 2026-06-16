package com.carriez.flutter_hbb

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

class PermissionRequestTransparentActivity: Activity() {
    private val logTag = "permissionRequest"
    private var requestStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(logTag, "onCreate PermissionRequestTransparentActivity: intent.action: ${intent.action}")

        if (intent.action != ACT_REQUEST_MEDIA_PROJECTION) {
            finish()
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        if (intent.action != ACT_REQUEST_MEDIA_PROJECTION || requestStarted) {
            return
        }
        requestStarted = true
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed) {
                requestMediaProjection()
            }
        }, 250)
    }

    private fun requestMediaProjection() {
        try {
            val mediaProjectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val requestIntent = mediaProjectionManager.createScreenCaptureIntent()
            Log.d(logTag, "Starting MediaProjection permission request")
            startActivityForResult(requestIntent, REQ_REQUEST_MEDIA_PROJECTION)
        } catch (e: Exception) {
            Log.e(logTag, "Failed to start MediaProjection permission request", e)
            setResult(RES_FAILED)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_REQUEST_MEDIA_PROJECTION) {
            Log.d(logTag, "MediaProjection result: code=$resultCode hasData=${data != null}")
            if (resultCode == RESULT_OK && data != null) {
                launchService(data)
            } else {
                setResult(RES_FAILED)
            }
        }

        finish()
    }

    private fun launchService(mediaProjectionResultIntent: Intent) {
        Log.d(logTag, "Launch MainService")
        val serviceIntent = Intent(this, MainService::class.java)
        serviceIntent.action = ACT_INIT_MEDIA_PROJECTION_AND_SERVICE
        serviceIntent.putExtra(EXT_MEDIA_PROJECTION_RES_INTENT, mediaProjectionResultIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

}
