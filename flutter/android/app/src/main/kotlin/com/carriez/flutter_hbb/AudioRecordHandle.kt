package com.carriez.flutter_hbb

import ffi.FFI

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_FLOAT //  ENCODING_OPUS need API 30
const val AUDIO_SAMPLE_RATE = 48000
const val AUDIO_OUTPUT_CHANNELS = 2
private const val AUDIO_BYTES_PER_SAMPLE = 4
private const val AUDIO_FRAMES_PER_OPUS_BATCH = 480

class AudioRecordHandle(private var context: Context, private var isVideoStart: ()->Boolean, private var isAudioStart: ()->Boolean) {
    private val logTag = "LOG_AUDIO_RECORD_HANDLE"

    private var audioRecorder: AudioRecord? = null
    private var audioReader: AudioReader? = null
    private var minBufferSize = 0
    private var audioRecordStat = false
    private var audioThread: Thread? = null
    private var inputChannelCount = AUDIO_OUTPUT_CHANNELS
    private var recorderMode = "unknown"
    private var recorderSource = "unknown"
    private var audioFrameCount = 0L
    private var audioByteCount = 0L
    private var audioMaxAbsObserved = 0f
    private var lastAudioMaxAbs = 0f
    private var lastAudioStatsLogAt = 0L

    @SuppressLint("MissingPermission", "NewApi")
    @RequiresApi(Build.VERSION_CODES.M)
    fun createAudioRecorder(inVoiceCall: Boolean, mediaProjection: MediaProjection?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.w(logTag, "createAudioRecorder failed, unsupported sdk=${Build.VERSION.SDK_INT}")
            return false
        }
        if (ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(logTag, "createAudioRecorder failed, no RECORD_AUDIO permission")
            return false
        }

        stopAudioRecorder()
        if (audioThread?.isAlive == true) {
            Log.w(logTag, "MDM-AudioRecorderCreateFailed reason=previous_stop_timeout")
            return false
        }
        audioReader = null
        minBufferSize = 0

