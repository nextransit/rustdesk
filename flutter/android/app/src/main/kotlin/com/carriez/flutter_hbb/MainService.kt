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
import android.net.wifi.WifiManager
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
        // RustDesk Web has already converted the rendered canvas point into the
        // Android logical display coordinate space. Keep the JNI boundary as a
        // pure transport boundary: a second capture-size conversion here makes
        // the same pointer jump whenever the managed capture size changes.
        val mappedX = x
        val mappedY = y
        if (MdmInputFallback.isAvailable(applicationContext)) {
            Log.i(
                logTag,
                "MDM-InputDispatch pointer_route=provider_fallback kind=$kind mask=$mask " +
                    "raw_x=$x raw_y=$y x=$mappedX y=$mappedY"
            )
            if (MdmInputFallback.pointer(applicationContext, kind, mask, mappedX, mappedY)) {
                return
            }
            Log.w(logTag, "MDM-InputDispatch provider_fallback_failed kind=$kind mask=$mask")
        }
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
        Log.w(logTag, "MDM-InputDispatch pointer_route=unavailable kind=$kind mask=$mask")
    }

    @Keep
    @RequiresApi(Build.VERSION_CODES.N)
    fun rustKeyEventInput(input: ByteArray) {
        if (MdmInputFallback.isAvailable(applicationContext) &&
            MdmInputFallback.key(applicationContext, input)
        ) {
            Log.i(logTag, "MDM-InputDispatch key_route=provider_fallback bytes=${input.size}")
            return
        }
        val inputService = InputService.ctx
        if (inputService != null) {
            Log.i(logTag, "MDM-InputDispatch key_route=accessibility bytes=${input.size}")
            inputService.onKeyEvent(input)
            return
        }
        Log.w(logTag, "MDM-InputDispatch key_route=unavailable bytes=${input.size}")
    }

    @Keep
    fun rustGetByName(name: String): String {
        return when (name) {
            "screen_size" -> {
                JSONObject().apply {
                    put("width", SCREEN_INFO.width)
                    put("height", SCREEN_INFO.height)
                    put("scale", SCREEN_INFO.scale)
                }.toString()
            }
            "capture_size" -> {
                val captureSize = currentCaptureSizeForRustDesk()
                JSONObject().apply {
                    put("width", captureSize.first)
                    put("height", captureSize.second)
                    put("scale", captureSize.third)
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
                                } else {
                                    republishMdmFrameForNewConnection("add_connection")
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
    private val wifiManager: WifiManager by lazy {
        applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    @Suppress("DEPRECATION")
    private val remoteSessionWifiLock: WifiManager.WifiLock by lazy {
        wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "rustdesk:remote-session"
        ).apply { setReferenceCounted(false) }
    }

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

    private fun acquireRemoteSessionWifiLock(reason: String) {
        runCatching {
            if (!remoteSessionWifiLock.isHeld) {
                remoteSessionWifiLock.acquire()
                Log.i(logTag, "MDM-WifiLock acquired mode=high_perf reason=$reason")
            }
        }.onFailure {
            Log.w(logTag, "MDM-WifiLock acquire failed reason=$reason error=${it.message}")
        }
    }

    private fun releaseRemoteSessionWifiLock(reason: String) {
        runCatching {
            if (remoteSessionWifiLock.isHeld) {
                remoteSessionWifiLock.release()
                Log.i(logTag, "MDM-WifiLock released reason=$reason")
            }
        }.onFailure {
            Log.w(logTag, "MDM-WifiLock release failed reason=$reason error=${it.message}")
        }
    }

    companion object {
        private const val CAPTURE_STATS_LOG_INTERVAL_MS = 5_000L
        private const val MDM_MAX_PUBLISH_FPS = 15
        private const val MDM_MIN_PUBLISH_INTERVAL_MS = 1000L / MDM_MAX_PUBLISH_FPS
        // Duplicate frames are only a connection keepalive. Publishing the same
        // frame at 20 fps hides a stalled decoder from health metrics and repeats
        // transient black frames. Real decoded frames carry the configured rate;
        // static screens need only a low-rate keepalive.
        private const val MDM_KEEPALIVE_FRAME_INTERVAL_MS = 1_000L
        private const val MDM_DISPLAY_REFRESH_SETTLE_MS = 250L
        private const val MDM_BOOTSTRAP_MAX_BYTES = 2 * 1024 * 1024
        private const val MDM_BOOTSTRAP_TRAILING_NAL_STABLE_MS = 200L
        private const val MDM_BOOTSTRAP_DRAIN_ATTEMPTS = 20
        private const val MDM_BOOTSTRAP_NO_FRAME_RECOVERY_MS = 8_000L
        private const val MDM_DECODE_STALL_RECOVERY_MS = 4_000L
        private const val MDM_DECODE_STALL_MIN_INPUT_DELTA = 4L
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
        private val mdmDecoderInputCount = AtomicLong(0)
        private val mdmDecoderOutputCount = AtomicLong(0)
        private val mdmDecoderInputWaitCount = AtomicLong(0)
        private val mdmRgbaConversionNanos = AtomicLong(0)
        private val mdmJniSubmitNanos = AtomicLong(0)
        @Volatile private var captureStartedAtMs = 0L
        @Volatile private var captureLastFrameAtMs = 0L
        @Volatile private var captureLastStatsLogAtMs = 0L
        @Volatile private var captureLastStatsLogFrameCount = 0L
        @Volatile private var captureLastStatsLogByteCount = 0L
        @Volatile private var captureLastError: String? = null
        @Volatile private var captureSourceValue = CAPTURE_SOURCE_NONE
        @Volatile private var mdmCaptureWidth = 0
        @Volatile private var mdmCaptureHeight = 0
        @Volatile private var mdmCaptureDisplayId = android.view.Display.DEFAULT_DISPLAY
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
        val captureDisplayId: Int
            get() = if (captureSourceValue == CAPTURE_SOURCE_MDM_SCREENRECORD) mdmCaptureDisplayId else android.view.Display.DEFAULT_DISPLAY
        val mediaProjectionRequestInFlight: Boolean
            get() {
                val startedAt = mediaProjectionRequestStartedAtMs
                val ageMs = System.currentTimeMillis() - startedAt
                return startedAt > 0L && ageMs in 0L..MEDIA_PROJECTION_REQUEST_TTL_MS
            }

        fun switchToMdmSystemScreenrecord(reason: String, forceRestart: Boolean = false): Boolean {
            return activeInstance?.switchToMdmSystemScreenrecordCapture(reason, forceRestart) ?: false
        }

        fun stopManagedCapture(): Boolean {
            activeInstance?.stopCapture()
            return true
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
            captureDuplicateFrameCount.set(0)
            captureErrorCount.set(0)
            mdmDecoderInputCount.set(0)
            mdmDecoderOutputCount.set(0)
            mdmDecoderInputWaitCount.set(0)
            mdmRgbaConversionNanos.set(0)
            mdmJniSubmitNanos.set(0)
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
                        "decoder_in=${mdmDecoderInputCount.get()} decoder_out=${mdmDecoderOutputCount.get()} " +
                        "decoder_input_wait=${mdmDecoderInputWaitCount.get()} " +
                        "rgba_avg_ms=${averageStageMs(mdmRgbaConversionNanos.get(), mdmDecoderOutputCount.get())} " +
                        "jni_avg_ms=${averageStageMs(mdmJniSubmitNanos.get(), frames)} " +
                        "width=$captureWidth height=$captureHeight scale=${SCREEN_INFO.scale}"
                )
            }
        }

        private fun averageStageMs(totalNanos: Long, count: Long): Double {
            return if (count > 0L) totalNanos / count / 1_000_000.0 else 0.0
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

    private fun currentCaptureSizeForRustDesk(): Triple<Int, Int, Int> {
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
    @Volatile private var mdmDecoderInputCountAtLastOutput = 0L
    @Volatile private var mdmBootstrapInProgress = false
    private var mdmPendingBootstrapFrame: ByteBuffer? = null
    private var mdmKeepaliveFrameCount = 0L
    private var mdmRgbaScratch: ByteBuffer? = null
    private var mdmLastFrameScratch: ByteBuffer? = null
    private var mdmPendingFrameScratch: ByteBuffer? = null

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
        releaseRemoteSessionWifiLock("service_destroy")
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
                    // F-06 (HIGH): 刷新 scrap 端 SCREEN_SIZE 缓存。当屏幕旋转且使用
                    // mdm screenrecord 时，updateScreenInfo 已更新 SCREEN_INFO（flutter_hbb
                    // 端），但 scrap 端的 SCREEN_SIZE（Rust lazy_static Mutex）仍为旧值。
                    // 不调用 FFI.refreshScreen() 会导致：
                    //   1. scrap Display::width()/height() 返回旧方向值
                    //   2. video_service CapturerInfo.display_width/height 仍为旧方向
                    //   3. 控制端（web-console）基于错误的 display_width/height 发送坐标
                    //   4. RustDeskInputProvider 用 getRealSize() 物理尺寸校验，
                    //      坐标范围不匹配 → 注入位置错误（横屏导航栏点不中）
                    // refreshScreen() 触发 Display::refresh_size() + ANDROID_REFRESH_EPOCH
                    // 递增 → video_service 主循环检测到 epoch 变化 → bail!("SWITCH")
                    // → 重新创建 Capturer 并广播新 display_width/height 给控制端。
                    FFI.refreshScreen()
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
        acquireRemoteSessionWifiLock("media_projection_capture")
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
        mdmCaptureDisplayId = android.view.Display.DEFAULT_DISPLAY
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
        releaseRemoteSessionWifiLock("stop_capture")
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
     * 检测 mdm-agent (uid 1000, system app) 提供的 managed Annex-B H264 是否就绪.
     * 主屏由 shell screenrecord 产出, 副屏由 agent 进程内 SurfaceControl + MediaCodec
     * 产出, 两者共用同一文件和 meta 协议.
     */
    private fun useMdmSystemScreenrecord(expectedDisplayId: Int? = null): Boolean {
        return try {
            val metaFile = java.io.File("/sdcard/Android/data/com.decard.mdm.agent/files/system_screen_meta.json")
            if (!metaFile.exists()) return false
            val screenFile = java.io.File("/sdcard/Android/data/com.decard.mdm.agent/files/system_screen.h264")
            val readyDeadline = System.currentTimeMillis() + 3000L
            var lastState = "missing"
            var lastDisplayId = android.view.Display.DEFAULT_DISPLAY
            var processRunning = false
            while (System.currentTimeMillis() < readyDeadline) {
                val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull()
                if (meta == null) {
                    Thread.sleep(100L)
                    continue
                }
                lastState = meta.optString("state", "unknown")
                lastDisplayId = meta.optInt(
                    "displayId",
                    android.view.Display.DEFAULT_DISPLAY
                )
                processRunning = lastDisplayId != android.view.Display.DEFAULT_DISPLAY ||
                    isMdmScreenrecordProcessRunning()
                val displayMatches = expectedDisplayId == null ||
                    lastDisplayId == expectedDisplayId
                if (lastState == "active" &&
                    displayMatches &&
                    processRunning &&
                    screenFile.exists() &&
                    screenFile.length() >= 1024L &&
                    hasCompleteMdmAvcBootstrap(screenFile)
                ) {
                    return true
                }
                Thread.sleep(100L)
            }
            Log.w(
                logTag,
                "mdm managed capture not ready after wait: state=$lastState " +
                    "displayId=$lastDisplayId expectedDisplayId=$expectedDisplayId " +
                    "producerRunning=$processRunning exists=${screenFile.exists()} " +
                    "size=${screenFile.length()}"
            )
            false
        } catch (e: Throwable) {
            Log.w(logTag, "mdm managed capture readiness check failed: ${e.message}")
            false
        }
    }

    private fun hasCompleteMdmAvcBootstrap(screenFile: java.io.File): Boolean {
        return try {
            java.io.RandomAccessFile(screenFile, "r").use { stream ->
                val bootstrap = readMdmFeedBootstrap(
                    stream,
                    stream.length(),
                    allowStableTrailingNal = true
                )
                val nalTypes = bootstrap.nalUnits.map(::mdmH264NalType)
                7 in nalTypes && 8 in nalTypes && 5 in nalTypes
            }
        } catch (_: Throwable) {
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
        val targetDisplayId = readMdmScreenrecordDisplayId()
        if (captureSourceValue == CAPTURE_SOURCE_MDM_SCREENRECORD && isStart && !forceRestart) {
            val currentWidth = mdmCaptureWidth.takeIf { it > 0 } ?: SCREEN_INFO.width
            val currentHeight = mdmCaptureHeight.takeIf { it > 0 } ?: SCREEN_INFO.height
            if (currentWidth == targetMeta.first &&
                currentHeight == targetMeta.second &&
                mdmCaptureDisplayId == targetDisplayId
            ) {
                return true
            }
            Log.i(
                logTag,
                "switch to mdm screenrecord requires restart reason=$reason " +
                    "current=${currentWidth}x$currentHeight displayId=$mdmCaptureDisplayId " +
                    "target=${targetMeta.first}x${targetMeta.second} displayId=$targetDisplayId"
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
        if (!useMdmSystemScreenrecord(targetDisplayId)) {
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
            val frameDisplayId = readMdmScreenrecordDisplayId()
            val activeFeed = mdmScreenrecordThread
            if (
                _isStart &&
                captureSourceValue == CAPTURE_SOURCE_MDM_SCREENRECORD &&
                mdmDecoder != null &&
                activeFeed?.isAlive == true &&
                mdmCaptureWidth == frameWidth &&
                mdmCaptureHeight == frameHeight &&
                mdmCaptureDisplayId == frameDisplayId
            ) {
                Log.i(logTag, "mdm screenrecord capture already active: ${frameWidth}x${frameHeight} displayId=$frameDisplayId")
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
            mdmDecoderStarted = false
            mdmDecoder = createAndStartMdmScreenrecordDecoder(videoFormat, frameWidth, frameHeight)
            mdmDecoderStarted = true
            mdmDecoderOutputImageUnavailableLogged = false
            mdmLastRgbaFrame = null
            mdmLastFrameOutputAtMs = 0L
            mdmLastDecodedFramePublishedAtMs = 0L
            mdmDecoderInputCountAtLastOutput = 0L
            mdmBootstrapInProgress = false
            mdmPendingBootstrapFrame = null
            mdmKeepaliveFrameCount = 0L
            mdmRgbaScratch = null
            mdmLastFrameScratch = null
            mdmPendingFrameScratch = null

            _isStart = true
            _isReady = true
            captureSourceValue = CAPTURE_SOURCE_MDM_SCREENRECORD
            mdmCaptureWidth = frameWidth
            mdmCaptureHeight = frameHeight
            mdmCaptureDisplayId = frameDisplayId
            FFI.refreshScreen()
            serviceHandler?.postDelayed({
                if (
                    _isStart &&
                    captureSourceValue == CAPTURE_SOURCE_MDM_SCREENRECORD &&
                    mdmCaptureWidth == frameWidth &&
                    mdmCaptureHeight == frameHeight
                ) {
                    FFI.refreshScreen()
                    Log.i(
                        logTag,
                        "MDM-CaptureDisplayRefresh reason=settled dimensions=${frameWidth}x${frameHeight}"
                    )
                }
            }, MDM_DISPLAY_REFRESH_SETTLE_MS)
            resetCaptureStats()
            MainActivity.rdClipboardManager?.setCaptureStarted(_isStart)
            FFI.setFrameRawEnable("video", true)
            startMdmScreenrecordFeedThread(screenFile, mdmDecoder!!)
            startMdmKeepaliveThread(mdmDecoder!!)
            startAudioForCapture(activeProjection, CAPTURE_SOURCE_MDM_SCREENRECORD)
            checkMediaPermission()
            acquireRemoteSessionWifiLock("mdm_screenrecord_capture")
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
            mdmCaptureDisplayId = android.view.Display.DEFAULT_DISPLAY
            return false
        }
    }

    /**
     * Rebuild only the Annex-B H.264 decoder/feed path while keeping the active
     * RustDesk session, raw-frame buffer, audio recorder and last RGBA frame.
     *
     * Android 9 MTK decoders can hold several access units on a static screen.
     * The old watchdog treated that as a capture failure and called the full
     * switchToMdmSystemScreenrecordCapture(forceRestart=true) path. That path
     * disables video, tears down audio and clears the cached frame, creating the
     * exact five-second Dashboard interruption it was meant to repair.
     *
     * VIDEO_RAW intentionally retains the last frame, so Rust continues sending
     * duplicate keepalive frames while this decoder-only recovery runs.
     */
    @Synchronized
    private fun restartMdmScreenrecordDecoder(reason: String): Boolean {
        if (!_isStart || captureSourceValue != CAPTURE_SOURCE_MDM_SCREENRECORD) {
            Log.w(logTag, "MDM-DecoderRecovery skipped reason=$reason capture_inactive")
            return false
        }

        val screenFile = java.io.File(
            "/sdcard/Android/data/com.decard.mdm.agent/files/system_screen.h264"
        )
        if (!screenFile.exists() || screenFile.length() < 1024L) {
            Log.w(
                logTag,
                "MDM-DecoderRecovery skipped reason=$reason source_unavailable " +
                    "exists=${screenFile.exists()} size=${screenFile.length()}"
            )
            return false
        }

        val oldFeedThread = mdmScreenrecordThread
        val oldKeepaliveThread = mdmKeepaliveThread
        val oldDecoder = mdmDecoder
        val oldDecoderStarted = mdmDecoderStarted
        val cachedFrameAvailable = mdmLastRgbaFrame != null
        val startedAtMs = System.currentTimeMillis()

        mdmScreenrecordThread = null
        mdmKeepaliveThread = null
        mdmDecoder = null
        mdmDecoderStarted = false

        if (oldFeedThread != null && oldFeedThread !== Thread.currentThread()) {
            try {
                oldFeedThread.join(1_500L)
                if (oldFeedThread.isAlive) {
                    Log.w(logTag, "MDM-DecoderRecovery old feed still alive reason=$reason")
                }
            } catch (_: Throwable) {}
        }
        if (oldKeepaliveThread != null && oldKeepaliveThread !== Thread.currentThread()) {
            try {
                oldKeepaliveThread.join(1_000L)
            } catch (_: Throwable) {}
        }
        if (oldDecoderStarted) {
            runCatching { oldDecoder?.stop() }
                .onFailure { Log.w(logTag, "MDM-DecoderRecovery stop failed: ${it.message}") }
        }
        runCatching { oldDecoder?.release() }
            .onFailure { Log.w(logTag, "MDM-DecoderRecovery release failed: ${it.message}") }

        return try {
            val (frameWidth, frameHeight) = readMdmScreenrecordMeta()
            val videoFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                frameWidth,
                frameHeight
            ).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
            }
            val decoder = createAndStartMdmScreenrecordDecoder(
                videoFormat,
                frameWidth,
                frameHeight
            )
            mdmDecoder = decoder
            mdmDecoderStarted = true
            mdmDecoderOutputImageUnavailableLogged = false
            mdmDecoderInputCountAtLastOutput = mdmDecoderInputCount.get()
            // Give the replacement decoder a full watchdog window to consume its
            // bootstrap access units. The cached RGBA frame remains live meanwhile.
            mdmLastDecodedFramePublishedAtMs = System.currentTimeMillis()
            mdmBootstrapInProgress = false
            mdmPendingBootstrapFrame = null
            mdmCaptureWidth = frameWidth
            mdmCaptureHeight = frameHeight
            startMdmScreenrecordFeedThread(screenFile, decoder)
            startMdmKeepaliveThread(decoder)
            FFI.refreshScreen()
            Log.i(
                logTag,
                "MDM-DecoderRecovery completed reason=$reason " +
                    "elapsed_ms=${System.currentTimeMillis() - startedAtMs} " +
                    "cached_frame=$cachedFrameAvailable dimensions=${frameWidth}x$frameHeight"
            )
            true
        } catch (e: Throwable) {
            Log.e(logTag, "MDM-DecoderRecovery failed reason=$reason: ${e.message}", e)
            mdmDecoder = null
            mdmDecoderStarted = false
            false
        }
    }

    private fun readMdmScreenrecordMeta(): Pair<Int, Int> {
        return readMdmScreenrecordMetaFromFile(logFailure = true)
            ?: Pair(SCREEN_INFO.width, SCREEN_INFO.height)
    }

    private fun readMdmScreenrecordDisplayId(): Int {
        return try {
            val metaFile = java.io.File("/sdcard/Android/data/com.decard.mdm.agent/files/system_screen_meta.json")
            if (!metaFile.exists()) android.view.Display.DEFAULT_DISPLAY
            else JSONObject(metaFile.readText()).optInt("displayId", android.view.Display.DEFAULT_DISPLAY)
        } catch (_: Throwable) {
            android.view.Display.DEFAULT_DISPLAY
        }
    }

    private fun readMdmScreenrecordGeneration(): Long {
        return try {
            val metaFile = java.io.File("/sdcard/Android/data/com.decard.mdm.agent/files/system_screen_meta.json")
            if (!metaFile.exists()) -1L else JSONObject(metaFile.readText()).optLong("generation", -1L)
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun createAndStartMdmScreenrecordDecoder(
        videoFormat: MediaFormat,
        frameWidth: Int,
        frameHeight: Int
    ): MediaCodec {
        val hardwareCandidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .asSequence()
            .filter { !it.isEncoder }
            .filter { codecInfo ->
                codecInfo.supportedTypes.any {
                    it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
                }
            }
            .filterNot(::isMdmSoftwareCodec)
            .filter { codecInfo ->
                runCatching {
                    val capabilities = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    capabilities.videoCapabilities.isSizeSupported(frameWidth, frameHeight)
                }.getOrDefault(false)
            }
            .sortedBy { codecInfo ->
                when {
                    codecInfo.name.startsWith("OMX.MTK.", ignoreCase = true) -> 0
                    codecInfo.name.startsWith("OMX.qcom.", ignoreCase = true) -> 1
                    else -> 2
                }
            }
            .toList()

        for (codecInfo in hardwareCandidates) {
            val decoder = runCatching { MediaCodec.createByCodecName(codecInfo.name) }
                .onFailure {
                    Log.w(logTag, "mdm hardware AVC decoder create failed name=${codecInfo.name}: ${it.message}")
                }
                .getOrNull() ?: continue
            try {
                decoder.configure(videoFormat, null, null, 0)
                decoder.start()
                Log.i(logTag, "mdm screenrecord hardware decoder selected: ${decoder.name}")
                return decoder
            } catch (e: Throwable) {
                Log.w(logTag, "mdm hardware AVC decoder start failed name=${codecInfo.name}: ${e.message}")
                runCatching { decoder.stop() }
                runCatching { decoder.release() }
            }
        }

        val softwareDecoder = try {
            MediaCodec.createByCodecName(MDM_SOFTWARE_AVC_DECODER)
        } catch (e: Throwable) {
            Log.w(logTag, "software AVC decoder unavailable; falling back to default: ${e.message}")
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        }
        softwareDecoder.configure(videoFormat, null, null, 0)
        softwareDecoder.start()
        Log.w(logTag, "mdm screenrecord software decoder selected: ${softwareDecoder.name}")
        return softwareDecoder
    }

    private fun isMdmSoftwareCodec(codecInfo: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return codecInfo.isSoftwareOnly
        }
        val name = codecInfo.name.lowercase()
        return name.startsWith("omx.google.") ||
            name.startsWith("c2.android.") ||
            name.startsWith("omx.ffmpeg.")
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
            var streamGeneration = -1L
            var lastGenerationCheckAtMs = 0L
            var lastBootstrapWaitLogAtMs = 0L
            var lastBootstrapCandidateLength = -1L
            var bootstrapCandidateStableSinceMs = 0L
            try {
                while (isStart && mdmDecoder === decoder) {
                    drainMdmDecoderOutput(decoder, suppressOutputThroughUs)
                    if (!screenFile.exists()) {
                        runCatching { stream?.close() }
                        stream = null
                        streamOffset = 0L
                        pendingBytes = ByteArray(0)
                        streamGeneration = -1L
                        Thread.sleep(20)
                        continue
                    }
                    val currentLength = screenFile.length()
                    val now = System.currentTimeMillis()
                    val currentGeneration = if (now - lastGenerationCheckAtMs >= 250L) {
                        lastGenerationCheckAtMs = now
                        readMdmScreenrecordGeneration()
                    } else {
                        streamGeneration
                    }
                    val generationChanged = stream != null &&
                        currentGeneration >= 0L &&
                        streamGeneration >= 0L &&
                        currentGeneration != streamGeneration
                    if (stream == null ||
                        currentLength < streamOffset ||
                        generationChanged
                    ) {
                        val openReason = when {
                            stream == null -> "initial"
                            generationChanged -> "generation_changed"
                            else -> "truncated"
                        }
                        runCatching { stream?.close() }
                        stream = java.io.RandomAccessFile(screenFile, "r")
                        streamGeneration = currentGeneration
                        var bootstrap = readMdmFeedBootstrap(stream, currentLength)
                        if (bootstrap.nalUnits.isEmpty()) {
                            val candidateNow = System.currentTimeMillis()
                            if (currentLength != lastBootstrapCandidateLength) {
                                lastBootstrapCandidateLength = currentLength
                                bootstrapCandidateStableSinceMs = candidateNow
                            } else if (
                                candidateNow - bootstrapCandidateStableSinceMs >=
                                    MDM_BOOTSTRAP_TRAILING_NAL_STABLE_MS
                            ) {
                                bootstrap = readMdmFeedBootstrap(
                                    stream,
                                    currentLength,
                                    allowStableTrailingNal = true
                                )
                                if (bootstrap.nalUnits.isNotEmpty()) {
                                    Log.i(
                                        logTag,
                                        "mdm h264 bootstrap accepted stable trailing NAL " +
                                            "size=$currentLength generation=$currentGeneration reason=$openReason"
                                    )
                                }
                            }
                        }
                        streamOffset = bootstrap.streamOffset
                        pendingBytes = bootstrap.pendingBytes
                        if (bootstrap.nalUnits.isEmpty()) {
                            mdmBootstrapInProgress = true
                            mdmPendingBootstrapFrame = null
                            val waitNow = System.currentTimeMillis()
                            if (waitNow - lastBootstrapWaitLogAtMs >= 1_000L) {
                                lastBootstrapWaitLogAtMs = waitNow
                                Log.w(
                                    logTag,
                                    "mdm h264 bootstrap unavailable; waiting for fresh screenrecord generation " +
                                        "size=$currentLength generation=$currentGeneration reason=$openReason"
                                )
                            }
                            runCatching { stream?.close() }
                            stream = null
                            streamOffset = 0L
                            pendingBytes = ByteArray(0)
                            Thread.sleep(100L)
                            continue
                        }
                        lastBootstrapCandidateLength = -1L
                        bootstrapCandidateStableSinceMs = 0L
                        Log.i(
                            logTag,
                            "mdm h264 feed opened: size=$currentLength modified=${screenFile.lastModified()} " +
                                "reason=$openReason bootstrap_nals=${bootstrap.nalUnits.size} " +
                                "tail_bytes=${bootstrap.pendingBytes.size}"
                        )
                        mdmBootstrapInProgress = true
                        mdmPendingBootstrapFrame = null
                        val bootstrapAccessUnits = groupMdmAvcAccessUnits(bootstrap.nalUnits)
                        for (accessUnit in bootstrapAccessUnits) {
                            while (isStart && mdmDecoder === decoder) {
                                drainMdmDecoderOutput(decoder, Long.MAX_VALUE)
                                if (queueMdmDecoderInput(decoder, accessUnit, presentationTimeUs)) {
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
                        // MTK Android 9 decoders frequently retain a lone bootstrap IDR
                        // until the next access unit arrives. A static screen may not
                        // produce that next unit for tens of seconds, leaving the browser
                        // with audio but no first video frame. Re-queue the last complete
                        // access unit once as a decoder flush; all bootstrap outputs remain
                        // suppressed and only the latest decoded frame is published below.
                        if (mdmPendingBootstrapFrame == null && bootstrapAccessUnits.isNotEmpty()) {
                            val flushAccessUnit = bootstrapAccessUnits.last()
                            while (isStart && mdmDecoder === decoder) {
                                drainMdmDecoderOutput(decoder, suppressOutputThroughUs)
                                if (queueMdmDecoderInput(decoder, flushAccessUnit, presentationTimeUs)) {
                                    suppressOutputThroughUs = presentationTimeUs
                                    presentationTimeUs += 33_333L
                                    Log.i(
                                        logTag,
                                        "mdm bootstrap queued duplicate access unit to flush decoder " +
                                            "reason=$openReason bytes=${flushAccessUnit.size}"
                                    )
                                    break
                                }
                                Thread.sleep(5L)
                            }
                            var drainAttempt = 0
                            while (
                                mdmPendingBootstrapFrame == null &&
                                drainAttempt < MDM_BOOTSTRAP_DRAIN_ATTEMPTS
                            ) {
                                drainMdmDecoderOutput(decoder, suppressOutputThroughUs)
                                drainAttempt += 1
                                Thread.sleep(5L)
                            }
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
                    for (accessUnit in groupMdmAvcAccessUnits(parsedNalUnits.first)) {
                        while (isStart && mdmDecoder === decoder) {
                            drainMdmDecoderOutput(decoder, suppressOutputThroughUs)
                            if (queueMdmDecoderInput(decoder, accessUnit, presentationTimeUs)) {
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
                    Thread({
                        Thread.sleep(500L)
                        if (isStart && mdmDecoder === decoder) {
                            Log.w(logTag, "mdm h264 feed scheduling decoder recovery")
                            switchToMdmSystemScreenrecordCapture("feed_thread_error", forceRestart = true)
                        }
                    }, "mdm-screenrecord-feed-recovery").start()
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
            val startedAtMs = System.currentTimeMillis()
            var recoveryScheduled = false
            while (isStart && mdmDecoder === decoder) {
                emitMdmKeepaliveFrameIfNeeded()
                if (
                    !recoveryScheduled &&
                    mdmLastRgbaFrame == null &&
                    System.currentTimeMillis() - startedAtMs >= MDM_BOOTSTRAP_NO_FRAME_RECOVERY_MS
                ) {
                    recoveryScheduled = true
                    Log.w(
                        logTag,
                        "mdm screenrecord produced no first frame after " +
                            "${MDM_BOOTSTRAP_NO_FRAME_RECOVERY_MS}ms; restarting decoder"
                    )
                    Thread({
                        if (isStart && mdmDecoder === decoder && mdmLastRgbaFrame == null) {
                            switchToMdmSystemScreenrecordCapture(
                                "bootstrap_no_first_frame",
                                forceRestart = true
                            )
                        }
                    }, "mdm-screenrecord-first-frame-recovery").start()
                    return@Thread
                }
                val decodedFrameAgeMs = System.currentTimeMillis() - mdmLastDecodedFramePublishedAtMs
                val decoderInputDelta = mdmDecoderInputCount.get() - mdmDecoderInputCountAtLastOutput
                if (
                    !recoveryScheduled &&
                    mdmLastRgbaFrame != null &&
                    mdmLastDecodedFramePublishedAtMs > 0L &&
                    decodedFrameAgeMs >= MDM_DECODE_STALL_RECOVERY_MS &&
                    decoderInputDelta >= MDM_DECODE_STALL_MIN_INPUT_DELTA
                ) {
                    recoveryScheduled = true
                    Log.w(
                        logTag,
                        "mdm decoder stalled with active input; restarting capture " +
                            "frame_age_ms=$decodedFrameAgeMs input_delta=$decoderInputDelta " +
                            "decoder_in=${mdmDecoderInputCount.get()} " +
                            "decoder_out=${mdmDecoderOutputCount.get()}"
                    )
                    Thread({
                        if (
                            isStart &&
                            mdmDecoder === decoder &&
                            System.currentTimeMillis() - mdmLastDecodedFramePublishedAtMs >=
                                MDM_DECODE_STALL_RECOVERY_MS
                        ) {
                            if (!restartMdmScreenrecordDecoder("decoder_output_stall")) {
                                switchToMdmSystemScreenrecordCapture(
                                    "decoder_output_stall_fallback",
                                    forceRestart = true
                                )
                            }
                        }
                    }, "mdm-screenrecord-decoder-stall-recovery").start()
                    return@Thread
                }
                Thread.sleep(5L)
            }
        }, "mdm-screenrecord-keepalive")
        mdmKeepaliveThread = keepaliveThread
        keepaliveThread.start()
    }

    private fun readMdmFeedBootstrap(
        stream: java.io.RandomAccessFile,
        currentLength: Long,
        allowStableTrailingNal: Boolean = false
    ): MdmFeedBootstrap {
        if (currentLength <= 0L) {
            return MdmFeedBootstrap(emptyList(), ByteArray(0), 0L)
        }
        val tailReadLength = min(currentLength, MDM_BOOTSTRAP_MAX_BYTES.toLong()).toInt()
        val tailStartOffset = currentLength - tailReadLength
        val tailBytes = ByteArray(tailReadLength)
        stream.seek(tailStartOffset)
        stream.readFully(tailBytes)
        val tailParseBytes = if (allowStableTrailingNal) {
            tailBytes + byteArrayOf(0, 0, 0, 1)
        } else {
            tailBytes
        }
        val tailParsed = extractCompleteAnnexBNalUnits(tailParseBytes)
        val tailNalUnits = tailParsed.first
        val idrIndex = tailNalUnits.indexOfLast { mdmH264NalType(it) == 5 }
        if (idrIndex < 0) {
            return MdmFeedBootstrap(emptyList(), tailBytes, currentLength)
        }

        val tailSps = (idrIndex downTo 0)
            .firstOrNull { mdmH264NalType(tailNalUnits[it]) == 7 }
            ?.let { tailNalUnits[it] }
        val tailPps = (idrIndex downTo 0)
            .firstOrNull { mdmH264NalType(tailNalUnits[it]) == 8 }
            ?.let { tailNalUnits[it] }

        // screenrecord emits SPS/PPS at the beginning of the generation, while a
        // long-running file can place the newest IDR beyond the tail bootstrap
        // window. Read the head independently and combine its parameter sets with
        // the newest complete IDR from the tail. Prefer tail parameter sets when a
        // mid-stream format refresh happened.
        var parameterSetSource = "tail"
        var sps = tailSps
        var pps = tailPps
        if (sps == null || pps == null) {
            val headReadLength = min(currentLength, MDM_BOOTSTRAP_MAX_BYTES.toLong()).toInt()
            val headBytes = if (tailStartOffset == 0L) {
                tailBytes
            } else {
                ByteArray(headReadLength).also {
                    stream.seek(0L)
                    stream.readFully(it)
                }
            }
            val headNalUnits = extractCompleteAnnexBNalUnits(
                headBytes + byteArrayOf(0, 0, 0, 1)
            ).first
            if (sps == null) {
                sps = headNalUnits.lastOrNull { mdmH264NalType(it) == 7 }
            }
            if (pps == null) {
                pps = headNalUnits.lastOrNull { mdmH264NalType(it) == 8 }
            }
            parameterSetSource = "head"
        }
        if (sps == null || pps == null) {
            return MdmFeedBootstrap(emptyList(), tailBytes, currentLength)
        }
        val selectedSps = sps ?: return MdmFeedBootstrap(emptyList(), tailBytes, currentLength)
        val selectedPps = pps ?: return MdmFeedBootstrap(emptyList(), tailBytes, currentLength)
        val selected = ArrayList<ByteArray>()
        selected.add(selectedSps)
        selected.add(selectedPps)
        selected.addAll(tailNalUnits.subList(idrIndex, tailNalUnits.size))
        Log.i(
            logTag,
            "mdm h264 bootstrap assembled parameter_sets=$parameterSetSource " +
                "tail_start=$tailStartOffset size=$currentLength idr_index=$idrIndex"
        )
        return MdmFeedBootstrap(
            selected,
            if (allowStableTrailingNal) ByteArray(0) else tailParsed.second,
            currentLength
        )
    }

    private fun mdmH264NalType(nalUnit: ByteArray): Int {
        val startOffset = findAnnexBStartCode(nalUnit, 0)
        if (startOffset < 0) return -1
        val headerOffset = startOffset + annexBStartCodeLength(nalUnit, startOffset)
        if (headerOffset >= nalUnit.size) return -1
        return nalUnit[headerOffset].toInt() and 0x1f
    }

    private fun groupMdmAvcAccessUnits(nalUnits: List<ByteArray>): List<ByteArray> {
        if (nalUnits.isEmpty()) return emptyList()
        val accessUnits = ArrayList<ByteArray>()
        var current = java.io.ByteArrayOutputStream()
        var currentHasVcl = false

        fun flushCurrent() {
            if (current.size() == 0) return
            accessUnits.add(current.toByteArray())
            current = java.io.ByteArrayOutputStream()
            currentHasVcl = false
        }

        for (nalUnit in nalUnits) {
            val nalType = mdmH264NalType(nalUnit)
            val isVcl = nalType == 1 || nalType == 5
            if ((nalType == 9 && current.size() > 0) || (isVcl && currentHasVcl)) {
                flushCurrent()
            }
            current.write(nalUnit)
            if (isVcl) {
                currentHasVcl = true
            }
        }
        flushCurrent()
        return accessUnits
    }

    private fun queueMdmDecoderInput(decoder: MediaCodec, nalUnit: ByteArray, presentationTimeUs: Long): Boolean {
        val inputIndex = decoder.dequeueInputBuffer(5000)
        if (inputIndex < 0) {
            mdmDecoderInputWaitCount.incrementAndGet()
            return false
        }
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
        mdmDecoderInputCount.incrementAndGet()
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
                            val conversionStartedAtNs = System.nanoTime()
                            val rgbaBuffer = mdmDecoderOutputToRgba(decoder, outputIndex, bufferInfo)
                            mdmRgbaConversionNanos.addAndGet(System.nanoTime() - conversionStartedAtNs)
                            if (rgbaBuffer != null) {
                                mdmDecoderOutputCount.incrementAndGet()
                                mdmDecoderInputCountAtLastOutput = mdmDecoderInputCount.get()
                                rgbaBuffer.rewind()
                                val byteCount = rgbaBuffer.remaining()
                                val frame = copyMdmRgbaFrame(rgbaBuffer, suppressCurrentOutput)
                                if (suppressCurrentOutput) {
                                    mdmPendingBootstrapFrame = frame
                                } else {
                                    val submitStartedAtNs = System.nanoTime()
                                    FFI.onVideoFrameUpdate(rgbaBuffer)
                                    mdmJniSubmitNanos.addAndGet(System.nanoTime() - submitStartedAtNs)
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
        val submitStartedAtNs = System.nanoTime()
        FFI.onVideoFrameUpdate(frame)
        mdmJniSubmitNanos.addAndGet(System.nanoTime() - submitStartedAtNs)
        mdmLastRgbaFrame = frame.asReadOnlyBuffer().apply { rewind() }
        val now = System.currentTimeMillis()
        mdmLastFrameOutputAtMs = now
        mdmLastDecodedFramePublishedAtMs = now
        mdmDecoderInputCountAtLastOutput = mdmDecoderInputCount.get()
        recordCaptureFrame(byteCount)
        Log.i(logTag, "mdm bootstrap published latest frame only reason=$reason nals=$bootstrapNalCount")
    }

    private fun republishMdmFrameForNewConnection(reason: String) {
        val frame = mdmLastRgbaFrame?.duplicate()?.apply { rewind() }
        if (frame == null) {
            // The bootstrap path will publish the first frame shortly. Refresh is
            // still required here so Rust keeps the request pending for the new
            // subscriber instead of relying on a refresh emitted before it existed.
            FFI.refreshScreen()
            Log.w(logTag, "MDM-ConnectionFrameRefresh pending_bootstrap reason=$reason")
            return
        }
        val submitStartedAtNs = System.nanoTime()
        FFI.onVideoFrameUpdate(frame)
        mdmJniSubmitNanos.addAndGet(System.nanoTime() - submitStartedAtNs)
        mdmLastFrameOutputAtMs = System.currentTimeMillis()
        captureDuplicateFrameCount.incrementAndGet()
        FFI.refreshScreen()
        Log.i(
            logTag,
            "MDM-ConnectionFrameRefresh republished=true reason=$reason bytes=${frame.remaining()}"
        )
    }

    private fun copyMdmRgbaFrame(source: ByteBuffer, pending: Boolean): ByteBuffer {
        val input = source.duplicate().apply { rewind() }
        val requiredBytes = input.remaining()
        val current = if (pending) mdmPendingFrameScratch else mdmLastFrameScratch
        val target = current
            ?.takeIf { it.capacity() == requiredBytes }
            ?: ByteBuffer.allocateDirect(requiredBytes).also {
                if (pending) mdmPendingFrameScratch = it else mdmLastFrameScratch = it
            }
        target.clear()
        return target.apply {
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
        val submitStartedAtNs = System.nanoTime()
        FFI.onVideoFrameUpdate(frame)
        mdmJniSubmitNanos.addAndGet(System.nanoTime() - submitStartedAtNs)
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
        val requiredBytes = imageWidth * imageHeight * 4
        val rgbaBuffer = mdmRgbaScratch
            ?.takeIf { it.capacity() == requiredBytes }
            ?: ByteBuffer.allocateDirect(requiredBytes).also { mdmRgbaScratch = it }
        rgbaBuffer.clear()
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
        mdmDecoderInputCountAtLastOutput = 0L
        mdmBootstrapInProgress = false
        mdmPendingBootstrapFrame = null
        mdmKeepaliveFrameCount = 0L
        mdmRgbaScratch = null
        mdmLastFrameScratch = null
        mdmPendingFrameScratch = null
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
