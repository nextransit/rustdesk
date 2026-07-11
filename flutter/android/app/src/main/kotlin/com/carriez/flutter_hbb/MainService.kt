package com.carriez.flutter_hbb

import ffi.FFI

/**
 * Capture screen,get video and audio,send to rust.
 * Dispatch notifications
 *
 * Inspired by [droidVNC-NG] https://github.com/bk138/droidVNC-NG
 */

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
import android.view.WindowManager
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

const val DEFAULT_NOTIFY_TITLE = "RustDesk"
const val DEFAULT_NOTIFY_TEXT = "Service is running"
const val DEFAULT_NOTIFY_ID = 1
const val NOTIFY_ID_OFFSET = 100

const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_VP9

// video const

const val MAX_SCREEN_SIZE = 1200

const val VIDEO_KEY_BIT_RATE = 1024_000
const val VIDEO_KEY_FRAME_RATE = 30

class MainService : Service() {

    @Keep
    @RequiresApi(Build.VERSION_CODES.N)
    fun rustPointerInput(kind: Int, mask: Int, x: Int, y: Int) {
        // turn on screen with LEFT_DOWN when screen off
        if (!powerManager.isInteractive && (kind == 0 || mask == LEFT_DOWN)) {
            ensureScreenInteractive("pointer_input")
        }
        val mapped = mapRemoteInputToScreen(x, y)
        val mappedX = mapped.first
        val mappedY = mapped.second
        val inputService = InputService.ctx
        if (inputService != null) {
            Log.i(
                logTag,
                "MDM-InputDispatch pointer_route=accessibility kind=$kind mask=$mask " +
                    "raw_x=$x raw_y=$y x=$mappedX y=$mappedY"
            )
            when (kind) {
                0 -> { // touch
                    inputService.onTouchInput(mask, mappedX, mappedY)
                }
                1 -> { // mouse
                    inputService.onMouseInput(mask, mappedX, mappedY)
                }
                else -> {
                    Log.w(logTag, "MDM-InputDispatch pointer_route=accessibility_ignored kind=$kind mask=$mask")
                }
            }
            return
        }
        Log.w(
            logTag,
            "MDM-InputDispatch pointer_route=provider_fallback reason=accessibility_unavailable kind=$kind mask=$mask " +
                "raw_x=$x raw_y=$y x=$mappedX y=$mappedY"
        )
        if (!MdmInputFallback.pointer(applicationContext, kind, mask, mappedX, mappedY)) {
            Log.w(logTag, "MDM-InputDispatch pointer_route=provider_fallback_failed kind=$kind mask=$mask")
        }
    }

    private fun mapRemoteInputToScreen(x: Int, y: Int): Pair<Int, Int> {
        if (captureSourceValue != CAPTURE_SOURCE_MDM_SCREENRECORD || mdmCaptureWidth <= 0 || mdmCaptureHeight <= 0) {
            return Pair(x, y)
        }
        val mappedX = (x.toDouble() * SCREEN_INFO.width.toDouble() / mdmCaptureWidth.toDouble()).toInt()
        val mappedY = (y.toDouble() * SCREEN_INFO.height.toDouble() / mdmCaptureHeight.toDouble()).toInt()
        return Pair(mappedX, mappedY)
    }

    @Keep
    @RequiresApi(Build.VERSION_CODES.N)
    fun rustKeyEventInput(input: ByteArray) {
        val inputService = InputService.ctx
        if (inputService != null) {
            Log.i(logTag, "MDM-InputDispatch key_route=accessibility bytes=${input.size}")
            inputService.onKeyEvent(input)
            return
        }
        Log.w(
            logTag,
            "MDM-InputDispatch key_route=provider_fallback reason=accessibility_unavailable bytes=${input.size}"
        )
        if (!MdmInputFallback.key(applicationContext, input)) {
            Log.w(logTag, "MDM-InputDispatch key_route=provider_fallback_failed bytes=${input.size}")
        }
    }

    @Keep
    fun rustGetByName(name: String): String {
        return when (name) {
            "screen_size" -> {
                val screenSize = currentScreenSizeForRustDesk()
                JSONObject().apply {
                    put("width", screenSize.first)
                    put("height", screenSize.second)
                    put("scale", screenSize.third)
                }.toString()
            }
            "is_start" -> {
                isStart.toString()
            }
            "capture_source" -> captureSourceValue
            else -> ""
        }
    }