        val requests = mutableListOf<AudioRecorderRequest>()
        if (inVoiceCall) {
            requests += AudioRecorderRequest(
                mode = "voice_call",
                source = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sourceLabel = "VOICE_COMMUNICATION"
            )
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
                requests += AudioRecorderRequest(
                    mode = "playback_capture",
                    playbackProjection = mediaProjection,
                    sourceLabel = "AudioPlaybackCaptureConfiguration"
                )
            }
            requests += AudioRecorderRequest(
                mode = "mic_fallback",
                source = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sourceLabel = "VOICE_COMMUNICATION"
            )
            requests += AudioRecorderRequest(
                mode = "mic_fallback",
                source = MediaRecorder.AudioSource.MIC,
                sourceLabel = "MIC"
            )
        }

        for (request in requests) {
            for (channelMask in listOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)) {
                val channelCount = channelCountForMask(channelMask)
                val bufferBytes = normalizedBufferSize(channelMask, channelCount)
                if (bufferBytes <= 0) {
                    Log.w(
                        logTag,
                        "MDM-AudioRecorderConfigSkip mode=${request.mode} source=${request.sourceLabel} " +
                            "sdk=${Build.VERSION.SDK_INT} channel_mask=$channelMask reason=min_buffer_invalid"
                    )
                    continue
                }
                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AUDIO_ENCODING)
                    .setSampleRate(AUDIO_SAMPLE_RATE)
                    .setChannelMask(channelMask)
                    .build()
                val builder = AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferBytes)

                if (request.playbackProjection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val apcc = AudioPlaybackCaptureConfiguration.Builder(request.playbackProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_ALARM)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()
                    builder.setAudioPlaybackCaptureConfig(apcc)
                } else if (request.source != null) {
                    builder.setAudioSource(request.source)
                }

                val recorder = try {
                    builder.build()
                } catch (e: Exception) {
                    Log.w(
                        logTag,
                        "MDM-AudioRecorderCreateFailed mode=${request.mode} source=${request.sourceLabel} " +
                            "sdk=${Build.VERSION.SDK_INT} input_channels=$channelCount buffer_bytes=$bufferBytes error=${e.message}"
                    )
                    null
                } ?: continue

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(
                        logTag,
                        "MDM-AudioRecorderCreateFailed mode=${request.mode} source=${request.sourceLabel} " +
                            "sdk=${Build.VERSION.SDK_INT} input_channels=$channelCount state=${recorder.state}"
                    )
                    recorder.release()
                    continue
                }

                audioRecorder = recorder
                minBufferSize = bufferBytes
                inputChannelCount = channelCount
                recorderMode = request.mode
                recorderSource = request.sourceLabel
                audioReader = AudioReader(
                    readBufSize = minBufferSize,
                    bytesPerSample = AUDIO_BYTES_PER_SAMPLE,
                    inputChannels = inputChannelCount,
                    outputChannels = AUDIO_OUTPUT_CHANNELS,
                    maxFrames = 4
                )
                Log.i(
                    logTag,
                    "MDM-AudioRecorderCreated mode=$recorderMode source=$recorderSource sdk=${Build.VERSION.SDK_INT} " +
                        "sample_rate=$AUDIO_SAMPLE_RATE input_channels=$inputChannelCount " +
                        "output_channels=$AUDIO_OUTPUT_CHANNELS encoding=PCM_FLOAT buffer_bytes=$minBufferSize"
                )
                return true
            }
        }

        Log.e(
            logTag,
            "MDM-AudioRecorderCreateFailed mode=all sdk=${Build.VERSION.SDK_INT} " +
                "mediaProjection=${mediaProjection != null} inVoiceCall=$inVoiceCall"
        )
        return false
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun startAudioRecorder(): Boolean {
        if (audioReader != null && audioRecorder != null && minBufferSize != 0) {
            try {
                FFI.setFrameRawEnable("audio", true)
                audioRecorder!!.startRecording()
                audioRecordStat = true
                audioFrameCount = 0L
                audioByteCount = 0L
                audioMaxAbsObserved = 0f
                lastAudioMaxAbs = 0f
                lastAudioStatsLogAt = System.currentTimeMillis()
                Log.i(
                    logTag,
                    "MDM-AudioRecorderStart mode=$recorderMode source=$recorderSource sdk=${Build.VERSION.SDK_INT} " +
                        "buffer_bytes=$minBufferSize input_channels=$inputChannelCount output_channels=$AUDIO_OUTPUT_CHANNELS"
                )
                audioThread = thread {
                    // MDM: keep the original blocking readSync to avoid
                    // BufferOverflow / index arithmetic bugs the previous
                    // pacing experiment hit on API 28. Stall detection and
                    // jitter smoothing are now handled in the rust side
                    // (audio_service.rs).
                    var consecutiveErrors = 0
                    while (audioRecordStat) {
                        try {
                            audioReader!!.readSync(audioRecorder!!)?.let {
                                recordAudioFrame(it)
                                FFI.onAudioFrameUpdate(it)
                            }
                            consecutiveErrors = 0
                        } catch (e: Throwable) {
                            consecutiveErrors += 1
                            Log.e(
                                logTag,
                                "MDM-AudioThreadError err=$e consecutive=$consecutiveErrors"
                            )
                            if (consecutiveErrors >= 5) {
                                break
                            }
                            try { Thread.sleep(20) } catch (_: InterruptedException) {}
                        }
                    }
                    // let's release here rather than onDestroy to avoid threading issue
                    audioRecorder?.release()
                    audioRecorder = null
                    minBufferSize = 0
                    FFI.setFrameRawEnable("audio", false)
                    Log.i(
                        logTag,
                "MDM-AudioRecorderStop mode=$recorderMode source=$recorderSource " +
                            "frames=$audioFrameCount bytes=$audioByteCount max_abs=$audioMaxAbsObserved"
                    )
                }
                return true
            } catch (e: Exception) {
                audioRecordStat = false
                FFI.setFrameRawEnable("audio", false)
                runCatching { audioRecorder?.release() }
                audioRecorder = null
                audioReader = null
                minBufferSize = 0
                Log.w(logTag, "MDM-AudioRecorderStartFailed mode=$recorderMode source=$recorderSource error=$e")
                return false
            }
        } else {
            Log.w(logTag, "MDM-AudioRecorderStartFailed reason=not_created")
            return false
        }
    }

    fun onVoiceCallStarted(mediaProjection: MediaProjection?): Boolean {
        if (!isSupportVoiceCall()) {
            return false
        }
        // No need to check if video or audio is started here.
        if (!switchToVoiceCall(mediaProjection)) {
            return false
        }
        return true
    }

    fun onVoiceCallClosed(mediaProjection: MediaProjection?): Boolean {
        // Return true if not supported, because is was not started.
        if (!isSupportVoiceCall()) {
            return true
        }
        if (isVideoStart()) {
            switchOutVoiceCall(mediaProjection)
        }
        tryReleaseAudio()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun switchToVoiceCall(mediaProjection: MediaProjection?): Boolean {
        audioRecorder?.let {
            if (it.getAudioSource() == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                return true
            }
        }
        stopAudioRecorder()

        if (!createAudioRecorder(true, mediaProjection)) {
            Log.e(logTag, "createAudioRecorder fail")
            return false
        }
        return startAudioRecorder()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun switchOutVoiceCall(mediaProjection: MediaProjection?): Boolean {
        audioRecorder?.let {
            if (it.getAudioSource() != MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                return true
            }
        }
        stopAudioRecorder()

        if (!createAudioRecorder(false, mediaProjection)) {
            Log.e(logTag, "createAudioRecorder fail")
            return false
        }
        return startAudioRecorder()
    }

    fun tryReleaseAudio() {
        if (isAudioStart() || isVideoStart()) {
            return
        }
        stopAudioRecorder()
    }

    fun stopAudioRecorder() {
        audioRecordStat = false
        runCatching { audioRecorder?.stop() }
        val thread = audioThread
        if (thread != null && thread != Thread.currentThread()) {
            thread.join(1500)
            if (thread.isAlive) {
                Log.w(logTag, "MDM-AudioRecorderStop timeout mode=$recorderMode source=$recorderSource")
            } else {
                audioThread = null
            }
        } else if (thread == null) {
            runCatching { audioRecorder?.release() }
            audioRecorder = null
            audioReader = null
            minBufferSize = 0
            FFI.setFrameRawEnable("audio", false)
        }
    }

    fun destroy() {
        Log.d(logTag, "destroy audio record handle")

        stopAudioRecorder()
    }

    private fun channelCountForMask(channelMask: Int): Int {
        return if (channelMask == AudioFormat.CHANNEL_IN_MONO) 1 else 2
    }

    private fun normalizedBufferSize(channelMask: Int, channelCount: Int): Int {
        val minBytes = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            channelMask,
            AUDIO_ENCODING
        )
        if (minBytes <= 0) {
            return 0
        }
        val inputFrameBytes = channelCount * AUDIO_BYTES_PER_SAMPLE
        val minFrames = (minBytes + inputFrameBytes - 1) / inputFrameBytes
        val normalizedFrames = ((minFrames + AUDIO_FRAMES_PER_OPUS_BATCH - 1) / AUDIO_FRAMES_PER_OPUS_BATCH) *
            AUDIO_FRAMES_PER_OPUS_BATCH
        return normalizedFrames * inputFrameBytes
    }

    private fun recordAudioFrame(buffer: ByteBuffer) {
        val bytes = buffer.capacity()
        val frames = ++audioFrameCount
        audioByteCount += bytes.toLong().coerceAtLeast(0L)
        lastAudioMaxAbs = maxAbs(buffer)
        audioMaxAbsObserved = max(audioMaxAbsObserved, lastAudioMaxAbs)
        val now = System.currentTimeMillis()
        if (frames == 1L || now - lastAudioStatsLogAt >= 5000L) {
            lastAudioStatsLogAt = now
            Log.i(
                logTag,
                "MDM-AudioFrameStats mode=$recorderMode source=$recorderSource " +
                    "frames=$frames bytes=$audioByteCount last_frame_bytes=$bytes " +
                    "last_max_abs=$lastAudioMaxAbs max_abs=$audioMaxAbsObserved"
            )
        }
    }

    private fun maxAbs(buffer: ByteBuffer): Float {
        val reader = buffer.duplicate().order(ByteOrder.nativeOrder())
        reader.rewind()
        var maxValue = 0f
        while (reader.remaining() >= AUDIO_BYTES_PER_SAMPLE) {
            maxValue = max(maxValue, abs(reader.getFloat()))
        }
        return maxValue
    }

    private data class AudioRecorderRequest(
        val mode: String,
        val source: Int? = null,
        val sourceLabel: String,
        val playbackProjection: MediaProjection? = null
    )
}
