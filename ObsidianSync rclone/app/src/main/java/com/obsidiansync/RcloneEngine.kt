package com.obsidiansync

import android.content.Context
import java.io.File

object RcloneEngine {
    fun runCommand(ctx: Context, params: List<String>, onLog: (String) -> Unit, onFinished: (Boolean) -> Unit) {
        Thread {
            try {
                val binary = File(ctx.applicationInfo.nativeLibraryDir, "librclone.so").absolutePath
                val config = File(ctx.filesDir, "rclone.conf").absolutePath
                val cmd = mutableListOf(binary, "--config", config).apply { addAll(params) }

                onLog(">>> Run: ${params.joinToString(" ")}")
                val process = ProcessBuilder(cmd).redirectErrorStream(true).start()

                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { onLog(it) }
                }
                process.waitFor()
                onFinished(process.exitValue() == 0)
            } catch (e: Exception) {
                onLog("Error: ${e.message}")
                e.printStackTrace()
                onFinished(false)
            }
        }.start()
    }
}