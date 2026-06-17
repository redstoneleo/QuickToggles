package com.example.utils

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

object ShizukuHelper {
    private const val TAG = "ShizukuHelper"

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun requestPermission(context: android.content.Context, requestCode: Int = 0) {
        try {
            if (!Shizuku.pingBinder()) {
                try {
                    val fallbackIntent = android.content.Intent("moe.shizuku.privileged.api.ACTION_REQUEST_BINDER")
                    context.sendBroadcast(fallbackIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Fallback broadcast failed: ${e.message}")
                }
                android.widget.Toast.makeText(context, "正在尝试唤醒 Shizuku... 请在即将打开的 Shizuku App 中手动启用本软件的授权。", android.widget.Toast.LENGTH_LONG).show()
                val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (intent != null) {
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
                return
            }
            if (Shizuku.isPreV11()) {
                android.widget.Toast.makeText(context, "当前 Shizuku 版本过低，不支持直接唤起授权，请在 Shizuku App 中手动授权", android.widget.Toast.LENGTH_LONG).show()
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(requestCode)
            } else {
                android.widget.Toast.makeText(context, "Shizuku 已获得授权", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Shizuku permission: ${e.message}")
            android.widget.Toast.makeText(context, "请求 Shizuku 授权失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun runCommand(cmd: String): ShellUtils.CommandResult {
        if (!isShizukuAvailable()) {
            return ShellUtils.CommandResult(-1, "", "Shizuku is not available or permission not granted")
        }

        var process: rikka.shizuku.ShizukuRemoteProcess? = null
        var os: DataOutputStream? = null
        var reader: BufferedReader? = null
        val outputMsg = StringBuilder()

        try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            process = method.invoke(null, arrayOf("sh"), null, null) as rikka.shizuku.ShizukuRemoteProcess
            os = DataOutputStream(process.outputStream)
            
            os.writeBytes("$cmd\n")
            os.writeBytes("exit\n")
            os.flush()

            reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                outputMsg.append(line).append("\n")
            }

            val exitValue = process.waitFor()
            return ShellUtils.CommandResult(exitValue, outputMsg.toString().trim(), "")
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku command execution failed: ${e.message}")
            return ShellUtils.CommandResult(-1, "", e.message ?: "Unknown error")
        } finally {
            try { os?.close() } catch (e: Exception) {}
            try { reader?.close() } catch (e: Exception) {}
            try { process?.destroy() } catch (e: Exception) {}
        }
    }
}
