package com.example.utils

import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

object ShellUtils {
    private const val TAG = "ShellUtils"

    private val lock = Any()
    private var rootProcess: Process? = null
    private var rootOs: DataOutputStream? = null
    private var rootReader: BufferedReader? = null

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

    private fun initRootProcess() {
        if (rootProcess != null) return
        try {
            val pb = ProcessBuilder("su")
            pb.redirectErrorStream(true)
            val p = pb.start()
            rootProcess = p
            rootOs = DataOutputStream(p.outputStream)
            rootReader = BufferedReader(InputStreamReader(p.inputStream))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start persistent root shell", e)
            closeRootProcess()
        }
    }

    private fun closeRootProcess() {
        try { rootOs?.close() } catch (e: Exception) {}
        try { rootReader?.close() } catch (e: Exception) {}
        try { rootProcess?.destroy() } catch (e: Exception) {}
        rootProcess = null
        rootOs = null
        rootReader = null
    }

    fun runCommand(cmd: String, useRoot: Boolean = true, timeoutMs: Long? = 5000L): CommandResult {
        if (!useRoot) {
            return runCommandsInternal(listOf(cmd), false)
        }

        synchronized(lock) {
            initRootProcess()
            if (rootProcess == null || rootOs == null || rootReader == null) {
                return CommandResult(-1, "", "Failed to start root shell")
            }

            val outputMsg = StringBuilder()
            try {
                rootOs!!.writeBytes("$cmd\n")
                rootOs!!.writeBytes("echo \"===AISTUDIO_CMD_END===: $?\"\n")
                rootOs!!.flush()

                var exitCode = 0
                val future = java.util.concurrent.Executors.newSingleThreadExecutor().submit(java.util.concurrent.Callable {
                    while (true) {
                        val line = rootReader!!.readLine()
                        if (line == null) {
                            return@Callable -2 // died
                        }
                        if (line.startsWith("===AISTUDIO_CMD_END===: ")) {
                            exitCode = line.substringAfter("===AISTUDIO_CMD_END===: ").trim().toIntOrNull() ?: 0
                            return@Callable exitCode
                        }
                        if (line != "===AISTUDIO_CMD_END===:") {
                            outputMsg.append(line).append("\n")
                        }
                    }
                    return@Callable -1
                })

                val res = if (timeoutMs != null) {
                    try {
                        future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    } catch (e: java.util.concurrent.TimeoutException) {
                        future.cancel(true)
                        closeRootProcess()
                        return CommandResult(-1, outputMsg.toString().trim(), "Root shell timed out after $timeoutMs ms")
                    }
                } else {
                    future.get()
                }

                if (res == -2) {
                    closeRootProcess()
                    return CommandResult(-1, outputMsg.toString().trim(), "Root shell died")
                }

                return CommandResult(exitCode, outputMsg.toString().trim(), "")
            } catch (e: Exception) {
                Log.e(TAG, "Persistent root command execution failed: ${e.message}")
                closeRootProcess()
                return CommandResult(-1, "", e.message ?: "Unknown error")
            }
        }
    }

    fun runCommands(cmds: List<String>, useRoot: Boolean = true, timeoutMs: Long? = 5000L): CommandResult {
        if (useRoot) {
            val combinedCmd = cmds.joinToString("\n")
            return runCommand(combinedCmd, true, timeoutMs)
        }
        return runCommandsInternal(cmds, false)
    }

    private fun runCommandsInternal(cmds: List<String>, useRoot: Boolean = true): CommandResult {
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
