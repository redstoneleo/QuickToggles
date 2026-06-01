package com.example.utils

import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

object ShellUtils {
    private const val TAG = "ShellUtils"

    fun isRootAvailable(): Boolean {
        var process: Process? = null
        var os: DataOutputStream? = null
        try {
            process = Runtime.getRuntime().exec("su")
            os = DataOutputStream(process.outputStream)
            os.writeBytes("exit\n")
            os.flush()
            val exitValue = process.waitFor()
            return exitValue == 0
        } catch (e: Exception) {
            Log.e(TAG, "Root check failed: ${e.message}")
            return false
        } finally {
            try {
                os?.close()
                process?.destroy()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun runCommand(cmd: String, useRoot: Boolean = true): CommandResult {
        return runCommands(listOf(cmd), useRoot)
    }

    fun runCommands(cmds: List<String>, useRoot: Boolean = true): CommandResult {
        var process: Process? = null
        var os: DataOutputStream? = null
        var successReader: BufferedReader? = null
        var errorReader: BufferedReader? = null
        val successMsg = StringBuilder()
        val errorMsg = StringBuilder()
        try {
            process = if (useRoot) {
                Runtime.getRuntime().exec("su")
            } else {
                Runtime.getRuntime().exec("sh")
            }
            os = DataOutputStream(process.outputStream)
            for (cmd in cmds) {
                os.writeBytes("$cmd\n")
            }
            os.writeBytes("exit\n")
            os.flush()

            successReader = BufferedReader(InputStreamReader(process.inputStream))
            errorReader = BufferedReader(InputStreamReader(process.errorStream))

            var line: String?
            while (successReader.readLine().also { line = it } != null) {
                successMsg.append(line).append("\n")
            }
            while (errorReader.readLine().also { line = it } != null) {
                errorMsg.append(line).append("\n")
            }

            val exitValue = process.waitFor()
            return CommandResult(exitValue, successMsg.toString().trim(), errorMsg.toString().trim())
        } catch (e: Exception) {
            Log.e(TAG, "Command list execution failed ($cmds): ${e.message}")
            return CommandResult(-1, "", e.message ?: "Unknown error")
        } finally {
            try {
                os?.close()
                successReader?.close()
                errorReader?.close()
                process?.destroy()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
        val isSuccess: Boolean get() = exitCode == 0
    }
}
