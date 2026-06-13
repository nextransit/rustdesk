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
import android.util.Log
import java.io.File
import java.security.SecureRandom

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
                METHOD_SET_SESSION_PASSWORD -> setSessionPassword(extras)
                METHOD_CLEAR_SESSION_PASSWORD -> clearSessionPassword()
                METHOD_START_SERVICE -> startService(extras)
                METHOD_STOP_SERVICE -> stopService()
                METHOD_SERVICE_STATUS -> serviceStatus()
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
        require(hbbs.isNotBlank()) { "hbbs is required" }
        require(hbbr.isNotBlank()) { "hbbr is required" }

        val options = linkedMapOf(
            "custom-rendezvous-server" to hbbs,
            "relay-server" to hbbr,
            "key" to key
        )
        val file = File(configDir(), RUSTDESK2_TOML)
        writeToml(file, mergeSection(readToml(file), "options", options))
        return success(file)
    }

    private fun setSessionPassword(extras: Bundle?): Bundle {
        val password = extras?.getString(EXTRA_PASSWORD)?.trim().orEmpty()
        require(password.isNotBlank()) { "password is required" }

        val config = linkedMapOf(
            "password" to password,
            "salt" to randomSalt()
        )
        val options = linkedMapOf("verification-method" to "use-permanent-password")
        val file = File(configDir(), RUSTDESK_TOML)
        val mergedConfig = mergeRootKeys(readToml(file), config)
        writeToml(file, mergeSection(mergedConfig, "options", options))
        return success(file)
    }

    private fun clearSessionPassword(): Bundle {
        val file = File(configDir(), RUSTDESK_TOML)
        val cleared = removeRootKeys(readToml(file), setOf("password", "salt"))
        writeToml(file, mergeSection(cleared, "options", linkedMapOf("verification-method" to "")))
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
            val isRunning = am.runningAppProcesses?.any {
                it.processName == RUSTDESK_PACKAGE_NAME
            } ?: false
            val isForeground = am.runningAppProcesses?.any {
                it.processName == RUSTDESK_PACKAGE_NAME && it.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            } ?: false
            Bundle().apply {
                putBoolean(KEY_SUCCESS, true)
                putBoolean(KEY_STATUS_RUNNING, isRunning)
                putBoolean(KEY_STATUS_FOREGROUND, isForeground)
            }
        } catch (e: Exception) {
            Log.e(TAG, "serviceStatus failed", e)
            Bundle().apply {
                putBoolean(KEY_SUCCESS, false)
                putString(KEY_ERROR, "serviceStatus: ${e.message}")
            }
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

    private fun readToml(file: File): List<String> {
        return runCatching {
            if (file.exists()) file.readLines() else emptyList()
        }.getOrDefault(emptyList())
    }

    private fun writeToml(file: File, lines: List<String>) {
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n").trimEnd() + "\n")
    }

    private fun mergeRootKeys(lines: List<String>, values: LinkedHashMap<String, String>): List<String> {
        val keys = values.keys
        val output = mutableListOf<String>()
        var inserted = false

        for (line in lines) {
            val trimmed = line.trim()
            if (!inserted && trimmed.startsWith("[") && trimmed.endsWith("]")) {
                appendTomlPairs(output, values)
                inserted = true
            }
            if (!trimmed.startsWith("[") && keys.any { isTomlKeyLine(trimmed, it) }) {
                continue
            }
            output += line
        }

        if (!inserted) {
            appendTomlPairs(output, values)
        }
        return output
    }

    private fun removeRootKeys(lines: List<String>, keys: Set<String>): List<String> {
        val output = mutableListOf<String>()
        var inRoot = true
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inRoot = false
            }
            if (inRoot && keys.any { isTomlKeyLine(trimmed, it) }) {
                continue
            }
            output += line
        }
        return output
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

    private fun randomSalt(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random = SecureRandom()
        return (1..32)
            .map { chars[random.nextInt(chars.length)] }
            .joinToString("")
    }

    private fun success(file: File): Bundle {
        return Bundle().apply {
            putBoolean(KEY_SUCCESS, true)
            putString(KEY_PATH, file.absolutePath)
        }
    }

    companion object {
        private const val TAG = "MdmControlProvider"
        private const val AGENT_PACKAGE = "com.decard.mdm.agent"
        private const val RUSTDESK_TOML = "RustDesk.toml"
        private const val RUSTDESK2_TOML = "RustDesk2.toml"
        private const val RUSTDESK_PACKAGE_NAME = "com.carriez.flutter_hbb"

        private const val METHOD_SET_SERVER_CONFIG = "set_server_config"
        private const val METHOD_SET_SESSION_PASSWORD = "set_session_password"
        private const val METHOD_CLEAR_SESSION_PASSWORD = "clear_session_password"
        private const val METHOD_START_SERVICE = "start_service"
        private const val METHOD_STOP_SERVICE = "stop_service"
        private const val METHOD_SERVICE_STATUS = "service_status"

        private const val EXTRA_HBBS = "hbbs"
        private const val EXTRA_HBBR = "hbbr"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_PASSWORD = "password"
        private const val EXTRA_FROM_BOOT = "from_boot"

        private const val KEY_SUCCESS = "success"
        private const val KEY_ERROR = "error"
        private const val KEY_PATH = "path"
        private const val KEY_STATUS_RUNNING = "running"
        private const val KEY_STATUS_FOREGROUND = "foreground"

        // mdm-agent 拉起 service 的 action (无 mediaProjection, 用于纯后台驻留)
        // 与 ACT_INIT_MEDIA_PROJECTION_AND_SERVICE 区别: 不弹投屏确认, 不需要 mediaProjection intent
        // MainService.onStartCommand 需要识别并走纯 FFI 启动路径
        const val ACT_START_NO_PROJECTION = "com.carriez.flutter_hbb.START_NO_PROJECTION"
    }
}
