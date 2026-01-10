package com.obsidiansync

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object RcloneEngine {

    // Lấy đường dẫn file binary
    private fun getRcloneBinary(context: Context): String {
        val libPath = context.applicationInfo.nativeLibraryDir
        return File(libPath, "librclone.so").absolutePath
    }

    // Lấy đường dẫn file config (rclone.conf) nằm trong bộ nhớ riêng của App
    fun getConfigPath(context: Context): String {
        return File(context.filesDir, "rclone.conf").absolutePath
    }

    // Hàm chạy lệnh tổng quát (có trả về log realtime)
    // onLog: Hàm callback để cập nhật giao diện mỗi khi có dòng log mới
    // onFinished: Gọi khi lệnh chạy xong (true = thành công)
    fun runCommand(
        context: Context,
        params: List<String>,
        onLog: (String) -> Unit,
        onFinished: (Boolean) -> Unit
    ) {
        Thread {
            try {
                val binary = getRcloneBinary(context)
                val config = getConfigPath(context)

                // Tạo danh sách lệnh đầy đủ
                val command = mutableListOf(binary)
                // Luôn luôn trỏ vào file config riêng của app
                command.add("--config")
                command.add(config)

                command.addAll(params)

                onLog(">>> Đang chạy lệnh: ${params.joinToString(" ")}")

                val processBuilder = ProcessBuilder(command)
                // Quan trọng: Redirect lỗi (stderr) sang luồng chuẩn (stdout) để đọc được hết lỗi
                processBuilder.redirectErrorStream(true)

                val process = processBuilder.start()
                val reader = BufferedReader(InputStreamReader(process.inputStream))

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    // Gửi log ra ngoài (cần chạy trên UI Thread nếu cập nhật View,
                    // nhưng ở đây ta gửi String thô, bên ngoài tự xử lý)
                    line?.let { onLog(it) }
                }

                process.waitFor()
                onFinished(process.exitValue() == 0)

            } catch (e: Exception) {
                onLog("Lỗi nghiêm trọng: ${e.message}")
                e.printStackTrace()
                onFinished(false)
            }
        }.start()
    }
}