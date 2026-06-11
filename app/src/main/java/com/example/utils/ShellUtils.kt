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
        var reader: BufferedReader? = null
        val outputMsg = StringBuilder()
        try {
            val pb = if (useRoot) ProcessBuilder("su") else ProcessBuilder("sh")
            pb.redirectErrorStream(true) // Merge stderr into stdout to prevent buffer deadlocks
            process = pb.start()
            
            os = DataOutputStream(process.outputStream)
            for (cmd in cmds) {
                os.writeBytes("$cmd\n")
            }
            os.writeBytes("exit\n")
            os.flush()

            reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                outputMsg.append(line).append("\n")
            }

            val exitValue = process.waitFor()
            return CommandResult(exitValue, outputMsg.toString().trim(), "")
        } catch (e: Exception) {
            Log.e(TAG, "Command list execution failed ($cmds): ${e.message}")
            return CommandResult(-1, "", e.message ?: "Unknown error")
        } finally {
            try {
                os?.close()
                reader?.close()
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
