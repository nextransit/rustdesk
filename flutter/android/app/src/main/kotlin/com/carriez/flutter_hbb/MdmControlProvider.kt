package com.carriez.flutter_hbb

import android.content.ContentProvider
import android.content.ContentValues
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

        private const val METHOD_SET_SERVER_CONFIG = "set_server_config"
        private const val METHOD_SET_SESSION_PASSWORD = "set_session_password"
        private const val METHOD_CLEAR_SESSION_PASSWORD = "clear_session_password"

        private const val EXTRA_HBBS = "hbbs"
        private const val EXTRA_HBBR = "hbbr"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_PASSWORD = "password"

        private const val KEY_SUCCESS = "success"
        private const val KEY_ERROR = "error"
        private const val KEY_PATH = "path"
    }
}
