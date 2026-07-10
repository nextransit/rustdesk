package com.carriez.flutter_hbb

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import android.view.WindowManager
import ffi.FFI
import org.json.JSONArray
import org.json.JSONObject

object MediaCodecInfoBridge {
    private const val TAG = "MediaCodecInfoBridge"

    fun sync(context: Context) {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val codecArray = JSONArray()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val screenSize = getScreenSize(windowManager)
        val align = 64
        val width = (screenSize.first + align - 1) / align * align
        val height = (screenSize.second + align - 1) / align * align

        codecList.codecInfos.forEach { codec ->
            val hardwareAccelerated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                codec.isHardwareAccelerated
            } else {
                when {
                    listOf("OMX.google.", "OMX.SEC.", "c2.android").any {
                        codec.name.startsWith(it, true)
                    } -> false
                    listOf(
                        "c2.qti",
                        "OMX.qcom.video",
                        "OMX.Exynos",
                        "OMX.hisi",
                        "OMX.MTK",
                        "OMX.Intel",
                        "OMX.Nvidia"
                    ).any { codec.name.startsWith(it, true) } -> true
                    else -> false
                }
            }
            if (!hardwareAccelerated) {
                return@forEach
            }

            val mimeType = codec.supportedTypes.firstOrNull {
                it == "video/avc" || it == "video/hevc"
            } ?: return@forEach
            val capabilities = runCatching { codec.getCapabilitiesForType(mimeType) }
                .getOrNull() ?: return@forEach
            if (codec.isEncoder &&
                !capabilities.videoCapabilities.isSizeSupported(width, height) &&
                !capabilities.videoCapabilities.isSizeSupported(height, width)
            ) {
                return@forEach
            }
            val surface = capabilities.colorFormats.contains(COLOR_FormatSurface)
            val nv12 = capabilities.colorFormats.contains(COLOR_FormatYUV420SemiPlanar)
            if (!surface && !nv12) {
                return@forEach
            }

            val codecObject = JSONObject()
                .put("name", codec.name)
                .put("is_encoder", codec.isEncoder)
                .put("hw", true)
                .put("mime_type", mimeType)
                .put("min_width", capabilities.videoCapabilities.supportedWidths.lower)
                .put("max_width", capabilities.videoCapabilities.supportedWidths.upper)
                .put("min_height", capabilities.videoCapabilities.supportedHeights.lower)
                .put("max_height", capabilities.videoCapabilities.supportedHeights.upper)
                .put("surface", surface)
                .put("nv12", nv12)
                .put("min_bitrate", capabilities.videoCapabilities.bitrateRange.lower / 1000)
                .put("max_bitrate", capabilities.videoCapabilities.bitrateRange.upper / 1000)
            if (!codec.isEncoder && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                codecObject.put(
                    "low_latency",
                    capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
                )
            }
            if (codec.isEncoder) {
                codecArray.put(codecObject)
            }
        }

        val result = JSONObject()
            .put("version", Build.VERSION.SDK_INT)
            .put("w", width)
            .put("h", height)
            .put("codecs", codecArray)
        FFI.setCodecInfo(result.toString())
        Log.i(TAG, "synced hardware encoders=${codecArray.length()} size=${width}x$height")
    }
}