    @Keep
    fun rustSetByName(name: String, arg1: String, arg2: String) {
        when (name) {
            "add_connection" -> {
                try {
                    val jsonObject = JSONObject(arg1)
                    val id = jsonObject["id"] as Int
                    val username = jsonObject["name"] as String
                    val peerId = jsonObject["peer_id"] as String
                    val authorized = jsonObject["authorized"] as Boolean
                    val isFileTransfer = jsonObject["is_file_transfer"] as Boolean
                    val type = if (isFileTransfer) {
                        translate("Transfer file")
                    } else {
                        translate("Share screen")
                    }
                    if (authorized) {
                        if (!isFileTransfer) {
                            // mdm-no-launcher: START_NO_PROJECTION 会先启动 rust 端监听,
                            // 但不代表 Android MediaProjection 已可用。远控连入时必须以
                            // mediaProjection 为准触发采集, 不能被 isStart 短路。
                            keepScreenInteractive("add_connection")
                            Log.d(
                                logTag,
                                "add_connection: authorized=$authorized isStart=$isStart mediaProjection=${mediaProjection != null}"
                            )
                            if (requiresMdmSystemScreenrecord()) {
                                if (!switchToMdmSystemScreenrecordCapture("add_connection", false)) {
                                    Log.w(logTag, "add_connection: managed screenrecord source is not ready")
                                }
                            } else if (mediaProjection == null) {
                                Log.d(logTag, "add_connection: mediaProjection null, requesting")
                                requestMediaProjection()
                            } else if (!isStart) {
                                startCapture()
                            } else {
                                Log.d(logTag, "add_connection: capture already running")
                            }
                        }
                        onClientAuthorizedNotification(id, type, username, peerId)
                    } else {
                        loginRequestNotification(id, type, username, peerId)
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
            "update_voice_call_state" -> {
                try {
                    val jsonObject = JSONObject(arg1)
                    val id = jsonObject["id"] as Int
                    val username = jsonObject["name"] as String
                    val peerId = jsonObject["peer_id"] as String
                    val inVoiceCall = jsonObject["in_voice_call"] as Boolean
                    val incomingVoiceCall = jsonObject["incoming_voice_call"] as Boolean
                    if (!isMdmAudioEnabled()) {
                        Log.i(logTag, "update_voice_call_state ignored because MDM audio is disabled")
                        return
                    }
                    if (!inVoiceCall) {
                        if (incomingVoiceCall) {
                            voiceCallRequestNotification(id, "Voice Call Request", username, peerId)
                        } else {
                            if (!audioRecordHandle.switchOutVoiceCall(mediaProjection)) {
                                Log.e(logTag, "switchOutVoiceCall fail")
                                MainActivity.flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                                    "type" to "custom-nook-nocancel-hasclose-error",
                                    "title" to "Voice call",
                                    "text" to "Failed to switch out voice call."))
                            }
                        }
                    } else {
                        if (!audioRecordHandle.switchToVoiceCall(mediaProjection)) {
                            Log.e(logTag, "switchToVoiceCall fail")
                            MainActivity.flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                                "type" to "custom-nook-nocancel-hasclose-error",
                                "title" to "Voice call",
                                "text" to "Failed to switch to voice call."))
                        }
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
            "stop_capture" -> {
                Log.d(logTag, "from rust:stop_capture")
                stopCapture()
            }
            "half_scale" -> {
                val halfScale = arg1.toBoolean()
                if (isHalfScale != halfScale) {
                    isHalfScale = halfScale
                    updateScreenInfo(resources.configuration.orientation)
                }
                
            }
            else -> {
            }
        }
    }

    private var serviceLooper: Looper? = null
    private var serviceHandler: Handler? = null

    private val powerManager: PowerManager by lazy { applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val wakeLock: PowerManager.WakeLock by lazy { powerManager.newWakeLock(PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "rustdesk:wakelock")}

    private fun ensureScreenInteractive(reason: String) {
        if (powerManager.isInteractive && wakeLock.isHeld) {
            return
        }
        if (wakeLock.isHeld) {
            Log.d(logTag, "Wake screen for $reason, WakeLock release")
            wakeLock.release()
        }
        Log.d(logTag, "Wake screen for $reason")
        wakeLock.acquire(5000)
    }

    private fun keepScreenInteractive(reason: String) {
        if (wakeLock.isHeld && powerManager.isInteractive) {
            return
        }
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
        Log.d(logTag, "Keep screen awake for $reason")
        wakeLock.acquire()
    }

    private fun releaseScreenWakeLock(reason: String) {
        if (wakeLock.isHeld) {
            Log.d(logTag, "Release screen wake lock for $reason")
            wakeLock.release()
        }
    }

    companion object {
        private const val CAPTURE_STATS_LOG_INTERVAL_MS = 5_000L
        private const val MDM_MAX_PUBLISH_FPS = 15
        private const val MDM_MIN_PUBLISH_INTERVAL_MS = 1000L / MDM_MAX_PUBLISH_FPS
        private const val MDM_KEEPALIVE_FRAME_INTERVAL_MS = 250L
        private const val MDM_BOOTSTRAP_MAX_BYTES = 2 * 1024 * 1024
        private const val CAPTURE_LOG_TAG = "LOG_SERVICE"
        private const val MEDIA_PROJECTION_REQUEST_TTL_MS = 12_000L
        private const val CAPTURE_SOURCE_NONE = "none"
        private const val CAPTURE_SOURCE_MEDIA_PROJECTION = "media_projection"
        private const val CAPTURE_SOURCE_MDM_SCREENRECORD = "mdm_screenrecord"
        private const val MDM_SOFTWARE_AVC_DECODER = "OMX.google.h264.decoder"
        private var _isReady = false // media permission ready status
        private var _isStart = false // screen capture start status
        private var _isAudioStart = false // audio capture start status
        private val captureFrameCount = AtomicLong(0)
        private val captureByteCount = AtomicLong(0)
        private val captureDroppedFrameCount = AtomicLong(0)
        private val captureDuplicateFrameCount = AtomicLong(0)
        private val captureErrorCount = AtomicLong(0)
        @Volatile private var captureStartedAtMs = 0L
        @Volatile private var captureLastFrameAtMs = 0L
        @Volatile private var captureLastStatsLogAtMs = 0L
        @Volatile private var captureLastStatsLogFrameCount = 0L
        @Volatile private var captureLastStatsLogByteCount = 0L
        @Volatile private var captureLastError: String? = null
        @Volatile private var captureSourceValue = CAPTURE_SOURCE_NONE
        @Volatile private var mdmCaptureWidth = 0
        @Volatile private var mdmCaptureHeight = 0
        @Volatile private var mediaProjectionRequestStartedAtMs = 0L
        @Volatile private var activeInstance: MainService? = null
        val isReady: Boolean
            get() = _isReady
        val isStart: Boolean
            get() = _isStart
        val isCapturing: Boolean
            get() = _isReady && _isStart
        val isAudioStart: Boolean
            get() = _isAudioStart

        val captureFrames: Long
            get() = captureFrameCount.get()
        val captureBytes: Long
            get() = captureByteCount.get()
        val captureDroppedFrames: Long
            get() = captureDroppedFrameCount.get()
        val captureDuplicateFrames: Long
            get() = captureDuplicateFrameCount.get()
        val captureErrors: Long
            get() = captureErrorCount.get()
        val captureStartedAt: Long
            get() = captureStartedAtMs
        val captureLastFrameAt: Long
            get() = captureLastFrameAtMs
        val captureLastFrameAgeMs: Long
            get() = captureLastFrameAtMs.takeIf { it > 0L }?.let {
                (System.currentTimeMillis() - it).coerceAtLeast(0L)
            } ?: -1L
        val captureDurationMs: Long
            get() = captureStartedAtMs.takeIf { it > 0L }?.let {
                (System.currentTimeMillis() - it).coerceAtLeast(0L)
            } ?: 0L
        val captureAverageFps: Double
            get() {
                val durationMs = captureDurationMs
                return if (durationMs > 0L) captureFrameCount.get() * 1000.0 / durationMs else 0.0
            }
        val captureLastErrorMessage: String?
            get() = captureLastError
        val captureSource: String
            get() = captureSourceValue
        val captureWidth: Int
            get() = if (captureSourceValue == CAPTURE_SOURCE_MDM_SCREENRECORD && mdmCaptureWidth > 0) mdmCaptureWidth else SCREEN_INFO.width
        val captureHeight: Int
            get() = if (captureSourceValue == CAPTURE_SOURCE_MDM_SCREENRECORD && mdmCaptureHeight > 0) mdmCaptureHeight else SCREEN_INFO.height
        val mediaProjectionRequestInFlight: Boolean
            get() {
                val startedAt = mediaProjectionRequestStartedAtMs
                val ageMs = System.currentTimeMillis() - startedAt
                return startedAt > 0L && ageMs in 0L..MEDIA_PROJECTION_REQUEST_TTL_MS
            }

        fun switchToMdmSystemScreenrecord(reason: String, forceRestart: Boolean = false): Boolean {
            return activeInstance?.switchToMdmSystemScreenrecordCapture(reason, forceRestart) ?: false
        }

        fun markMediaProjectionRequestStarted(reason: String) {
            mediaProjectionRequestStartedAtMs = System.currentTimeMillis()
            Log.i(CAPTURE_LOG_TAG, "MDM-MediaProjectionRequest state=started reason=$reason")
        }

        fun clearMediaProjectionRequest(reason: String) {
            if (mediaProjectionRequestStartedAtMs > 0L) {
                Log.i(CAPTURE_LOG_TAG, "MDM-MediaProjectionRequest state=cleared reason=$reason")
            }
            mediaProjectionRequestStartedAtMs = 0L
        }

        fun resetCaptureStats() {
            val now = System.currentTimeMillis()
            captureFrameCount.set(0)
            captureByteCount.set(0)
            captureDroppedFrameCount.set(0)
            captureErrorCount.set(0)
            captureStartedAtMs = now
            captureLastFrameAtMs = 0L
            captureLastStatsLogAtMs = now
            captureLastStatsLogFrameCount = 0L
            captureLastStatsLogByteCount = 0L
            captureLastError = null
            Log.i(
                CAPTURE_LOG_TAG,
                "MDM-CaptureStart width=$captureWidth height=$captureHeight " +
                    "scale=${SCREEN_INFO.scale} dpi=${SCREEN_INFO.dpi}"
            )
        }

        fun recordCaptureFrame(byteCount: Int) {
            val now = System.currentTimeMillis()
            val frames = captureFrameCount.incrementAndGet()
            val bytes = captureByteCount.addAndGet(byteCount.toLong().coerceAtLeast(0L))
            captureLastFrameAtMs = now
            if (now - captureLastStatsLogAtMs >= CAPTURE_STATS_LOG_INTERVAL_MS) {
                val lastLogAt = captureLastStatsLogAtMs
                val deltaMs = (now - lastLogAt).coerceAtLeast(1L)
                val deltaFrames = frames - captureLastStatsLogFrameCount
                val deltaBytes = bytes - captureLastStatsLogByteCount
                captureLastStatsLogAtMs = now
                captureLastStatsLogFrameCount = frames
                captureLastStatsLogByteCount = bytes
                Log.i(
                    CAPTURE_LOG_TAG,
                    "MDM-CaptureStats active=$isCapturing frames=$frames delta_frames=$deltaFrames " +
                        "bytes=$bytes delta_bytes=$deltaBytes window_fps=${deltaFrames * 1000.0 / deltaMs} " +
                        "avg_fps=$captureAverageFps last_frame_age_ms=$captureLastFrameAgeMs " +
                        "dropped=${captureDroppedFrameCount.get()} errors=${captureErrorCount.get()} " +
                        "width=$captureWidth height=$captureHeight scale=${SCREEN_INFO.scale}"
                )
            }
        }

        fun recordCaptureDroppedFrame(reason: String) {
            val dropped = captureDroppedFrameCount.incrementAndGet()
            if (dropped <= 3 || dropped % 30 == 0L) {
                Log.w(CAPTURE_LOG_TAG, "MDM-CaptureDrop reason=$reason dropped=$dropped active=$isCapturing")
            }
        }

        fun recordCaptureError(e: Exception) {
            val errors = captureErrorCount.incrementAndGet()
            captureLastError = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
            Log.w(CAPTURE_LOG_TAG, "MDM-CaptureFrameError errors=$errors last_error=$captureLastError", e)
        }

        fun logCaptureStop(reason: String, activeBeforeStop: Boolean) {
            Log.i(
                CAPTURE_LOG_TAG,
                "MDM-CaptureStop reason=$reason active_before_stop=$activeBeforeStop " +
                    "frames=${captureFrameCount.get()} bytes=${captureByteCount.get()} " +
                    "duration_ms=$captureDurationMs last_frame_age_ms=$captureLastFrameAgeMs " +
                    "dropped=${captureDroppedFrameCount.get()} errors=${captureErrorCount.get()} " +
                    "last_error=${captureLastError ?: ""}"
            )
        }

        fun applyMdmAudioEnabled(enabled: Boolean): Boolean {
            return activeInstance?.applyMdmAudioEnabledNow(enabled) ?: false
        }
    }

    private fun currentScreenSizeForRustDesk(): Triple<Int, Int, Int> {
        if (captureSourceValue == CAPTURE_SOURCE_MDM_SCREENRECORD && mdmCaptureWidth > 0 && mdmCaptureHeight > 0) {
            return Triple(mdmCaptureWidth, mdmCaptureHeight, SCREEN_INFO.scale)
        }
        readMdmScreenrecordMetaIfRunning()?.let { meta ->
            return Triple(meta.first, meta.second, SCREEN_INFO.scale)
        }
        return Triple(SCREEN_INFO.width, SCREEN_INFO.height, SCREEN_INFO.scale)
    }

    private val logTag = "LOG_SERVICE"
    private fun newMediaProjectionCallback(projection: MediaProjection) = object : MediaProjection.Callback() {
        override fun onStop() {
            synchronized(this@MainService) {
                if (mediaProjection !== projection) {
                    Log.w(logTag, "MediaProjection callback onStop ignored for stale projection")
                    return
                }
                Log.w(
                    logTag,
                    "MediaProjection callback onStop active=$isStart frames=$captureFrames " +
                        "bytes=$captureBytes last_frame_age_ms=$captureLastFrameAgeMs"
                )
                markCaptureInactive(
                    reason = "media_projection_on_stop",
                    clearMediaProjection = true,
                    releaseVirtualDisplay = true
                )
            }
        }
    }
    private val virtualDisplayCallback = object : VirtualDisplay.Callback() {
        override fun onPaused() {
            Log.w(logTag, "VirtualDisplay callback onPaused")
            markCaptureInactive(
                reason = "virtual_display_paused",
                clearMediaProjection = false,
                releaseVirtualDisplay = false
            )
        }

        override fun onResumed() {
            Log.d(logTag, "VirtualDisplay callback onResumed")
        }

        override fun onStopped() {
            Log.w(logTag, "VirtualDisplay callback onStopped")
            markCaptureInactive(
                reason = "virtual_display_stopped",
                clearMediaProjection = false,
                releaseVirtualDisplay = true
            )
        }
    }
    private val useVP9 = false
    private val binder = LocalBinder()

    private var reuseVirtualDisplay = Build.VERSION.SDK_INT > 33

    // video
    private var mediaProjection: MediaProjection? = null
    private var surface: Surface? = null
    private val sendVP9Thread = Executors.newSingleThreadExecutor()
    private var videoEncoder: MediaCodec? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null

    // P0-fix(mdm-companion-android-9): mdm screenrecord 路径用 Annex-B H264 增量解码
    @Volatile private var mdmScreenrecordThread: Thread? = null
    @Volatile private var mdmKeepaliveThread: Thread? = null
    private var mdmDecoder: MediaCodec? = null
    private var mdmDecoderStarted = false
    @Volatile private var mdmDecoderOutputImageUnavailableLogged = false
    @Volatile private var mdmLastRgbaFrame: ByteBuffer? = null
    @Volatile private var mdmLastFrameOutputAtMs = 0L
    @Volatile private var mdmLastDecodedFramePublishedAtMs = 0L
    @Volatile private var mdmBootstrapInProgress = false
    private var mdmPendingBootstrapFrame: ByteBuffer? = null
    private var mdmKeepaliveFrameCount = 0L
    private var mdmRgbaScratch: ByteBuffer? = null

    private data class MdmFeedBootstrap(
        val nalUnits: List<ByteArray>,
        val pendingBytes: ByteArray,
        val streamOffset: Long
    )

    // audio
    private val audioRecordHandle = AudioRecordHandle(this, { isStart }, { isAudioStart })

    // notification
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationChannel: String
    private lateinit var notificationBuilder: NotificationCompat.Builder

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        Log.d(logTag,"MainService onCreate, sdk int:${Build.VERSION.SDK_INT} reuseVirtualDisplay:$reuseVirtualDisplay")
        FFI.init(this)
        HandlerThread("Service", Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
            serviceLooper = looper
            serviceHandler = Handler(looper)
        }
        updateScreenInfo(resources.configuration.orientation)
        initNotification()

        // keep the config dir same with flutter
        val prefs = applicationContext.getSharedPreferences(KEY_SHARED_PREFERENCES, FlutterActivity.MODE_PRIVATE)
        val configPath = prefs.getString(KEY_APP_DIR_CONFIG_PATH, "")?.takeIf { it.isNotBlank() }
            ?: appFlutterDir().also {
                prefs.edit().putString(KEY_APP_DIR_CONFIG_PATH, it).apply()
            }
        Log.d(logTag, "startServer configPath=$configPath")
        FFI.startServer(configPath, "")

        createForegroundNotification()
    }

    override fun onDestroy() {
        checkMediaPermission()
        if (activeInstance === this) {
            activeInstance = null
        }
        releaseScreenWakeLock("service_destroy")
        stopService(Intent(this, FloatingWindowService::class.java))
        super.onDestroy()
    }

    private var isHalfScale: Boolean? = null;
    @Synchronized
    private fun updateScreenInfo(orientation: Int) {
        var w: Int
        var h: Int
        var dpi: Int
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val m = windowManager.maximumWindowMetrics
            w = m.bounds.width()
            h = m.bounds.height()
            dpi = resources.configuration.densityDpi
        } else {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels
            h = dm.heightPixels
            dpi = dm.densityDpi
        }

        val max = max(w,h)
        val min = min(w,h)
        if (orientation == ORIENTATION_LANDSCAPE) {
            w = max
            h = min
        } else {
            w = min
            h = max
        }
        Log.d(logTag,"updateScreenInfo:w:$w,h:$h")
        var scale = 1
        if (w != 0 && h != 0) {
            if (isHalfScale == true && (w > MAX_SCREEN_SIZE || h > MAX_SCREEN_SIZE)) {
                scale = 2
                w /= scale
                h /= scale
                dpi /= scale
            }
            val screenChanged = SCREEN_INFO.width != w ||
                SCREEN_INFO.height != h ||
                SCREEN_INFO.dpi != dpi ||
                SCREEN_INFO.scale != scale
            if (screenChanged) {
                val oldWidth = SCREEN_INFO.width
                val oldHeight = SCREEN_INFO.height
                val oldDpi = SCREEN_INFO.dpi
                val oldScale = SCREEN_INFO.scale
                SCREEN_INFO.width = w
                SCREEN_INFO.height = h
                SCREEN_INFO.scale = scale
                SCREEN_INFO.dpi = dpi
                Log.i(
                    logTag,
                    "MDM-OrientationChange orientation=$orientation " +
                        "old=${oldWidth}x${oldHeight}@${oldDpi}/$oldScale " +
                        "new=${w}x${h}@${dpi}/$scale was_capturing=$isStart"
                )
                if (isStart && captureSourceValue == CAPTURE_SOURCE_MDM_SCREENRECORD) {
                    Log.i(
                        logTag,
                        "MDM-OrientationChange waiting_for_agent_screenrecord_restart " +
                            "capture=${mdmCaptureWidth}x${mdmCaptureHeight} target=${w}x${h}"
                    )
                } else if (isStart) {
                    markCaptureInactive(
                        reason = "orientation_changed",
                        clearMediaProjection = false,
                        releaseVirtualDisplay = true
                    )
                    FFI.refreshScreen()
                    startCapture()
                } else {
                    FFI.refreshScreen()
                }
            }

        }
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(logTag, "service onBind")
        return binder
    }

    inner class LocalBinder : Binder() {
        init {
            Log.d(logTag, "LocalBinder init")
        }

        fun getService(): MainService = this@MainService
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("whichService", "this service: ${Thread.currentThread()}")
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACT_INIT_MEDIA_PROJECTION_AND_SERVICE -> {
                createForegroundNotification()

                if (intent.getBooleanExtra(EXT_INIT_FROM_BOOT, false)) {
                    FFI.startService(appFlutterDir())
                }
                Log.d(logTag, "service starting: ${startId}:${Thread.currentThread()}")
                val mediaProjectionManager =
                    getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

                val projectionIntent = intent.getParcelableExtra<Intent>(EXT_MEDIA_PROJECTION_RES_INTENT)
                if (projectionIntent == null) {
                    Log.d(logTag, "getParcelableExtra intent null, invoke requestMediaProjection")
                    requestMediaProjection()
                } else {
                    try {
                        if (virtualDisplay != null || isStart) {
                            markCaptureInactive(
                                reason = "new_media_projection",
                                clearMediaProjection = false,
                                releaseVirtualDisplay = true
                            )
                        }
                        val previousProjection = mediaProjection
                        val previousCallback = mediaProjectionCallback
                        if (previousProjection != null && previousCallback != null) {
                            runCatching { previousProjection.unregisterCallback(previousCallback) }
                        }
                        if (previousProjection != null) {
                            Log.d(logTag, "Stopping previous MediaProjection before replacing it")
                            runCatching { previousProjection.stop() }
                                .onFailure { Log.w(logTag, "Previous MediaProjection stop failed", it) }
                            mediaProjection = null
                            mediaProjectionCallback = null
                            _isReady = false
                        }

                        val projection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, projectionIntent)
                        if (projection == null) {
                            _isReady = false
                            clearMediaProjectionRequest("projection_null")
                            recordCaptureError(IllegalStateException("MediaProjectionManager returned null projection"))
                            return START_NOT_STICKY
                        }

                        val callback = newMediaProjectionCallback(projection)
                        mediaProjection = projection
                        mediaProjectionCallback = callback
                        projection.registerCallback(
                            callback,
                            serviceHandler ?: Handler(Looper.getMainLooper())
                        )
                        checkMediaPermission()
                        _isReady = true
                        clearMediaProjectionRequest("projection_ready")
                        Log.d(logTag, "MediaProjection ready, starting capture")
                        if (!startCapture(projection)) {
                            Log.w(logTag, "MediaProjection ready but startCapture failed")
                        }
                    } catch (e: Exception) {
                        Log.e(logTag, "MediaProjection init failed", e)
                        recordCaptureError(e)
                        clearMediaProjectionRequest("projection_exception")
                        markCaptureInactive(
                            reason = "media_projection_init_exception",
                            clearMediaProjection = true,
                            releaseVirtualDisplay = true
                        )
                    }
                }
            }
            // mdm-no-launcher 模式: mdm-agent 拉起 service 进入后台驻留,
            // 不需要 mediaProjection 也不弹任何 UI, 仅启动 FFI 让 rust 端
            // 进入 hbbr 监听, 等远控接入
            MdmControlProvider.ACT_START_NO_PROJECTION -> {
                Log.d(logTag, "mdm start: ACT_START_NO_PROJECTION")
                createForegroundNotification()
                FFI.startService(appFlutterDir())
                if (!isStart && requiresMdmSystemScreenrecord() && useMdmSystemScreenrecord()) {
                    if (!startMdmScreenrecordCapture()) {
                        Log.w(logTag, "mdm start: managed screenrecord capture failed")
                    }
                } else if (!isStart) {
                    _isReady = false
                }
            }
        }
        return START_NOT_STICKY // don't use sticky (auto restart), the new service (from auto restart) will lose control
    }

    private fun appFlutterDir(): String = "${applicationInfo.dataDir}/app_flutter"

    private fun isMdmAudioEnabled(): Boolean {
        return applicationContext
            .getSharedPreferences(KEY_SHARED_PREFERENCES, FlutterActivity.MODE_PRIVATE)
            .getBoolean(KEY_MDM_AUDIO_ENABLED, false)
    }

    @Synchronized
    private fun applyMdmAudioEnabledNow(enabled: Boolean): Boolean {
        if (!enabled) {
            _isAudioStart = false
            audioRecordHandle.stopAudioRecorder()
            Log.i(logTag, "MDM audio disabled while service running")
            return true
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !isStart) {
            Log.i(
                logTag,
                "MDM audio enabled but capture is not ready sdk=${Build.VERSION.SDK_INT} isStart=$isStart mediaProjection=${mediaProjection != null}"
            )
            return false
        }
        if (_isAudioStart) {
            return true
        }
        return if (audioRecordHandle.createAudioRecorder(false, mediaProjection) && audioRecordHandle.startAudioRecorder()) {
            _isAudioStart = true
            Log.i(logTag, "MDM audio recorder started while capture is running sdk=${Build.VERSION.SDK_INT}")
            true
        } else {
            _isAudioStart = false
            Log.w(logTag, "MDM audio recorder start failed while capture is running")
            false
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenInfo(newConfig.orientation)
    }

    private fun requestMediaProjection() {
        if (requiresMdmSystemScreenrecord()) {
            Log.w(logTag, "MediaProjection request blocked: managed screenrecord is mandatory on sdk=${Build.VERSION.SDK_INT}")
            if (!switchToMdmSystemScreenrecordCapture("projection_request_blocked", false)) {
                Log.w(logTag, "managed screenrecord source is not ready while blocking MediaProjection")
            }
            return
        }
        if (mediaProjectionRequestInFlight) {
            Log.d(logTag, "MediaProjection request already in flight")
            return
        }
        markMediaProjectionRequestStarted("main_service")
        val intent = Intent(this, PermissionRequestTransparentActivity::class.java).apply {
            action = ACT_REQUEST_MEDIA_PROJECTION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            clearMediaProjectionRequest("main_service_start_failed")
            throw e
        }
    }

    @SuppressLint("WrongConstant")
    private fun createSurface(): Surface? {
        return if (useVP9) {
            // TODO
            null
        } else {
            Log.d(logTag, "ImageReader.newInstance:INFO:$SCREEN_INFO")
            imageReader =
                ImageReader.newInstance(
                    SCREEN_INFO.width,
                    SCREEN_INFO.height,
                    PixelFormat.RGBA_8888,
                    4
                ).apply {
                    setOnImageAvailableListener({ imageReader: ImageReader ->
                        try {
                            // If not call acquireLatestImage, listener will not be called again
                            imageReader.acquireLatestImage().use { image ->
                                if (image == null) {
                                    recordCaptureDroppedFrame("null_image")
                                    return@setOnImageAvailableListener
                                }
                                if (!isStart) {
                                    recordCaptureDroppedFrame("capture_not_started")
                                    return@setOnImageAvailableListener
                                }
                                val planes = image.planes
                                val buffer = planes[0].buffer
                                buffer.rewind()
                                val byteCount = buffer.remaining()
                                FFI.onVideoFrameUpdate(buffer)
                                recordCaptureFrame(byteCount)
                            }
                        } catch (e: java.lang.Exception) {
                            recordCaptureError(e)
                        }
                    }, serviceHandler)
                }
            Log.d(logTag, "ImageReader.setOnImageAvailableListener done")
            imageReader?.surface
        }
    }

    fun onVoiceCallStarted(): Boolean {
        return audioRecordHandle.onVoiceCallStarted(mediaProjection)
    }

    fun onVoiceCallClosed(): Boolean {
        return audioRecordHandle.onVoiceCallClosed(mediaProjection)
    }

    @Synchronized
    fun startCapture(projection: MediaProjection? = mediaProjection): Boolean {
        if (isStart) {
            return isReady
        }
        keepScreenInteractive("start_capture")

        updateScreenInfo(resources.configuration.orientation)
        Log.d(logTag, "Start Capture")

        // P0-fix(mdm-companion-android-9): 优先检测 mdm-agent 提供的 screenrecord
        // 文件 (system uid 1000 跑 shell screenrecord 写 h264 到公共目录).
        // Annex-B H264 增量解码避开 MediaProjection + VirtualDisplaySurface bug.
        if (useMdmSystemScreenrecord()) {
            Log.i(logTag, "startCapture: routing to mdm screenrecord path (Android 9 bypass)")
            if (startMdmScreenrecordCapture(projection)) {
                return true
            }
            Log.w(logTag, "mdm screenrecord path failed")
        }
        if (requiresMdmSystemScreenrecord()) {
            Log.e(logTag, "MediaProjection fallback blocked on managed sdk=${Build.VERSION.SDK_INT}")
            return false
        }

        val activeProjection = projection
        if (activeProjection == null) {
            Log.w(logTag, "startCapture fail,mediaProjection is null")
            return false
        }

        surface = createSurface()
        if (surface == null) {
            Log.w(logTag, "startCapture fail,surface is null")
            markCaptureInactive(
                reason = "start_capture_surface_null",
                clearMediaProjection = false,
                releaseVirtualDisplay = true
            )
            return false
        }

        _isStart = true
        captureSourceValue = CAPTURE_SOURCE_MEDIA_PROJECTION
        resetCaptureStats()
        MainActivity.rdClipboardManager?.setCaptureStarted(_isStart)

        val recorderStarted = if (useVP9) {
            startVP9VideoRecorder(activeProjection)
        } else {
            startRawVideoRecorder(activeProjection)
        }
        if (!recorderStarted || !isStart) {
            Log.w(logTag, "startCapture failed or capture stopped immediately: recorderStarted=$recorderStarted isStart=$isStart")
            markCaptureInactive(
                reason = "start_capture_failed_or_stopped",
                clearMediaProjection = false,
                releaseVirtualDisplay = true
            )
            return false
        }

        startAudioForCapture(activeProjection, CAPTURE_SOURCE_MEDIA_PROJECTION)
        checkMediaPermission()
        FFI.setFrameRawEnable("video",true)
        return true
    }

    private fun startAudioForCapture(activeProjection: MediaProjection?, source: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !isMdmAudioEnabled()) {
            _isAudioStart = false
            Log.d(logTag, "audio recorder disabled by MDM or unsupported sdk=${Build.VERSION.SDK_INT} source=$source")
            return false
        }
        return if (audioRecordHandle.createAudioRecorder(false, activeProjection) && audioRecordHandle.startAudioRecorder()) {
            _isAudioStart = true
            Log.i(logTag, "MDM audio recorder start requested sdk=${Build.VERSION.SDK_INT} source=$source")
            true
        } else {
            _isAudioStart = false
            Log.d(logTag, "createAudioRecorder/startAudioRecorder fail source=$source")
            false
        }
    }

    @Synchronized
    fun stopCapture() {
        markCaptureInactive(
            reason = "stop_capture",
            clearMediaProjection = false,
            releaseVirtualDisplay = true
        )
    }

    @Synchronized
    private fun markCaptureInactive(
        reason: String,
        clearMediaProjection: Boolean,
        releaseVirtualDisplay: Boolean
    ) {
        Log.d(logTag, "Stop Capture reason=$reason clearMediaProjection=$clearMediaProjection releaseVirtualDisplay=$releaseVirtualDisplay")
        logCaptureStop(reason, _isStart)
        FFI.setFrameRawEnable("video",false)
        _isStart = false
        captureSourceValue = CAPTURE_SOURCE_NONE
        mdmCaptureWidth = 0
        mdmCaptureHeight = 0
        MainActivity.rdClipboardManager?.setCaptureStarted(_isStart)
        // release video
        virtualDisplay?.let { display ->
            val shouldReleaseVirtualDisplay = releaseVirtualDisplay || !reuseVirtualDisplay
            if (reuseVirtualDisplay && !shouldReleaseVirtualDisplay) {
                // The virtual display video projection can be paused by calling `setSurface(null)`.
                // https://developer.android.com/reference/android/hardware/display/VirtualDisplay.Callback
                // https://learn.microsoft.com/en-us/dotnet/api/android.hardware.display.virtualdisplay.callback.onpaused?view=net-android-34.0
                Log.d(logTag, "Pause VirtualDisplay via setSurface(null) reason=$reason")
                runCatching { display.setSurface(null) }
            } else if (shouldReleaseVirtualDisplay) {
                Log.d(
                    logTag,
                    "Release VirtualDisplay reason=$reason requested=$releaseVirtualDisplay " +
                        "reuseVirtualDisplay=$reuseVirtualDisplay"
                )
                runCatching { display.release() }
            }
        }
        virtualDisplay = null
        // suface needs to be release after `imageReader.close()` to imageReader access released surface
        // https://github.com/rustdesk/rustdesk/issues/4118#issuecomment-1515666629
        runCatching { imageReader?.close() }
        imageReader = null
        videoEncoder?.let {
            runCatching { it.signalEndOfInputStream() }
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        videoEncoder = null
        stopMdmScreenrecordCapture()
        // suface needs to be release after `imageReader.close()` to imageReader access released surface
        // https://github.com/rustdesk/rustdesk/issues/4118#issuecomment-1515666629
        runCatching { surface?.release() }
        surface = null

        // release audio
        _isAudioStart = false
        audioRecordHandle.tryReleaseAudio()
        if (clearMediaProjection) {
            val callback = mediaProjectionCallback
            mediaProjection?.let { projection ->
                if (callback != null) {
                    runCatching { projection.unregisterCallback(callback) }
                }
            }
            mediaProjectionCallback = null
            mediaProjection = null
            _isReady = false
        }
        releaseScreenWakeLock("stop_capture")
    }

    fun destroy() {
        Log.d(logTag, "destroy service")
        _isReady = false
        _isAudioStart = false

        stopCapture()

        if (reuseVirtualDisplay) {
            virtualDisplay?.release()
            virtualDisplay = null
        }

        val callback = mediaProjectionCallback
        mediaProjection?.let { projection ->
            if (callback != null) {
                runCatching { projection.unregisterCallback(callback) }
            }
        }
        mediaProjectionCallback = null
        mediaProjection = null
        checkMediaPermission()
        stopForeground(true)
        stopService(Intent(this, FloatingWindowService::class.java))
        stopSelf()
    }

    fun checkMediaPermission(): Boolean {
        Handler(Looper.getMainLooper()).post {
            MainActivity.flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "media", "value" to isReady.toString())
            )
        }
        Handler(Looper.getMainLooper()).post {
            MainActivity.flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "input", "value" to InputService.isOpen.toString())
            )
        }
        return isReady
    }

    private fun startRawVideoRecorder(mp: MediaProjection): Boolean {
        Log.d(logTag, "startRawVideoRecorder,screen info:$SCREEN_INFO")
        if (surface == null) {
            Log.d(logTag, "startRawVideoRecorder failed,surface is null")
            return false
        }
        return createOrSetVirtualDisplay(mp, surface!!)
    }

    private fun startVP9VideoRecorder(mp: MediaProjection): Boolean {
        createMediaCodec()
        videoEncoder?.let {
            surface = it.createInputSurface()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                surface!!.setFrameRate(1F, FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
            it.setCallback(cb)
            it.start()
            return createOrSetVirtualDisplay(mp, surface!!)
        }
        return false
    }

    // https://github.com/bk138/droidVNC-NG/blob/b79af62db5a1c08ed94e6a91464859ffed6f4e97/app/src/main/java/net/christianbeier/droidvnc_ng/MediaProjectionService.java#L250
    // Reuse virtualDisplay if it exists, to avoid media projection confirmation dialog every connection.
    private fun createOrSetVirtualDisplay(mp: MediaProjection, s: Surface): Boolean {
        try {
            if (virtualDisplay != null && !isStart) {
                runCatching { virtualDisplay?.release() }
                virtualDisplay = null
            }
            virtualDisplay?.let {
                it.resize(SCREEN_INFO.width, SCREEN_INFO.height, SCREEN_INFO.dpi)
                it.setSurface(s)
            } ?: let {
                virtualDisplay = mp.createVirtualDisplay(
                    "RustDeskVD",
                    SCREEN_INFO.width, SCREEN_INFO.height, SCREEN_INFO.dpi, VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    s, virtualDisplayCallback, serviceHandler
                )
            }
            return virtualDisplay != null
        } catch (e: SecurityException) {
            Log.w(logTag, "createOrSetVirtualDisplay: got SecurityException, re-requesting confirmation");
            // This initiates a prompt dialog for the user to confirm screen projection.
            requestMediaProjection()
            return false
        }
    }

    private val cb: MediaCodec.Callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            codec.getOutputBuffer(index)?.let { buf ->
                sendVP9Thread.execute {
                    val byteArray = ByteArray(buf.limit())
                    buf.get(byteArray)
                    // sendVp9(byteArray)
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(logTag, "MediaCodec.Callback error:$e")
        }
    }

    private fun createMediaCodec() {
        Log.d(logTag, "MediaFormat.MIMETYPE_VIDEO_VP9 :$MIME_TYPE")
        videoEncoder = MediaCodec.createEncoderByType(MIME_TYPE)
        val mFormat =
            MediaFormat.createVideoFormat(MIME_TYPE, SCREEN_INFO.width, SCREEN_INFO.height)
        mFormat.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_KEY_BIT_RATE)
        mFormat.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_KEY_FRAME_RATE)
        mFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        mFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
        try {
            videoEncoder!!.configure(mFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.e(logTag, "mEncoder.configure fail!")
        }
    }

    private fun initNotification() {
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationChannel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "RustDesk"
            val channelName = "RustDesk Service"
            val channel = NotificationChannel(
                channelId,
                channelName, NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "RustDesk Service Channel"
            }
            channel.lightColor = Color.BLUE
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            notificationManager.createNotificationChannel(channel)
            channelId
        } else {
            ""
        }
        notificationBuilder = NotificationCompat.Builder(this, notificationChannel)
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun createForegroundNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra("type", type)
        }
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getActivity(this, 0, intent, FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(this, 0, intent, FLAG_UPDATE_CURRENT)
        }
        val notification = notificationBuilder
            .setOngoing(true)
            .setSmallIcon(R.mipmap.ic_stat_logo)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentTitle(DEFAULT_NOTIFY_TITLE)
            .setContentText(translate(DEFAULT_NOTIFY_TEXT))
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setColor(ContextCompat.getColor(this, R.color.primary))
            .setWhen(System.currentTimeMillis())
            .build()
        startForeground(DEFAULT_NOTIFY_ID, notification)
    }

    private fun loginRequestNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        val notification = notificationBuilder
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle(translate("Do you accept?"))
            .setContentText("$type:$username-$peerId")
            // .setStyle(MediaStyle().setShowActionsInCompactView(0, 1))
            // .addAction(R.drawable.check_blue, "check", genLoginRequestPendingIntent(true))
            // .addAction(R.drawable.close_red, "close", genLoginRequestPendingIntent(false))
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
    }

    private fun onClientAuthorizedNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        cancelNotification(clientID)
        val notification = notificationBuilder
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle("$type ${translate("Established")}")
            .setContentText("$username - $peerId")
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
    }

    private fun voiceCallRequestNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        val notification = notificationBuilder
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle(translate("Do you accept?"))
            .setContentText("$type:$username-$peerId")
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
    }

    private fun getClientNotifyID(clientID: Int): Int {
        return clientID + NOTIFY_ID_OFFSET
    }

    fun cancelNotification(clientID: Int) {
        notificationManager.cancel(getClientNotifyID(clientID))
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun genLoginRequestPendingIntent(res: Boolean): PendingIntent {
        val intent = Intent(this, MainService::class.java).apply {
            action = ACT_LOGIN_REQ_NOTIFY
            putExtra(EXT_LOGIN_REQ_NOTIFY, res)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getService(this, 111, intent, FLAG_IMMUTABLE)
        } else {
            PendingIntent.getService(this, 111, intent, FLAG_UPDATE_CURRENT)
        }
    }

    private fun setTextNotification(_title: String?, _text: String?) {
        val title = _title ?: DEFAULT_NOTIFY_TITLE
        val text = _text ?: translate(DEFAULT_NOTIFY_TEXT)
        val notification = notificationBuilder
            .clearActions()
            .setStyle(null)
            .setContentTitle(title)
            .setContentText(text)
            .build()
        notificationManager.notify(DEFAULT_NOTIFY_ID, notification)
    }

    // ─── P0-fix(mdm-companion-android-9): mdm screenrecord 视频读取路径 ───
    /**
     * 检测 mdm-agent (uid 1000, system app) 是否在跑 shell `screenrecord` 写
     * h264 到 /sdcard/Android/data/com.decard.mdm.agent/files/system_screen.h264.
     * 如果是, flutter_hbb 改用 Annex-B H264 增量解码读这个 h264 文件 (不走 MediaProjection
     * 流程), 彻底绕开 Android 9 SurfaceFlinger.getUniqueId() bug.
     *
     * 检测: 读 mdm 私有外部存储的 meta JSON + 验证文件存在 + 检查 screenrecord 进程.
     */
    private fun useMdmSystemScreenrecord(): Boolean {
        return try {
            val metaFile = java.io.File("/sdcard/Android/data/com.decard.mdm.agent/files/system_screen_meta.json")
            if (!metaFile.exists()) return false
            val screenFile = java.io.File("/sdcard/Android/data/com.decard.mdm.agent/files/system_screen.h264")
            val readyDeadline = System.currentTimeMillis() + 3000L
            while (System.currentTimeMillis() < readyDeadline) {
                val proc = Runtime.getRuntime().exec(arrayOf("pidof", "screenrecord"))
                val running = proc.waitFor() == 0
                if (!running) return false
                if (screenFile.exists() && screenFile.length() >= 1024L) {
                    return true
                }
                Thread.sleep(100)
            }
            Log.w(logTag, "mdm screenrecord file not ready after wait: exists=${screenFile.exists()} size=${screenFile.length()}")
            false
        } catch (e: Throwable) {
            false
        }
    }

    private fun requiresMdmSystemScreenrecord(): Boolean {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q
    }

    /**
     * P0-fix: 启动 MediaCodec 解码器增量读取 mdm screenrecord Annex-B h264 文件,
     * 把解码出的 RGBA frame 通过现有 FFI.onVideoFrameUpdate 推给 rust 端.
     * 解码器使用 ByteBuffer 输出, 不再创建 ImageReader/output Surface,
     * 避免 Android 9/MTK 上额外 VirtualDisplaySurface/BufferQueue 引发画面卡死.
     */
    @Synchronized
    private fun switchToMdmSystemScreenrecordCapture(reason: String, forceRestart: Boolean): Boolean {
        val targetMeta = readMdmScreenrecordMeta()
        if (captureSourceValue == CAPTURE_SOURCE_MDM_SCREENRECORD && isStart && !forceRestart) {
            val currentWidth = mdmCaptureWidth.takeIf { it > 0 } ?: SCREEN_INFO.width
            val currentHeight = mdmCaptureHeight.takeIf { it > 0 } ?: SCREEN_INFO.height
            if (currentWidth == targetMeta.first && currentHeight == targetMeta.second) {
                return true
            }
            Log.i(
                logTag,
                "switch to mdm screenrecord requires restart reason=$reason current=${currentWidth}x$currentHeight target=${targetMeta.first}x${targetMeta.second}"
            )
        } else if (captureSourceValue == CAPTURE_SOURCE_MDM_SCREENRECORD && isStart) {
            Log.i(logTag, "force restart mdm screenrecord capture reason=$reason")
        }
        if (captureSourceValue == CAPTURE_SOURCE_MDM_SCREENRECORD && isStart) {
            markCaptureInactive(
                reason = "switch_to_mdm_screenrecord_${reason}_restart",
                clearMediaProjection = false,
                releaseVirtualDisplay = true
            )
        }
        if (!useMdmSystemScreenrecord()) {
            Log.w(logTag, "switch to mdm screenrecord skipped: source not ready reason=$reason")
            return false
        }
        val projection = mediaProjection
        if (isStart) {
            markCaptureInactive(
                reason = "switch_to_mdm_screenrecord_$reason",
                clearMediaProjection = false,
                releaseVirtualDisplay = true
            )
        }
        val switched = startMdmScreenrecordCapture(projection)
        if (!switched) {
            Log.w(logTag, "switch to mdm screenrecord failed; MediaProjection fallback is disabled")
        }
        return switched
    }

    @Synchronized
    private fun startMdmScreenrecordCapture(activeProjection: MediaProjection? = mediaProjection): Boolean {
        try {
            val screenFile = java.io.File("/sdcard/Android/data/com.decard.mdm.agent/files/system_screen.h264")
            val readyDeadline = System.currentTimeMillis() + 3000L
            while (System.currentTimeMillis() < readyDeadline && (!screenFile.exists() || screenFile.length() < 1024L)) {
                Thread.sleep(100)
            }
            if (!screenFile.exists() || screenFile.length() < 1024L) {
                Log.w(logTag, "mdm screenrecord file not ready")
                return false
            }

            val screenMeta = readMdmScreenrecordMeta()
            val frameWidth = screenMeta.first
            val frameHeight = screenMeta.second
            val activeFeed = mdmScreenrecordThread
            if (
                _isStart &&
                captureSourceValue == CAPTURE_SOURCE_MDM_SCREENRECORD &&
                mdmDecoder != null &&
                activeFeed?.isAlive == true &&
                mdmCaptureWidth == frameWidth &&
                mdmCaptureHeight == frameHeight
            ) {
                Log.i(logTag, "mdm screenrecord capture already active: ${frameWidth}x${frameHeight}")
                return true
            }
            if (mdmDecoder != null || activeFeed != null || mdmKeepaliveThread != null) {
                Log.w(logTag, "mdm screenrecord stale capture resources detected; releasing before restart")
                stopMdmScreenrecordCapture()
            }
            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, frameWidth, frameHeight)
            videoFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
            videoFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            mdmDecoder = createMdmScreenrecordDecoder()
            mdmDecoderStarted = false
            mdmDecoder!!.configure(videoFormat, null, null, 0)
            mdmDecoder!!.start()
            mdmDecoderStarted = true
            mdmDecoderOutputImageUnavailableLogged = false
            mdmLastRgbaFrame = null
            mdmLastFrameOutputAtMs = 0L
            mdmLastDecodedFramePublishedAtMs = 0L
            mdmBootstrapInProgress = false
            mdmPendingBootstrapFrame = null
            mdmKeepaliveFrameCount = 0L
            mdmRgbaScratch = null

            _isStart = true
            _isReady = true
            captureSourceValue = CAPTURE_SOURCE_MDM_SCREENRECORD
            mdmCaptureWidth = frameWidth
            mdmCaptureHeight = frameHeight
            FFI.refreshScreen()
            resetCaptureStats()
            MainActivity.rdClipboardManager?.setCaptureStarted(_isStart)
            FFI.setFrameRawEnable("video", true)
            startMdmScreenrecordFeedThread(screenFile, mdmDecoder!!)
            startMdmKeepaliveThread(mdmDecoder!!)
            startAudioForCapture(activeProjection, CAPTURE_SOURCE_MDM_SCREENRECORD)
            checkMediaPermission()
            Log.i(logTag, "mdm screenrecord capture started: ${screenFile.absolutePath}")
            return true
        } catch (e: Throwable) {
            Log.e(logTag, "mdm screenrecord capture failed: ${e.message}", e)
            stopMdmScreenrecordCapture()
            FFI.setFrameRawEnable("video", false)
            _isStart = false
            _isReady = false
            captureSourceValue = CAPTURE_SOURCE_NONE
            mdmCaptureWidth = 0
            mdmCaptureHeight = 0
            return false
        }
    }

    private fun readMdmScreenrecordMeta(): Pair<Int, Int> {
        return readMdmScreenrecordMetaFromFile(logFailure = true)
            ?: Pair(SCREEN_INFO.width, SCREEN_INFO.height)
    }

    private fun createMdmScreenrecordDecoder(): MediaCodec {
        return try {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
                Log.i(logTag, "mdm screenrecord decoder selected: ${it.name}")
            }
        } catch (e: Throwable) {
            Log.w(logTag, "default AVC decoder unavailable; falling back to software: ${e.message}")
            MediaCodec.createByCodecName(MDM_SOFTWARE_AVC_DECODER).also {
                Log.i(logTag, "mdm screenrecord decoder selected: $MDM_SOFTWARE_AVC_DECODER")
            }
        }
    }

    private fun readMdmScreenrecordMetaIfRunning(): Pair<Int, Int>? {
        return if (isMdmScreenrecordProcessRunning()) {
            readMdmScreenrecordMetaFromFile(logFailure = false)
        } else {
            null
        }
    }

    private fun readMdmScreenrecordMetaFromFile(logFailure: Boolean): Pair<Int, Int>? {
        return try {
            val metaFile = java.io.File("/sdcard/Android/data/com.decard.mdm.agent/files/system_screen_meta.json")
            if (!metaFile.exists()) return null
            val json = JSONObject(metaFile.readText())
            val frameWidth = json.optInt("width", SCREEN_INFO.width).coerceAtLeast(1)
            val frameHeight = json.optInt("height", SCREEN_INFO.height).coerceAtLeast(1)
            Pair(frameWidth, frameHeight)
        } catch (e: Throwable) {
            if (logFailure) {
                Log.w(logTag, "mdm screenrecord meta read failed: ${e.message}")
            }
            null
        }
    }

    private fun isMdmScreenrecordProcessRunning(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("pidof", "screenrecord"))
            proc.waitFor() == 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun yuv420ImageToRgba(image: Image): ByteBuffer {
        val cropRect = image.cropRect
        val imageWidth = cropRect.width()
        val imageHeight = cropRect.height()
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val requiredBytes = imageWidth * imageHeight * 4
        val rgbaBuffer = mdmRgbaScratch
            ?.takeIf { it.capacity() == requiredBytes }
            ?: ByteBuffer.allocateDirect(requiredBytes).also { mdmRgbaScratch = it }
        rgbaBuffer.clear()

        val yOffset = yBuffer.position() + cropRect.top * yPlane.rowStride + cropRect.left * yPlane.pixelStride
        val chromaTop = cropRect.top / 2
        val chromaLeft = cropRect.left / 2
        val uOffset = uBuffer.position() + chromaTop * uPlane.rowStride + chromaLeft * uPlane.pixelStride
        val vOffset = vBuffer.position() + chromaTop * vPlane.rowStride + chromaLeft * vPlane.pixelStride

        if (FFI.convertYuv420ToRgba(
                yBuffer,
                uBuffer,
                vBuffer,
                rgbaBuffer,
                imageWidth,
                imageHeight,
                yOffset,
                uOffset,
                vOffset,
                yPlane.rowStride,
                uPlane.rowStride,
                vPlane.rowStride,
                uPlane.pixelStride,
                vPlane.pixelStride
            )
        ) {
            rgbaBuffer.position(0)
            rgbaBuffer.limit(requiredBytes)
            return rgbaBuffer
        }

        for (rowIndex in 0 until imageHeight) {
            val uvRowIndex = rowIndex / 2
            for (columnIndex in 0 until imageWidth) {
                val uvColumnIndex = columnIndex / 2
                val yIndex = yOffset + rowIndex * yPlane.rowStride + columnIndex * yPlane.pixelStride
                val uIndex = uOffset + uvRowIndex * uPlane.rowStride + uvColumnIndex * uPlane.pixelStride
                val vIndex = vOffset + uvRowIndex * vPlane.rowStride + uvColumnIndex * vPlane.pixelStride

                val yValue = yBuffer.get(yIndex).toInt() and 0xff
                val uValue = uBuffer.get(uIndex).toInt() and 0xff
                val vValue = vBuffer.get(vIndex).toInt() and 0xff

                val luma = (yValue - 16).coerceAtLeast(0)
                val chromaBlue = uValue - 128
                val chromaRed = vValue - 128
                val red = clampColor((298 * luma + 409 * chromaRed + 128) shr 8)
                val green = clampColor((298 * luma - 100 * chromaBlue - 208 * chromaRed + 128) shr 8)
                val blue = clampColor((298 * luma + 516 * chromaBlue + 128) shr 8)

                rgbaBuffer.put(red.toByte())
                rgbaBuffer.put(green.toByte())
                rgbaBuffer.put(blue.toByte())
                rgbaBuffer.put(0xff.toByte())
            }
        }
        rgbaBuffer.flip()
        return rgbaBuffer
    }

    private fun clampColor(value: Int): Int {
        return value.coerceIn(0, 255)
    }

    private fun startMdmScreenrecordFeedThread(screenFile: java.io.File, decoder: MediaCodec) {
        val feedThread = Thread({
            var pendingBytes = ByteArray(0)
            var streamOffset = 0L
            var presentationTimeUs = 0L
            var suppressOutputThroughUs: Long? = null
            var stream: java.io.RandomAccessFile? = null
            try {
                while (isStart && mdmDecoder === decoder) {
                    drainMdmDecoderOutput(decoder, suppressOutputThroughUs)
                    if (!screenFile.exists()) {
                        Thread.sleep(20)
                        continue
                    }
                    val currentLength = screenFile.length()
                    if (stream == null ||
                        currentLength < streamOffset
                    ) {
                        val openReason = if (stream == null) "initial" else "truncated"
                        runCatching { stream?.close() }
                        stream = java.io.RandomAccessFile(screenFile, "r")
                        val bootstrap = readMdmFeedBootstrap(stream, currentLength)
                        streamOffset = bootstrap.streamOffset
                        pendingBytes = bootstrap.pendingBytes
                        Log.i(
                            logTag,
                            "mdm h264 feed opened: size=$currentLength modified=${screenFile.lastModified()} " +
                                "reason=$openReason bootstrap_nals=${bootstrap.nalUnits.size} " +
                                "tail_bytes=${bootstrap.pendingBytes.size}"
                        )
                        mdmBootstrapInProgress = true
                        mdmPendingBootstrapFrame = null
                        for (nalUnit in bootstrap.nalUnits) {
                            while (isStart && mdmDecoder === decoder) {
                                drainMdmDecoderOutput(decoder, Long.MAX_VALUE)
                                if (queueMdmDecoderInput(decoder, nalUnit, presentationTimeUs)) {
                                    presentationTimeUs += 33_333L
                                    break
                                }
                                Thread.sleep(5)
                            }
                        }
                        suppressOutputThroughUs = (presentationTimeUs - 33_333L).coerceAtLeast(0L)
                        repeat(10) {
                            drainMdmDecoderOutput(decoder, suppressOutputThroughUs)
                            Thread.sleep(5L)
                        }
                        publishMdmBootstrapFrame(openReason, bootstrap.nalUnits.size)
                        mdmBootstrapInProgress = false
                    }
                    val activeStream = stream ?: continue
                    val availableBytes = currentLength - streamOffset
                    if (availableBytes <= 0L) {
                        Thread.sleep(20)
                        continue
                    }
                    val readSize = min(availableBytes, 64L * 1024L).toInt()
                    val chunk = ByteArray(readSize)
                    activeStream.seek(streamOffset)
                    val bytesRead = activeStream.read(chunk)
                    if (bytesRead <= 0) {
                        Thread.sleep(20)
                        continue
                    }
                    streamOffset += bytesRead.toLong()
                    val combinedBytes = ByteArray(pendingBytes.size + bytesRead)
                    System.arraycopy(pendingBytes, 0, combinedBytes, 0, pendingBytes.size)
                    System.arraycopy(chunk, 0, combinedBytes, pendingBytes.size, bytesRead)
                    val parsedNalUnits = extractCompleteAnnexBNalUnits(combinedBytes)
                    pendingBytes = parsedNalUnits.second
                    for (nalUnit in parsedNalUnits.first) {
                        while (isStart && mdmDecoder === decoder) {
                            drainMdmDecoderOutput(decoder, suppressOutputThroughUs)
                            if (queueMdmDecoderInput(decoder, nalUnit, presentationTimeUs)) {
                                presentationTimeUs += 33_333L
                                break
                            }
                            Thread.sleep(5)
                        }
                    }
                }
                drainMdmDecoderOutput(decoder, suppressOutputThroughUs)
            } catch (e: Throwable) {
                if (isStart) {
                    Log.w(logTag, "mdm h264 feed error: ${e.message}", e)
                    recordCaptureError(Exception(e))
                }
            } finally {
                runCatching { stream?.close() }
            }
        }, "mdm-screenrecord-feed")
        mdmScreenrecordThread = feedThread
        feedThread.start()
    }

    private fun startMdmKeepaliveThread(decoder: MediaCodec) {
        val keepaliveThread = Thread({
            while (isStart && mdmDecoder === decoder) {
                emitMdmKeepaliveFrameIfNeeded()
                Thread.sleep(20L)
            }
        }, "mdm-screenrecord-keepalive")
        mdmKeepaliveThread = keepaliveThread
        keepaliveThread.start()
    }

    private fun readMdmFeedBootstrap(
        stream: java.io.RandomAccessFile,
        currentLength: Long
    ): MdmFeedBootstrap {
        if (currentLength <= 0L) {
            return MdmFeedBootstrap(emptyList(), ByteArray(0), 0L)
        }
        val readLength = min(currentLength, MDM_BOOTSTRAP_MAX_BYTES.toLong()).toInt()
        val startOffset = currentLength - readLength
        val bytes = ByteArray(readLength)
        stream.seek(startOffset)
        stream.readFully(bytes)
        val parsed = extractCompleteAnnexBNalUnits(bytes)
        val nalUnits = parsed.first
        val idrIndex = nalUnits.indexOfLast { mdmH264NalType(it) == 5 }
        if (idrIndex < 0) {
            return MdmFeedBootstrap(emptyList(), parsed.second, currentLength)
        }
        val spsIndex = (idrIndex downTo 0).firstOrNull { mdmH264NalType(nalUnits[it]) == 7 }
        val ppsIndex = (idrIndex downTo 0).firstOrNull { mdmH264NalType(nalUnits[it]) == 8 }
        val selected = ArrayList<ByteArray>()
        spsIndex?.let { selected.add(nalUnits[it]) }
        ppsIndex?.let { selected.add(nalUnits[it]) }
        selected.addAll(nalUnits.subList(idrIndex, nalUnits.size))
        return MdmFeedBootstrap(selected, parsed.second, currentLength)
    }

    private fun mdmH264NalType(nalUnit: ByteArray): Int {
        val startOffset = findAnnexBStartCode(nalUnit, 0)
        if (startOffset < 0) return -1
        val headerOffset = startOffset + annexBStartCodeLength(nalUnit, startOffset)
        if (headerOffset >= nalUnit.size) return -1
        return nalUnit[headerOffset].toInt() and 0x1f
    }

    private fun queueMdmDecoderInput(decoder: MediaCodec, nalUnit: ByteArray, presentationTimeUs: Long): Boolean {
        val inputIndex = decoder.dequeueInputBuffer(5000)
        if (inputIndex < 0) return false
        val inputBuffer = decoder.getInputBuffer(inputIndex)
        if (inputBuffer == null) {
            decoder.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
            return true
        }
        inputBuffer.clear()
        if (nalUnit.size > inputBuffer.remaining()) {
            Log.w(logTag, "mdm h264 nal too large: size=${nalUnit.size} capacity=${inputBuffer.remaining()}")
            recordCaptureDroppedFrame("mdm_nal_too_large")
            decoder.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
            return true
        }
        inputBuffer.put(nalUnit)
        decoder.queueInputBuffer(inputIndex, 0, nalUnit.size, presentationTimeUs, 0)
        return true
    }

    private fun drainMdmDecoderOutput(decoder: MediaCodec, suppressOutputThroughUs: Long? = null) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
            when {
                outputIndex >= 0 -> {
                    try {
                        if (bufferInfo.size > 0 && isStart) {
                            val now = System.currentTimeMillis()
                            val suppressCurrentOutput = suppressOutputThroughUs != null &&
                                bufferInfo.presentationTimeUs <= suppressOutputThroughUs
                            if (
                                !suppressCurrentOutput &&
                                mdmLastDecodedFramePublishedAtMs > 0L &&
                                now - mdmLastDecodedFramePublishedAtMs < MDM_MIN_PUBLISH_INTERVAL_MS
                            ) {
                                continue
                            }
                            val rgbaBuffer = mdmDecoderOutputToRgba(decoder, outputIndex, bufferInfo)
                            if (rgbaBuffer != null) {
                                rgbaBuffer.rewind()
                                val byteCount = rgbaBuffer.remaining()
                                val frame = copyMdmRgbaFrame(rgbaBuffer)
                                if (suppressCurrentOutput) {
                                    mdmPendingBootstrapFrame = frame
                                } else {
                                    FFI.onVideoFrameUpdate(rgbaBuffer)
                                    mdmLastRgbaFrame = frame
                                    mdmLastFrameOutputAtMs = now
                                    mdmLastDecodedFramePublishedAtMs = now
                                    recordCaptureFrame(byteCount)
                                }
                            } else {
                                recordCaptureDroppedFrame("mdm_decoder_empty_output")
                            }
                        }
                    } catch (e: Throwable) {
                        recordCaptureError(Exception(e))
                    } finally {
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.i(logTag, "mdm decoder output format changed: ${decoder.outputFormat}")
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> return
            }
        }
    }

    private fun publishMdmBootstrapFrame(reason: String, bootstrapNalCount: Int) {
        val frame = mdmPendingBootstrapFrame?.duplicate()?.apply { rewind() }
        mdmPendingBootstrapFrame = null
        if (frame == null) {
            Log.w(logTag, "mdm bootstrap produced no frame reason=$reason nals=$bootstrapNalCount")
            return
        }
        val byteCount = frame.remaining()
        FFI.onVideoFrameUpdate(frame)
        mdmLastRgbaFrame = frame.asReadOnlyBuffer().apply { rewind() }
        val now = System.currentTimeMillis()
        mdmLastFrameOutputAtMs = now
        mdmLastDecodedFramePublishedAtMs = now
        recordCaptureFrame(byteCount)
        Log.i(logTag, "mdm bootstrap published latest frame only reason=$reason nals=$bootstrapNalCount")
    }

    private fun copyMdmRgbaFrame(source: ByteBuffer): ByteBuffer {
        val input = source.duplicate().apply { rewind() }
        return ByteBuffer.allocateDirect(input.remaining()).apply {
            put(input)
            flip()
        }.asReadOnlyBuffer()
    }

    private fun emitMdmKeepaliveFrameIfNeeded() {
        if (mdmBootstrapInProgress) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - mdmLastFrameOutputAtMs < MDM_KEEPALIVE_FRAME_INTERVAL_MS) {
            return
        }
        val frame = mdmLastRgbaFrame?.duplicate()?.apply { rewind() } ?: return
        val byteCount = frame.remaining()
        FFI.onVideoFrameUpdate(frame)
        mdmLastFrameOutputAtMs = now
        mdmKeepaliveFrameCount += 1
        captureDuplicateFrameCount.incrementAndGet()
        if (mdmKeepaliveFrameCount <= 3L || mdmKeepaliveFrameCount % 100L == 0L) {
            Log.i(
                logTag,
                "MDM-CaptureKeepalive duplicate_frames=$mdmKeepaliveFrameCount " +
                    "interval_ms=$MDM_KEEPALIVE_FRAME_INTERVAL_MS bytes=$byteCount"
            )
        }
    }

    private fun mdmDecoderOutputToRgba(
        decoder: MediaCodec,
        outputIndex: Int,
        bufferInfo: MediaCodec.BufferInfo
    ): ByteBuffer? {
        try {
            decoder.getOutputImage(outputIndex)?.use { image ->
                return yuv420ImageToRgba(image)
            }
        } catch (e: Throwable) {
            if (!mdmDecoderOutputImageUnavailableLogged) {
                mdmDecoderOutputImageUnavailableLogged = true
                Log.w(logTag, "mdm decoder output unavailable: ${e.message}")
            }
        }
        val outputBuffer = decoder.getOutputBuffer(outputIndex) ?: return null
        return yuv420BufferToRgba(outputBuffer, bufferInfo, decoder.outputFormat)
    }

    private fun yuv420BufferToRgba(
        outputBuffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
        format: MediaFormat
    ): ByteBuffer {
        val formatWidth = mediaFormatInt(format, MediaFormat.KEY_WIDTH, mdmCaptureWidth.takeIf { it > 0 } ?: SCREEN_INFO.width)
        val formatHeight = mediaFormatInt(format, MediaFormat.KEY_HEIGHT, mdmCaptureHeight.takeIf { it > 0 } ?: SCREEN_INFO.height)
        val cropLeft = mediaFormatInt(format, "crop-left", 0).coerceAtLeast(0)
        val cropTop = mediaFormatInt(format, "crop-top", 0).coerceAtLeast(0)
        val cropRight = mediaFormatInt(format, "crop-right", formatWidth - 1).coerceIn(cropLeft, formatWidth - 1)
        val cropBottom = mediaFormatInt(format, "crop-bottom", formatHeight - 1).coerceIn(cropTop, formatHeight - 1)
        val imageWidth = cropRight - cropLeft + 1
        val imageHeight = cropBottom - cropTop + 1
        val stride = mediaFormatInt(format, MediaFormat.KEY_STRIDE, formatWidth).takeIf { it > 0 } ?: formatWidth
        val sliceHeight = mediaFormatInt(format, MediaFormat.KEY_SLICE_HEIGHT, formatHeight).takeIf { it > 0 } ?: formatHeight
        val colorFormat = mediaFormatInt(
            format,
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        )
        val rawBuffer = outputBuffer.duplicate().apply {
            position(0)
            limit(capacity())
        }
        val rgbaBuffer = ByteBuffer.allocateDirect(imageWidth * imageHeight * 4)
        val baseOffset = bufferInfo.offset.coerceAtLeast(0)
        val lumaPlaneSize = stride * sliceHeight
        val semiPlanar = colorFormat != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar &&
            colorFormat != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar
        val planarChromaStride = (stride + 1) / 2
        val planarChromaPlaneSize = planarChromaStride * ((sliceHeight + 1) / 2)

        for (rowIndex in 0 until imageHeight) {
            val sourceY = cropTop + rowIndex
            val sourceUvY = sourceY / 2
            for (columnIndex in 0 until imageWidth) {
                val sourceX = cropLeft + columnIndex
                val sourceUvX = sourceX / 2
                val yIndex = baseOffset + sourceY * stride + sourceX
                val yValue = getByteOrDefault(rawBuffer, yIndex, 16)
                val uValue: Int
                val vValue: Int
                if (semiPlanar) {
                    val uvIndex = baseOffset + lumaPlaneSize + sourceUvY * stride + sourceUvX * 2
                    uValue = getByteOrDefault(rawBuffer, uvIndex, 128)
                    vValue = getByteOrDefault(rawBuffer, uvIndex + 1, 128)
                } else {
                    val uIndex = baseOffset + lumaPlaneSize + sourceUvY * planarChromaStride + sourceUvX
                    val vIndex = baseOffset + lumaPlaneSize + planarChromaPlaneSize + sourceUvY * planarChromaStride + sourceUvX
                    uValue = getByteOrDefault(rawBuffer, uIndex, 128)
                    vValue = getByteOrDefault(rawBuffer, vIndex, 128)
                }
                putYuvPixelAsRgba(rgbaBuffer, yValue, uValue, vValue)
            }
        }
        rgbaBuffer.flip()
        return rgbaBuffer
    }

    private fun mediaFormatInt(format: MediaFormat, key: String, defaultValue: Int): Int {
        return try {
            if (format.containsKey(key)) format.getInteger(key) else defaultValue
        } catch (_: Throwable) {
            defaultValue
        }
    }

    private fun getByteOrDefault(buffer: ByteBuffer, index: Int, defaultValue: Int): Int {
        return if (index >= 0 && index < buffer.limit()) {
            buffer.get(index).toInt() and 0xff
        } else {
            defaultValue
        }
    }

    private fun putYuvPixelAsRgba(rgbaBuffer: ByteBuffer, yValue: Int, uValue: Int, vValue: Int) {
        val luma = (yValue - 16).coerceAtLeast(0)
        val chromaBlue = uValue - 128
        val chromaRed = vValue - 128
        val red = clampColor((298 * luma + 409 * chromaRed + 128) shr 8)
        val green = clampColor((298 * luma - 100 * chromaBlue - 208 * chromaRed + 128) shr 8)
        val blue = clampColor((298 * luma + 516 * chromaBlue + 128) shr 8)
        rgbaBuffer.put(red.toByte())
        rgbaBuffer.put(green.toByte())
        rgbaBuffer.put(blue.toByte())
        rgbaBuffer.put(0xff.toByte())
    }

    private fun extractCompleteAnnexBNalUnits(bytes: ByteArray): Pair<List<ByteArray>, ByteArray> {
        val startOffsets = mutableListOf<Int>()
        var searchOffset = 0
        while (searchOffset < bytes.size - 3) {
            val startOffset = findAnnexBStartCode(bytes, searchOffset)
            if (startOffset < 0) break
            startOffsets.add(startOffset)
            searchOffset = startOffset + annexBStartCodeLength(bytes, startOffset)
        }
        if (startOffsets.size < 2) {
            val keepOffset = if (startOffsets.isNotEmpty()) {
                startOffsets.first()
            } else {
                max(0, bytes.size - 4)
            }
            return Pair(emptyList(), bytes.copyOfRange(keepOffset, bytes.size))
        }

        val nalUnits = mutableListOf<ByteArray>()
        for (offsetIndex in 0 until startOffsets.size - 1) {
            val startOffset = startOffsets[offsetIndex]
            val endOffset = startOffsets[offsetIndex + 1]
            if (endOffset > startOffset) {
                nalUnits.add(bytes.copyOfRange(startOffset, endOffset))
            }
        }
        val remainderOffset = startOffsets.last()
        return Pair(nalUnits, bytes.copyOfRange(remainderOffset, bytes.size))
    }

    private fun findAnnexBStartCode(bytes: ByteArray, fromOffset: Int): Int {
        var offset = fromOffset
        while (offset <= bytes.size - 3) {
            if (bytes[offset].toInt() == 0 && bytes[offset + 1].toInt() == 0) {
                if (bytes[offset + 2].toInt() == 1) return offset
                if (offset <= bytes.size - 4 && bytes[offset + 2].toInt() == 0 && bytes[offset + 3].toInt() == 1) {
                    return offset
                }
            }
            offset += 1
        }
        return -1
    }

    private fun annexBStartCodeLength(bytes: ByteArray, startOffset: Int): Int {
        return if (startOffset <= bytes.size - 4 && bytes[startOffset + 2].toInt() == 0) 4 else 3
    }

    /**
     * 释放 mdm screenrecord 视频读取相关资源.
     */
    private fun stopMdmScreenrecordCapture() {
        val feedThread = mdmScreenrecordThread
        val keepaliveThread = mdmKeepaliveThread
        val decoder = mdmDecoder
        val decoderStarted = mdmDecoderStarted
        mdmScreenrecordThread = null
        mdmKeepaliveThread = null
        mdmDecoder = null
        mdmDecoderStarted = false
        if (feedThread != null && feedThread !== Thread.currentThread()) {
            try {
                feedThread.join(2000L)
                if (feedThread.isAlive) {
                    Log.w(logTag, "mdm screenrecord feed thread still alive before decoder release")
                }
            } catch (_: Throwable) {}
        }
        if (keepaliveThread != null && keepaliveThread !== Thread.currentThread()) {
            try {
                keepaliveThread.join(1000L)
            } catch (_: Throwable) {}
        }
        mdmLastRgbaFrame = null
        mdmLastFrameOutputAtMs = 0L
        mdmLastDecodedFramePublishedAtMs = 0L
        mdmBootstrapInProgress = false
        mdmPendingBootstrapFrame = null
        mdmKeepaliveFrameCount = 0L
        mdmRgbaScratch = null
        if (decoderStarted) {
            try {
                decoder?.stop()
            } catch (e: Throwable) { Log.w(logTag, "mdm decoder stop: ${e.message}") }
        }
        try {
            decoder?.release()
        } catch (e: Throwable) { Log.w(logTag, "mdm decoder release: ${e.message}") }
        mdmDecoderOutputImageUnavailableLogged = false
        Log.i(logTag, "mdm screenrecord capture released")
    }

}
