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
        if (MdmInputFallback.pointer(applicationContext, kind, mask, x, y)) {
            return
        }
        when (kind) {
            0 -> { // touch
                InputService.ctx?.onTouchInput(mask, x, y)
            }
            1 -> { // mouse
                InputService.ctx?.onMouseInput(mask, x, y)
            }
            else -> {
            }
        }
    }

    @Keep
    @RequiresApi(Build.VERSION_CODES.N)
    fun rustKeyEventInput(input: ByteArray) {
        InputService.ctx?.onKeyEvent(input)
    }

    @Keep
    fun rustGetByName(name: String): String {
        return when (name) {
            "screen_size" -> {
                JSONObject().apply {
                    put("width",SCREEN_INFO.width)
                    put("height",SCREEN_INFO.height)
                    put("scale",SCREEN_INFO.scale)
                }.toString()
            }
            "is_start" -> {
                isStart.toString()
            }
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
                            if (mediaProjection == null) {
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
        private const val CAPTURE_LOG_TAG = "LOG_SERVICE"
        private const val MEDIA_PROJECTION_REQUEST_TTL_MS = 12_000L
        private var _isReady = false // media permission ready status
        private var _isStart = false // screen capture start status
        private var _isAudioStart = false // audio capture start status
        private val captureFrameCount = AtomicLong(0)
        private val captureByteCount = AtomicLong(0)
        private val captureDroppedFrameCount = AtomicLong(0)
        private val captureErrorCount = AtomicLong(0)
        @Volatile private var captureStartedAtMs = 0L
        @Volatile private var captureLastFrameAtMs = 0L
        @Volatile private var captureLastStatsLogAtMs = 0L
        @Volatile private var captureLastStatsLogFrameCount = 0L
        @Volatile private var captureLastStatsLogByteCount = 0L
        @Volatile private var captureLastError: String? = null
        @Volatile private var mediaProjectionRequestStartedAtMs = 0L
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
        val mediaProjectionRequestInFlight: Boolean
            get() {
                val startedAt = mediaProjectionRequestStartedAtMs
                val ageMs = System.currentTimeMillis() - startedAt
                return startedAt > 0L && ageMs in 0L..MEDIA_PROJECTION_REQUEST_TTL_MS
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
                "MDM-CaptureStart width=${SCREEN_INFO.width} height=${SCREEN_INFO.height} " +
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
                        "width=${SCREEN_INFO.width} height=${SCREEN_INFO.height} scale=${SCREEN_INFO.scale}"
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

    // audio
    private val audioRecordHandle = AudioRecordHandle(this, { isStart }, { isAudioStart })

    // notification
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationChannel: String
    private lateinit var notificationBuilder: NotificationCompat.Builder

    override fun onCreate() {
        super.onCreate()
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
        releaseScreenWakeLock("service_destroy")
        stopService(Intent(this, FloatingWindowService::class.java))
        super.onDestroy()
    }

    private var isHalfScale: Boolean? = null;
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
            if (SCREEN_INFO.width != w) {
                SCREEN_INFO.width = w
                SCREEN_INFO.height = h
                SCREEN_INFO.scale = scale
                SCREEN_INFO.dpi = dpi
                if (isStart) {
                    stopCapture()
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
                if (!isStart) {
                    _isReady = false
                }
            }
        }
        return START_NOT_STICKY // don't use sticky (auto restart), the new service (from auto restart) will lose control
    }

    private fun appFlutterDir(): String = "${applicationInfo.dataDir}/app_flutter"

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenInfo(newConfig.orientation)
    }

    private fun requestMediaProjection() {
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
        val activeProjection = projection
        if (activeProjection == null) {
            Log.w(logTag, "startCapture fail,mediaProjection is null")
            return false
        }
        keepScreenInteractive("start_capture")
        
        updateScreenInfo(resources.configuration.orientation)
        Log.d(logTag, "Start Capture")
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!audioRecordHandle.createAudioRecorder(false, activeProjection)) {
                Log.d(logTag, "createAudioRecorder fail")
            } else {
                Log.d(logTag, "audio recorder start")
                audioRecordHandle.startAudioRecorder()
            }
        }
        checkMediaPermission()
        FFI.setFrameRawEnable("video",true)
        return true
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
}
