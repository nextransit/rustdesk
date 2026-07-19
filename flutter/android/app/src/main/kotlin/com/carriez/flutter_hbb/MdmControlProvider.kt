package com.carriez.flutter_hbb

import android.app.ActivityManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Build
import android.util.Log
import ffi.FFI
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class MdmControlProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val result = Bundle()
        if (!isAuthorizedCaller()) {
            result.putBoolean(KEY_SUCCESS, false)
            result.putString(KEY_ERROR, "unauthorized caller uid=${Binder.getCallingUid()}")
            return result
        }

        return try {
            when (method) {
                METHOD_SET_SERVER_CONFIG -> setServerConfig(extras)
                METHOD_SET_AUDIO_ENABLED -> setAudioEnabled(extras)
                METHOD_SET_SESSION_PASSWORD -> setSessionPassword(extras)
                METHOD_CLEAR_SESSION_PASSWORD -> clearSessionPassword()
                METHOD_START_SERVICE -> startService(extras)
                METHOD_REQUEST_MEDIA_PROJECTION -> requestMediaProjection()
                METHOD_SWITCH_MDM_SCREENRECORD -> switchMdmScreenrecord(extras)
                METHOD_STOP_CAPTURE -> stopCapture()
                METHOD_STOP_SERVICE -> stopService()
                METHOD_SERVICE_STATUS -> serviceStatus()
                METHOD_GET_IDENTITY -> getIdentity()
                METHOD_ACCEPT_WEBRTC_OFFER -> acceptWebRtcOffer(extras)
                else -> Bundle().apply {
                    putBoolean(KEY_SUCCESS, false)
                    putString(KEY_ERROR, "unknown method: $method")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MDM control call failed: $method", e)
            result.putBoolean(KEY_SUCCESS, false)
            result.putString(KEY_ERROR, e.message ?: e.javaClass.simpleName)
            result
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private fun setServerConfig(extras: Bundle?): Bundle {
        val hbbs = extras?.getString(EXTRA_HBBS)?.trim().orEmpty()
        val hbbr = extras?.getString(EXTRA_HBBR)?.trim().orEmpty()
        val key = extras?.getString(EXTRA_KEY)?.trim().orEmpty()
        val audioEnabled = extras?.getBoolean(EXTRA_AUDIO_ENABLED, false) ?: false
        val startServer = extras?.getBoolean(EXTRA_START_SERVER, true) ?: true
        require(hbbs.isNotBlank()) { "hbbs is required" }
        require(hbbr.isNotBlank()) { "hbbr is required" }

        // Let RustDesk write its own config as the app uid. MDM agent cannot
        // write RustDesk private files directly on non-root devices.
        val appDir = configDir()
        Log.i(TAG, "MDM-debug setServerConfig start: appDir=${appDir.absolutePath} hbbs=$hbbs hbbr=$hbbr audio=$audioEnabled keyLen=${key.length}")
        context?.applicationContext?.let { MediaCodecInfoBridge.sync(it) }
        val options = linkedMapOf(
            "custom-rendezvous-server" to hbbs,
            "relay-server" to hbbr,
            "key" to key,
            // Managed Dashboard sessions must stay connected while the operator
            // watches a static screen. A persisted RustDesk desktop setting can
            // otherwise close the incoming session after one idle minute; Rust
            // then emits stop_capture and the Web client enters a reconnect loop.
            "allow-auto-disconnect" to "N",
            "auto-disconnect-timeout" to "0"
        )
        options.putAll(managedWebRtcOptions(extras))
        if (extras?.containsKey(EXTRA_MAX_FPS) == true) {
            options.putAll(managedStreamOptions(extras))
        }
        options["enable-audio"] = if (audioEnabled) "Y" else "N"
        options["disable-audio"] = if (audioEnabled) "N" else "Y"
        val failed = options.filterNot { (optionKey, optionValue) ->
            FFI.setOption(appDir.absolutePath, optionKey, optionValue)
        }.keys
        require(failed.isEmpty()) { "set RustDesk server options failed: ${failed.joinToString(",")}" }
        context?.getSharedPreferences(KEY_SHARED_PREFERENCES, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_MDM_AUDIO_ENABLED, audioEnabled)
            ?.apply()
        val runtimeAudioApplied = MainService.applyMdmAudioEnabled(audioEnabled)
        Log.i(
            TAG,
            "MDM RustDesk options applied: hbbs=$hbbs hbbr=$hbbr " +
                "codec=${options["mdm-codec-preference"].orEmpty()} " +
                "fps=${options["mdm-max-fps"].orEmpty()} " +
                "bitrate=${options["mdm-max-bitrate-bps"].orEmpty()} " +
                "webrtc=${options["mdm-webrtc-transport"].orEmpty()} " +
                "icePolicy=${options["mdm-webrtc-ice-transport"].orEmpty()} " +
                "autoDisconnect=${options["allow-auto-disconnect"].orEmpty()} " +
                "audio=$audioEnabled runtimeAudioApplied=$runtimeAudioApplied"
        )
        if (startServer) {
            Log.i(TAG, "MDM-debug FFI.startServer called")
            FFI.startServer(appDir.absolutePath, "")
            val myId = FFI.getMyId(appDir.absolutePath)
            Log.i(TAG, "MDM-debug FFI.startServer done getMyId=$myId")
        } else {
            Log.i(TAG, "MDM-debug FFI.startServer skipped reason=config_refresh")
        }
        return success(File(appDir, RUSTDESK2_TOML)).apply {
            putString(KEY_STATUS_WEBRTC_CAPABILITIES, webRtcCapabilities(appDir))
        }
    }

    /**
     * Hot-switch managed session audio without restarting the RustDesk server.
     *
     * set_server_config historically calls FFI.startServer(). Reusing it for an
     * audio toggle tears down the active capture/connection and causes a visible
     * video interruption. Audio is an independent runtime plane: update the two
     * RustDesk options, persist the managed flag, then start/stop only the active
     * audio recorder.
     */
    private fun setAudioEnabled(extras: Bundle?): Bundle {
        val audioEnabled = extras?.getBoolean(EXTRA_AUDIO_ENABLED, false) ?: false
        val appDir = configDir()
        val options = linkedMapOf(
            "enable-audio" to if (audioEnabled) "Y" else "N",
            "disable-audio" to if (audioEnabled) "N" else "Y",
            "mdm-audio-enabled" to if (audioEnabled) "Y" else "N"
        )
        val failed = options.filterNot { (optionKey, optionValue) ->
            FFI.setOption(appDir.absolutePath, optionKey, optionValue)
        }.keys
        require(failed.isEmpty()) {
            "set RustDesk audio options failed: ${failed.joinToString(",")}"
        }
        context?.getSharedPreferences(KEY_SHARED_PREFERENCES, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_MDM_AUDIO_ENABLED, audioEnabled)
            ?.apply()
        val runtimeAudioApplied = MainService.applyMdmAudioEnabled(audioEnabled)
        Log.i(
            TAG,
            "MDM audio hot switch applied audio=$audioEnabled " +
                "runtimeAudioApplied=$runtimeAudioApplied serverRestarted=false"
        )
        return success(File(appDir, RUSTDESK2_TOML)).apply {
            putBoolean(KEY_STATUS_AUDIO_ENABLED, audioEnabled)
            putBoolean(KEY_STATUS_AUDIO_RUNNING, audioEnabled && runtimeAudioApplied)
        }
    }

    private fun acceptWebRtcOffer(extras: Bundle?): Bundle {
        val offerEndpoint = extras?.getString(EXTRA_WEBRTC_OFFER_ENDPOINT)?.trim().orEmpty()
        require(offerEndpoint.startsWith("webrtc://")) { "invalid WebRTC offer endpoint" }
        val answerEndpoint = FFI.acceptWebRtcOffer(configDir().absolutePath, offerEndpoint)
        require(answerEndpoint.startsWith("webrtc://")) { answerEndpoint.ifBlank { "empty WebRTC answer" } }
        return success(File(configDir(), RUSTDESK2_TOML)).apply {
            putString(KEY_WEBRTC_ANSWER_ENDPOINT, answerEndpoint)
        }
    }

    private fun managedWebRtcOptions(extras: Bundle?): LinkedHashMap<String, String> {
        val options = linkedMapOf<String, String>()
        if (extras == null) {
            return options
        }

        val iceServersKey = firstPresentKey(
            extras,
            EXTRA_WEBRTC_ICE_SERVERS,
            EXTRA_ICE_SERVERS_JSON,
            EXTRA_ICE_SERVERS
        )
        if (iceServersKey != null) {
            options["ice-servers"] = normalizeIceServers(
                extras.getString(iceServersKey).orEmpty()
            )
            options["mdm-webrtc-allow-default-stun"] = "N"
        }

        val requestedTransport = when {
            extras.containsKey(EXTRA_WEBRTC_TRANSPORT) ->
                extras.getString(EXTRA_WEBRTC_TRANSPORT).orEmpty()
            extras.containsKey(EXTRA_TRANSPORT_PREFERENCE) ->
                extras.getString(EXTRA_TRANSPORT_PREFERENCE).orEmpty()
            extras.containsKey(EXTRA_SESSION_TRANSPORT) ->
                extras.getString(EXTRA_SESSION_TRANSPORT).orEmpty()
            extras.containsKey(EXTRA_TRANSPORT) -> extras.getString(EXTRA_TRANSPORT).orEmpty()
            else -> ""
        }.trim().lowercase()
        val transport = when (requestedTransport) {
            "disabled", "off", "rustdesk", "websocket" -> "disabled"
            "auto" -> "auto"
            "datachannel", "data-channel", "datachannel-preferred", "webrtc",
            "webrtc-first", "webrtc-only", "webrtc-datachannel" -> "datachannel"
            "media", "media-track", "media-preferred", "webrtc-media" -> "media"
            "" -> ""
            else -> throw IllegalArgumentException(
                "unsupported WebRTC transport preference: $requestedTransport"
            )
        }
        if (transport.isNotEmpty()) {
            options["mdm-webrtc-transport"] = transport
        }

        val iceTransportKey = firstPresentKey(
            extras,
            EXTRA_WEBRTC_ICE_TRANSPORT,
            EXTRA_ICE_TRANSPORT_POLICY
        )
        if (iceTransportKey != null) {
            val iceTransport = extras.getString(iceTransportKey)
                .orEmpty()
                .trim()
                .lowercase()
            options["mdm-webrtc-ice-transport"] = when (iceTransport) {
                "all", "direct-first" -> "all"
                "relay", "relay-only" -> "relay"
                else -> throw IllegalArgumentException(
                    "unsupported WebRTC ICE transport policy: $iceTransport"
                )
            }
        }

        if (extras.containsKey(EXTRA_WEBRTC_ALLOW_DEFAULT_STUN)) {
            options["mdm-webrtc-allow-default-stun"] = boolOption(
                extras.getBoolean(EXTRA_WEBRTC_ALLOW_DEFAULT_STUN, false)
            )
        }
        if (extras.containsKey(EXTRA_WEBRTC_MEDIA_ENABLED)) {
            options["mdm-webrtc-media-enabled"] = boolOption(
                extras.getBoolean(EXTRA_WEBRTC_MEDIA_ENABLED, false)
            )
        }
        copyBooleanOption(extras, options, EXTRA_WEBRTC_ENABLED, "mdm-webrtc-enabled")
        copyBooleanOption(
            extras,
            options,
            EXTRA_WEBRTC_BACKEND_SIGNALING_ENABLED,
            "mdm-webrtc-backend-signaling-enabled"
        )
        copyBooleanOption(
            extras,
            options,
            EXTRA_WEBRTC_DATA_CHANNEL_ENABLED,
            "mdm-webrtc-data-channel-enabled"
        )
        copyBooleanOption(
            extras,
            options,
            EXTRA_WEBRTC_MEDIA_TRACK_ENABLED,
            "mdm-webrtc-media-enabled"
        )
        copyBooleanOption(extras, options, EXTRA_WEBRTC_TURN_ENABLED, "mdm-webrtc-turn-enabled")
        copyBooleanOption(
            extras,
            options,
            EXTRA_WEBRTC_AUTOMATIC_FALLBACK_ENABLED,
            "mdm-webrtc-fallback-enabled"
        )
        copyBooleanOption(
            extras,
            options,
            EXTRA_WEBRTC_SELF_HEALING_ENABLED,
            "mdm-webrtc-self-healing-enabled"
        )
        if (extras.containsKey(EXTRA_WEBRTC_SIGNALING_MODE)) {
            val signalingMode = extras.getString(EXTRA_WEBRTC_SIGNALING_MODE)
                .orEmpty()
                .trim()
                .lowercase()
            require(signalingMode in setOf("backend", "existing-session", "disabled")) {
                "unsupported WebRTC signaling mode: $signalingMode"
            }
            options["mdm-webrtc-signaling-mode"] = signalingMode
        }
        if (extras.containsKey(EXTRA_WEBRTC_ENABLED) &&
            !extras.getBoolean(EXTRA_WEBRTC_ENABLED, false)
        ) {
            options["mdm-webrtc-transport"] = "disabled"
        }
        return options
    }

    private fun copyBooleanOption(
        extras: Bundle,
        options: LinkedHashMap<String, String>,
        extraKey: String,
        optionKey: String
    ) {
        if (extras.containsKey(extraKey)) {
            options[optionKey] = boolOption(extras.getBoolean(extraKey, false))
        }
    }

    private fun normalizeIceServers(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return ""
        }
        val input = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            JSONArray().apply {
                trimmed.split(',', '\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { put(it) }
            }
        }
        require(input.length() <= MAX_WEBRTC_ICE_SERVERS) { "too many WebRTC ICE servers" }

        val output = JSONArray()
        for (index in 0 until input.length()) {
            when (val entry = input.get(index)) {
                is String -> output.put(validateIceUrl(entry))
                is JSONObject -> {
                    val urls = normalizeIceServerUrls(entry.get("urls"))
                    val username = entry.optString("username", "")
                    val credential = entry.optString("credential", "")
                    val credentialType = entry.optString("credential_type", "password")
                        .trim()
                        .lowercase()
                    require(credentialType == "password") {
                        "unsupported WebRTC ICE credential type"
                    }
                    require(username.length <= MAX_WEBRTC_ICE_CREDENTIAL_LENGTH) {
                        "WebRTC ICE username is too long"
                    }
                    require(credential.length <= MAX_WEBRTC_ICE_CREDENTIAL_LENGTH) {
                        "WebRTC ICE credential is too long"
                    }
                    output.put(JSONObject().apply {
                        put("urls", urls)
                        if (username.isNotEmpty()) put("username", username)
                        if (credential.isNotEmpty()) put("credential", credential)
                        put("credential_type", "password")
                    })
                }
                else -> throw IllegalArgumentException("invalid WebRTC ICE server entry")
            }
        }
        return output.toString()
    }

    private fun normalizeIceServerUrls(value: Any): Any {
        return when (value) {
            is String -> validateIceUrl(value)
            is JSONArray -> JSONArray().apply {
                require(value.length() in 1..MAX_WEBRTC_URLS_PER_SERVER) {
                    "invalid WebRTC ICE URL count"
                }
                for (index in 0 until value.length()) {
                    put(validateIceUrl(value.getString(index)))
                }
            }
            else -> throw IllegalArgumentException("invalid WebRTC ICE urls")
        }
    }

    private fun validateIceUrl(raw: String): String {
        val value = raw.trim()
        require(value.length <= MAX_WEBRTC_ICE_SERVER_LENGTH) {
            "WebRTC ICE server URL is too long"
        }
        require(WEBRTC_ICE_SERVER_PATTERN.matches(value)) {
            "invalid WebRTC ICE server URL"
        }
        return value
    }

    private fun firstPresentKey(extras: Bundle, vararg keys: String): String? {
        return keys.firstOrNull { extras.containsKey(it) }
    }

    private fun managedStreamOptions(extras: Bundle?): LinkedHashMap<String, String> {
        val requestedCodec = extras.stringExtra(EXTRA_CODEC_PREFERENCE, "h264")
            .lowercase()
            .takeIf { it in setOf("auto", "vp8", "vp9", "av1", "h264", "h265") }
            ?: "h264"
        val codec = requestedCodec
        val audioEnabled = extras.booleanExtra(EXTRA_AUDIO_ENABLED, false)
        val fileTransferEnabled = extras.booleanExtra(EXTRA_FILE_TRANSFER_ENABLED, false)
        val tcpTunnelEnabled = extras.booleanExtra(EXTRA_TCP_TUNNEL_ENABLED, false)
        val maxBitrateBps = extras.intExtra(EXTRA_MAX_BITRATE_BPS, 600_000, 250_000, 3_000_000)
        val maxFps = extras.intExtra(EXTRA_MAX_FPS, 10, 1, 30)
        val idleFps = extras.intExtra(EXTRA_IDLE_FPS, 1, 1, 5)
        val customImageQuality = extras.intExtra(EXTRA_CUSTOM_IMAGE_QUALITY, 30, 20, 80)
        val imageQuality = extras.stringExtra(EXTRA_IMAGE_QUALITY, "custom")
            .lowercase()
            .takeIf { it in setOf("low", "balanced", "best", "custom") }
            ?: "custom"
        val preferHardware = codec == "auto" || codec == "h264" || codec == "h265"

        return linkedMapOf(
            "enable-hwcodec" to boolOption(preferHardware),
            "codec-preference" to codec,
            "image_quality" to imageQuality,
            "custom_image_quality" to customImageQuality.toString(),
            "custom-fps" to maxFps.toString(),
            // MDM profile 已明确给出 max bitrate / fps；避免通用 ABR 再把
            // Android 9 relay 会话二次降帧到配置值的一半。
            "enable-abr" to "N",
            "enable-abr-fps" to "N",
            "enable-audio" to boolOption(audioEnabled),
            "audio-input" to "",
            "enable-file-transfer" to boolOption(fileTransferEnabled),
            "enable-tunnel" to boolOption(tcpTunnelEnabled),
            "mdm-stream-quality" to extras.stringExtra(EXTRA_STREAM_QUALITY, "saving"),
            "mdm-max-bitrate-bps" to maxBitrateBps.toString(),
            "mdm-max-fps" to maxFps.toString(),
            "mdm-idle-fps" to idleFps.toString(),
            "mdm-scale-resolution-down-by" to extras.doubleExtra(EXTRA_SCALE_DOWN_BY, 2.2, 1.0, 4.0).toString(),
            "mdm-image-quality" to imageQuality,
            "mdm-custom-image-quality" to customImageQuality.toString(),
            "mdm-codec-preference" to codec,
            "mdm-force-hardware" to boolOption(preferHardware),
            "mdm-audio-enabled" to boolOption(audioEnabled),
            "mdm-file-transfer-enabled" to boolOption(fileTransferEnabled),
            "mdm-tcp-tunnel-enabled" to boolOption(tcpTunnelEnabled),
            "mdm-connection-strategy" to extras.stringExtra(EXTRA_CONNECTION_STRATEGY, "direct-first"),
            "mdm-transport" to extras.stringExtra(EXTRA_TRANSPORT, "auto"),
            "mdm-mtu" to extras.intExtra(EXTRA_MTU, 1350, 1200, 1500).toString()
        )
    }

    private fun boolOption(value: Boolean): String = if (value) "Y" else "N"

    private fun Bundle?.stringExtra(key: String, fallback: String): String {
        return this?.getString(key)?.trim()?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun Bundle?.booleanExtra(key: String, fallback: Boolean): Boolean {
        return this?.getBoolean(key, fallback) ?: fallback
    }

    private fun Bundle?.intExtra(key: String, fallback: Int, min: Int, max: Int): Int {
        return (this?.getInt(key, fallback) ?: fallback).coerceIn(min, max)
    }

    private fun Bundle?.doubleExtra(key: String, fallback: Double, min: Double, max: Double): Double {
        return (this?.getDouble(key, fallback) ?: fallback).coerceIn(min, max)
    }

    private fun setSessionPassword(extras: Bundle?): Bundle {
        val password = extras?.getString(EXTRA_PASSWORD)?.trim().orEmpty()
        require(password.isNotBlank()) { "password is required" }

        val file = File(configDir(), RUSTDESK_TOML)
        val ok = FFI.setPermanentPassword(configDir().absolutePath, password)
        require(ok) { "set permanent password failed" }
        return success(file)
    }

    private fun clearSessionPassword(): Bundle {
        val file = File(configDir(), RUSTDESK_TOML)
        val ok = FFI.clearPermanentPassword(configDir().absolutePath)
        require(ok) { "clear permanent password failed" }
        return success(file)
    }

    /**
     * 拉起 MainService 后台服务 (无 mediaProjection), 由 mdm-agent 触发.
     *
     * 设计: mdm-no-launcher 模式下, RustDesk 自身无桌面图标, 开机不自启.
     * mdm-agent (system uid) 通过此方法在合适时机拉起后台服务:
     * - 设备入网后首次配置完成
     * - 用户登录后需要远控能力
     * - mdm-agent 拉起此 service 走 foreground service, 不会被 OOM 杀掉
     *
     * service 拉起后通过 FFI.startService(true) 进入 rust 端 hbbr 监听,
     * 等待远控接入请求. MediaProjection 授权延后到首次会话时再触发.
     */
    private fun startService(extras: Bundle?): Bundle {
        val ctx = context ?: return Bundle().apply {
            putBoolean(KEY_SUCCESS, false); putString(KEY_ERROR, "no context")
        }
        val fromBoot = extras?.getBoolean(EXTRA_FROM_BOOT, false) ?: false
        ctx.getSharedPreferences(KEY_SHARED_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APP_DIR_CONFIG_PATH, configDir().absolutePath)
            .apply()
        val intent = Intent(ctx, MainService::class.java).apply {
            action = ACT_START_NO_PROJECTION
            putExtra(EXTRA_FROM_BOOT, fromBoot)
        }
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
            Bundle().apply {
                putBoolean(KEY_SUCCESS, true)
                putString(KEY_PATH, "MainService start requested")
            }
        } catch (e: Exception) {
            Log.e(TAG, "startService failed", e)
            Bundle().apply {
                putBoolean(KEY_SUCCESS, false)
                putString(KEY_ERROR, "startService: ${e.message}")
            }
        }
    }

    /**
     * 主动触发 MediaProjection 授权流程.
     *
     * start_service 只负责后台驻留和 hbbs/hbbr 监听；远控会话开始时必须显式
     * 拉起 PermissionRequestTransparentActivity，否则系统投屏授权弹窗不会出现，
     * MDM Agent 的自动批准 watcher 只能等到超时，最终表现为首帧超时/STREAM_TIMEOUT。
     */
    private fun requestMediaProjection(): Bundle {
        val ctx = context ?: return Bundle().apply {
            putBoolean(KEY_SUCCESS, false); putString(KEY_ERROR, "no context")
        }
        if (MainService.isCapturing) {
            if (MainService.captureSource != CAPTURE_SOURCE_MDM_SCREENRECORD) {
                MainService.switchToMdmSystemScreenrecord("request_media_projection_already_capturing")
            }
            return Bundle().apply {
                putBoolean(KEY_SUCCESS, true)
                putString(KEY_PATH, "MediaProjection already capturing")
                putBoolean(KEY_STATUS_CAPTURING, true)
                putBoolean(KEY_STATUS_MEDIA_READY, MainService.isReady)
                putString(KEY_STATUS_CAPTURE_SOURCE, MainService.captureSource)
            }
        }
        if (MainService.mediaProjectionRequestInFlight) {
            return Bundle().apply {
                putBoolean(KEY_SUCCESS, true)
                putString(KEY_PATH, "MediaProjection request already in flight")
                putBoolean(KEY_STATUS_CAPTURING, MainService.isCapturing)
                putBoolean(KEY_STATUS_MEDIA_READY, MainService.isReady)
                putString(KEY_STATUS_CAPTURE_SOURCE, MainService.captureSource)
            }
        }
        ctx.getSharedPreferences(KEY_SHARED_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APP_DIR_CONFIG_PATH, configDir().absolutePath)
            .apply()
        return try {
            val serviceIntent = Intent(ctx, MainService::class.java).apply {
                action = ACT_START_NO_PROJECTION
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(serviceIntent)
            } else {
                ctx.startService(serviceIntent)
            }
            val intent = Intent(ctx, PermissionRequestTransparentActivity::class.java).apply {
                action = ACT_REQUEST_MEDIA_PROJECTION
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            MainService.markMediaProjectionRequestStarted("mdm_provider")
            ctx.startActivity(intent)
            Log.i(TAG, "MediaProjection permission request dispatched via MDM provider")
            Bundle().apply {
                putBoolean(KEY_SUCCESS, true)
                putString(KEY_PATH, "MediaProjection request dispatched")
                putBoolean(KEY_STATUS_CAPTURING, MainService.isCapturing)
                putBoolean(KEY_STATUS_MEDIA_READY, MainService.isReady)
                putString(KEY_STATUS_CAPTURE_SOURCE, MainService.captureSource)
            }
        } catch (e: Exception) {
            MainService.clearMediaProjectionRequest("mdm_provider_failed")
            Log.e(TAG, "requestMediaProjection failed", e)
            Bundle().apply {
                putBoolean(KEY_SUCCESS, false)
                putString(KEY_ERROR, "requestMediaProjection: ${e.message}")
            }
        }
    }

    private fun switchMdmScreenrecord(extras: Bundle?): Bundle {
        val forceRestart = extras?.getBoolean(KEY_FORCE_CAPTURE_RESTART, false) == true
        val ok = MainService.switchToMdmSystemScreenrecord("provider", forceRestart)
        return Bundle().apply {
            putBoolean(KEY_SUCCESS, ok)
            putBoolean(KEY_STATUS_CAPTURING, MainService.isCapturing)
            putBoolean(KEY_STATUS_MEDIA_READY, MainService.isReady)
            putString(KEY_STATUS_CAPTURE_SOURCE, MainService.captureSource)
            if (ok) {
                putString(KEY_PATH, "switched to mdm screenrecord")
            } else {
                putString(KEY_ERROR, "mdm screenrecord source not ready")
            }
        }
    }

    private fun stopCapture(): Bundle {
        val ok = MainService.stopManagedCapture()
        return Bundle().apply {
            putBoolean(KEY_SUCCESS, ok)
            putBoolean(KEY_STATUS_CAPTURING, MainService.isCapturing)
            putBoolean(KEY_STATUS_MEDIA_READY, MainService.isReady)
            putString(KEY_STATUS_CAPTURE_SOURCE, MainService.captureSource)
            if (ok) putString(KEY_PATH, "capture stopped")
        }
    }

    /**
     * 关闭 MainService (force-stop 应用).
     *
     * 警告: 这是进程级 force-stop, 不仅停 service, 也清掉整个应用进程.
     * mdm-agent 决定何时调用 (如用户登出, 设备重置, 收到远程锁定命令).
     */
    private fun stopService(): Bundle {
        val ctx = context ?: return Bundle().apply {
            putBoolean(KEY_SUCCESS, false); putString(KEY_ERROR, "no context")
        }
        return try {
            // 用 force-stop 而非 stopService, 避免 rust ffi 残留状态
            val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.killBackgroundProcesses(RUSTDESK_PACKAGE_NAME)
            ctx.stopService(Intent(ctx, MainService::class.java))
            Bundle().apply {
                putBoolean(KEY_SUCCESS, true)
                putString(KEY_PATH, "MainService stop requested")
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopService failed", e)
            Bundle().apply {
                putBoolean(KEY_SUCCESS, false)
                putString(KEY_ERROR, "stopService: ${e.message}")
            }
        }
    }

    /**
     * 查询 MainService 当前运行状态.
     *
     * 返回: pid (>=0 表示运行) + foreground (是否前台服务)
     */
    private fun serviceStatus(): Bundle {
        val ctx = context ?: return Bundle().apply {
            putBoolean(KEY_SUCCESS, false); putString(KEY_ERROR, "no context")
        }
        return try {
            val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            val mainService = am.getRunningServices(Int.MAX_VALUE).firstOrNull {
                it.service.packageName == RUSTDESK_PACKAGE_NAME &&
                    it.service.className == MainService::class.java.name
            }
            Bundle().apply {
                putBoolean(KEY_SUCCESS, true)
                putBoolean(KEY_STATUS_RUNNING, mainService != null)
                putBoolean(KEY_STATUS_FOREGROUND, mainService?.foreground == true)
                putBoolean(KEY_STATUS_MEDIA_READY, MainService.isReady)
                putBoolean(KEY_STATUS_CAPTURING, MainService.isCapturing)
                putBoolean(KEY_STATUS_INPUT_READY, InputService.ctx != null || MdmInputFallback.isAvailable(ctx))
                putBoolean(KEY_STATUS_AUDIO_ENABLED, isMdmAudioEnabled())
                putBoolean(KEY_STATUS_AUDIO_RUNNING, MainService.isAudioStart)
                putLong(KEY_STATUS_CAPTURE_FRAMES, MainService.captureFrames)
                putLong(KEY_STATUS_CAPTURE_DUPLICATE_FRAMES, MainService.captureDuplicateFrames)
                putLong(KEY_STATUS_CAPTURE_BYTES, MainService.captureBytes)
                putLong(KEY_STATUS_CAPTURE_DROPPED_FRAMES, MainService.captureDroppedFrames)
                putLong(KEY_STATUS_CAPTURE_ERRORS, MainService.captureErrors)
                putLong(KEY_STATUS_CAPTURE_STARTED_AT, MainService.captureStartedAt)
                putLong(KEY_STATUS_CAPTURE_LAST_FRAME_AT, MainService.captureLastFrameAt)
                putLong(KEY_STATUS_CAPTURE_LAST_FRAME_AGE_MS, MainService.captureLastFrameAgeMs)
                putLong(KEY_STATUS_CAPTURE_DURATION_MS, MainService.captureDurationMs)
                putDouble(KEY_STATUS_CAPTURE_AVG_FPS, MainService.captureAverageFps)
                putInt(KEY_STATUS_CAPTURE_WIDTH, MainService.captureWidth)
                putInt(KEY_STATUS_CAPTURE_HEIGHT, MainService.captureHeight)
                putInt(KEY_STATUS_CAPTURE_SCALE, SCREEN_INFO.scale)
                putInt(KEY_STATUS_CAPTURE_DPI, SCREEN_INFO.dpi)
                putString(KEY_STATUS_CAPTURE_SOURCE, MainService.captureSource)
                putString(KEY_STATUS_WEBRTC_CAPABILITIES, webRtcCapabilities(configDir()))
                MainService.captureLastErrorMessage?.let {
                    putString(KEY_STATUS_CAPTURE_LAST_ERROR, it)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "serviceStatus failed", e)
            Bundle().apply {
                putBoolean(KEY_SUCCESS, false)
                putString(KEY_ERROR, "serviceStatus: ${e.message}")
            }
        }
    }

    private fun getIdentity(): Bundle {
        val appDir = configDir()
        val runtimeId = runCatching {
            FFI.getMyId(appDir.absolutePath)
        }.getOrElse {
            Log.w(TAG, "getIdentity runtime id unavailable: ${it.message}")
            ""
        }.trim()

        val rustDeskId = normalizeRustDeskId(runtimeId)
            ?: readRustDeskIdFromConfig()

        return Bundle().apply {
            putBoolean(KEY_SUCCESS, true)
            putString(KEY_RUSTDESK_ID, rustDeskId.orEmpty())
            putString(KEY_PATH, appDir.absolutePath)
        }
    }

    private fun isAuthorizedCaller(): Boolean {
        val callingUid = Binder.getCallingUid()
        if (callingUid == android.os.Process.SYSTEM_UID || callingUid == android.os.Process.ROOT_UID) {
            return true
        }
        val ctx = context ?: return false
        val packages = ctx.packageManager.getPackagesForUid(callingUid).orEmpty()
        return packages.contains(AGENT_PACKAGE)
    }

    private fun configDir(): File {
        val ctx = context ?: error("context unavailable")
        return File(ctx.applicationInfo.dataDir, "app_flutter").apply {
            if (!exists()) mkdirs()
        }
    }

    private fun isMdmAudioEnabled(): Boolean {
        val ctx = context ?: return false
        return ctx.getSharedPreferences(KEY_SHARED_PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_MDM_AUDIO_ENABLED, false)
    }

    private fun webRtcCapabilities(appDir: File): String {
        return runCatching {
            FFI.getWebRtcCapabilities(appDir.absolutePath)
        }.getOrElse {
            Log.w(TAG, "WebRTC capability query failed: ${it.message}")
            "{\"data_channel_compiled\":false,\"media_track_compiled\":false,\"fallback_transport\":\"rustdesk-websocket-hbbs-hbbr\"}"
        }
    }

    private fun readToml(file: File): List<String> {
        return runCatching {
            if (file.exists()) file.readLines() else emptyList()
        }.getOrDefault(emptyList())
    }

    private fun writeToml(file: File, lines: List<String>) {
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n").trimEnd() + "\n")
    }

    private fun mergeSection(
        lines: List<String>,
        section: String,
        values: LinkedHashMap<String, String>
    ): List<String> {
        val keys = values.keys
        val header = "[$section]"
        val output = mutableListOf<String>()
        var inTarget = false
        var sawTarget = false
        var inserted = false

        for (line in lines) {
            val trimmed = line.trim()
            val isSection = trimmed.startsWith("[") && trimmed.endsWith("]")
            if (isSection && inTarget && !inserted) {
                appendTomlPairs(output, values)
                inserted = true
            }
            if (trimmed == header) {
                inTarget = true
                sawTarget = true
                output += line
                continue
            }
            if (isSection) {
                inTarget = false
            }
            if (inTarget && keys.any { isTomlKeyLine(trimmed, it) }) {
                continue
            }
            output += line
        }

        if (inTarget && !inserted) {
            appendTomlPairs(output, values)
            inserted = true
        }
        if (!sawTarget) {
            if (output.isNotEmpty() && output.last().isNotBlank()) {
                output += ""
            }
            output += header
            appendTomlPairs(output, values)
        }
        return output
    }

    private fun appendTomlPairs(output: MutableList<String>, values: LinkedHashMap<String, String>) {
        for ((key, value) in values) {
            if (value.isBlank()) {
                continue
            }
            output += "$key = ${tomlString(value)}"
        }
    }

    private fun isTomlKeyLine(trimmedLine: String, key: String): Boolean {
        return trimmedLine.startsWith("$key ") || trimmedLine.startsWith("$key=")
    }

    private fun tomlString(value: String): String {
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n") + "\""
    }

    private fun success(file: File): Bundle {
        return Bundle().apply {
            putBoolean(KEY_SUCCESS, true)
            putString(KEY_PATH, file.absolutePath)
        }
    }

    private fun readRustDeskIdFromConfig(): String? {
        val candidates = listOf(
            File(configDir(), RUSTDESK_TOML),
            File(configDir(), RUSTDESK2_TOML)
        )
        for (file in candidates) {
            val id = parseRustDeskId(readToml(file).joinToString("\n"))
            if (!id.isNullOrBlank()) {
                return id
            }
        }
        return null
    }

    private fun parseRustDeskId(content: String): String? {
        val idRegex = Regex("""(?m)^\s*id\s*=\s*["']?([0-9][0-9\s-]{5,})["']?""")
        return idRegex.find(content)?.groupValues?.getOrNull(1)?.let { normalizeRustDeskId(it) }
    }

    private fun normalizeRustDeskId(value: String?): String? {
        val digits = value.orEmpty().filter { it.isDigit() }
        return digits.takeIf { it.length >= 6 }
    }

    companion object {
        private const val TAG = "MdmControlProvider"
        private const val AGENT_PACKAGE = "com.decard.mdm.agent"
        private const val RUSTDESK_TOML = "RustDesk.toml"
        private const val RUSTDESK2_TOML = "RustDesk2.toml"
        private const val RUSTDESK_PACKAGE_NAME = "com.carriez.flutter_hbb"

        private const val METHOD_SET_SERVER_CONFIG = "set_server_config"
        private const val METHOD_SET_AUDIO_ENABLED = "set_audio_enabled"
        private const val METHOD_SET_SESSION_PASSWORD = "set_session_password"
        private const val METHOD_CLEAR_SESSION_PASSWORD = "clear_session_password"
        private const val METHOD_START_SERVICE = "start_service"
        private const val METHOD_REQUEST_MEDIA_PROJECTION = "request_media_projection"
        private const val METHOD_SWITCH_MDM_SCREENRECORD = "switch_mdm_screenrecord"
        private const val METHOD_STOP_CAPTURE = "stop_capture"
        private const val METHOD_STOP_SERVICE = "stop_service"
        private const val METHOD_SERVICE_STATUS = "service_status"
        private const val METHOD_GET_IDENTITY = "get_identity"

        private const val EXTRA_HBBS = "hbbs"
        private const val EXTRA_HBBR = "hbbr"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_PASSWORD = "password"
        private const val EXTRA_FROM_BOOT = "from_boot"
        private const val EXTRA_STREAM_QUALITY = "stream_quality"
        private const val EXTRA_MAX_BITRATE_BPS = "max_bitrate_bps"
        private const val EXTRA_MAX_FPS = "max_fps"
        private const val EXTRA_IDLE_FPS = "idle_fps"
        private const val EXTRA_SCALE_DOWN_BY = "scale_resolution_down_by"
        private const val EXTRA_IMAGE_QUALITY = "image_quality"
        private const val EXTRA_CUSTOM_IMAGE_QUALITY = "custom_image_quality"
        private const val EXTRA_CODEC_PREFERENCE = "codec_preference"
        private const val EXTRA_AUDIO_ENABLED = "audio_enabled"
        private const val EXTRA_START_SERVER = "start_server"
        private const val EXTRA_FILE_TRANSFER_ENABLED = "file_transfer_enabled"
        private const val EXTRA_TCP_TUNNEL_ENABLED = "tcp_tunnel_enabled"
        private const val EXTRA_CONNECTION_STRATEGY = "connection_strategy"
        private const val EXTRA_TRANSPORT = "transport"
        private const val EXTRA_MTU = "mtu"
        private const val EXTRA_WEBRTC_ICE_SERVERS = "webrtc_ice_servers"
        private const val EXTRA_ICE_SERVERS_JSON = "ice_servers_json"
        private const val EXTRA_ICE_SERVERS = "ice_servers"
        private const val EXTRA_WEBRTC_TRANSPORT = "webrtc_transport_preference"
        private const val EXTRA_TRANSPORT_PREFERENCE = "transport_preference"
        private const val EXTRA_SESSION_TRANSPORT = "session_transport"
        private const val EXTRA_WEBRTC_ICE_TRANSPORT = "webrtc_ice_transport_policy"
        private const val EXTRA_ICE_TRANSPORT_POLICY = "ice_transport_policy"
        private const val EXTRA_WEBRTC_ALLOW_DEFAULT_STUN = "webrtc_allow_default_stun"
        private const val EXTRA_WEBRTC_MEDIA_ENABLED = "webrtc_media_enabled"
        private const val EXTRA_WEBRTC_ENABLED = "webrtc_enabled"
        private const val EXTRA_WEBRTC_BACKEND_SIGNALING_ENABLED =
            "webrtc_backend_signaling_enabled"
        private const val EXTRA_WEBRTC_DATA_CHANNEL_ENABLED = "webrtc_data_channel_enabled"
        private const val EXTRA_WEBRTC_MEDIA_TRACK_ENABLED = "webrtc_media_track_enabled"
        private const val EXTRA_WEBRTC_TURN_ENABLED = "webrtc_turn_enabled"
        private const val EXTRA_WEBRTC_AUTOMATIC_FALLBACK_ENABLED =
            "webrtc_automatic_fallback_enabled"
        private const val EXTRA_WEBRTC_SELF_HEALING_ENABLED = "webrtc_self_healing_enabled"
        private const val EXTRA_WEBRTC_SIGNALING_MODE = "webrtc_signaling_mode"
        private const val EXTRA_WEBRTC_OFFER_ENDPOINT = "webrtc_offer_endpoint"
        private const val METHOD_ACCEPT_WEBRTC_OFFER = "accept_webrtc_offer"
        private const val KEY_WEBRTC_ANSWER_ENDPOINT = "webrtc_answer_endpoint"

        private const val KEY_SUCCESS = "success"
        private const val KEY_ERROR = "error"
        private const val KEY_PATH = "path"
        private const val KEY_STATUS_RUNNING = "running"
        private const val KEY_STATUS_FOREGROUND = "foreground"
        private const val KEY_STATUS_MEDIA_READY = "media_ready"
        private const val KEY_STATUS_CAPTURING = "capturing"
        private const val KEY_STATUS_INPUT_READY = "input_ready"
        private const val KEY_STATUS_AUDIO_ENABLED = "audio_enabled"
        private const val KEY_STATUS_AUDIO_RUNNING = "audio_running"
        private const val KEY_STATUS_CAPTURE_FRAMES = "capture_frames"
        private const val KEY_STATUS_CAPTURE_DUPLICATE_FRAMES = "capture_duplicate_frames"
        private const val KEY_STATUS_CAPTURE_BYTES = "capture_bytes"
        private const val KEY_STATUS_CAPTURE_DROPPED_FRAMES = "capture_dropped_frames"
        private const val KEY_STATUS_CAPTURE_ERRORS = "capture_errors"
        private const val KEY_STATUS_CAPTURE_STARTED_AT = "capture_started_at"
        private const val KEY_STATUS_CAPTURE_LAST_FRAME_AT = "capture_last_frame_at"
        private const val KEY_STATUS_CAPTURE_LAST_FRAME_AGE_MS = "capture_last_frame_age_ms"
        private const val KEY_STATUS_CAPTURE_DURATION_MS = "capture_duration_ms"
        private const val KEY_STATUS_CAPTURE_AVG_FPS = "capture_avg_fps"
        private const val KEY_STATUS_CAPTURE_WIDTH = "capture_width"
        private const val KEY_STATUS_CAPTURE_HEIGHT = "capture_height"
        private const val KEY_STATUS_CAPTURE_SCALE = "capture_scale"
        private const val KEY_STATUS_CAPTURE_DPI = "capture_dpi"
        private const val KEY_STATUS_CAPTURE_SOURCE = "capture_source"
        private const val KEY_STATUS_CAPTURE_LAST_ERROR = "capture_last_error"
        private const val KEY_STATUS_WEBRTC_CAPABILITIES = "webrtc_capabilities"
        private const val KEY_FORCE_CAPTURE_RESTART = "force_capture_restart"
        private const val KEY_RUSTDESK_ID = "rustdesk_id"
        private const val CAPTURE_SOURCE_MDM_SCREENRECORD = "mdm_screenrecord"
        private const val MAX_WEBRTC_ICE_SERVERS = 8
        private const val MAX_WEBRTC_URLS_PER_SERVER = 4
        private const val MAX_WEBRTC_ICE_SERVER_LENGTH = 512
        private const val MAX_WEBRTC_ICE_CREDENTIAL_LENGTH = 1024
        private val WEBRTC_ICE_SERVER_PATTERN =
            Regex("^(stun|stuns|turn|turns):(?:/{0,2})[^\\s,]+$", RegexOption.IGNORE_CASE)

        // mdm-agent 拉起 service 的 action (无 mediaProjection, 用于纯后台驻留)
        // 与 ACT_INIT_MEDIA_PROJECTION_AND_SERVICE 区别: 不弹投屏确认, 不需要 mediaProjection intent
        // MainService.onStartCommand 需要识别并走纯 FFI 启动路径
        const val ACT_START_NO_PROJECTION = "com.carriez.flutter_hbb.START_NO_PROJECTION"
    }
}
