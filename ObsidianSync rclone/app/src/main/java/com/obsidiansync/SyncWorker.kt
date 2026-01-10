package com.obsidiansync

import android.accounts.Account
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.auth.GoogleAuthUtil
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.regex.Pattern
import kotlin.concurrent.thread

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private var currentProgress = 0
    private val NOTIFICATION_ID_RUNNING = 1
    private val NOTIFICATION_ID_RESULT = 2
    private val CHANNEL_ID = "obsidian_sync_channel"

    override suspend fun doWork(): Result {
        val isUpload = inputData.getBoolean("is_upload", true)
        val context = applicationContext

        // --- QUAN TRỌNG: BẬT CHẾ ĐỘ FOREGROUND NGAY LẬP TỨC ---
        // Đây là tấm khiên bảo vệ App khỏi bị Android giết khi chạy ngầm
        setForeground(createForegroundInfo("🚀 Đang khởi động..."))

        val localPath = AppConfig.getLocalPath(context)
        val remotePath = AppConfig.getRemotePath(context)

        val source = if (isUpload) localPath else remotePath
        val dest = if (isUpload) remotePath else localPath
        val modeText = if (isUpload) "Upload" else "Download"

        // --- BƯỚC 0: REFRESH TOKEN ---
        try {
            val email = AppConfig.getUserEmail(context)
            if (email != null) {
                val account = Account(email, "com.google")
                val newToken = GoogleAuthUtil.getToken(context, account, "oauth2:https://www.googleapis.com/auth/drive")
                val configFile = File(context.filesDir, "rclone.conf")
                if (configFile.exists()) {
                    val currentConfig = configFile.readText()
                    val rootFolderLine = currentConfig.lines().find { it.startsWith("root_folder_id") } ?: ""
                    val newConfigContent = """
[gdrive]
type = drive
scope = drive
token = {"access_token":"$newToken","token_type":"Bearer","expiry":"2030-01-01T00:00:00.000000+07:00"}
$rootFolderLine
""".trimIndent()
                    configFile.writeText(newConfigContent)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        // KHỞI ĐỘNG PROXY
        val proxyPort = 10800
        val proxy = TinyProxy(proxyPort)
        proxy.start()

        return try {
            val libPath = context.applicationInfo.nativeLibraryDir
            val binaryFile = File(libPath, "librclone.so")

            if (!binaryFile.exists()) {
                showResultNotification("❌ Lỗi: Thiếu file librclone.so")
                return Result.failure()
            }

            val configPath = File(context.filesDir, "rclone.conf").absolutePath
            val cachePath = context.cacheDir.absolutePath
            val homePath = context.filesDir.absolutePath

            // --- LỆNH SYNC ---
            val cmd = listOf(
                binaryFile.absolutePath,
                "--config", configPath,
                "sync", source, dest,
                "--transfers", "4",
                "--checkers", "8",
                "--delete-during",
                "--create-empty-src-dirs",
                "--progress",
                "--cache-dir", cachePath,
                "--no-check-certificate",
                "--user-agent", "ObsidianSyncAndroid",
                "--drive-chunk-size", "32M",
                "--drive-use-trash=false"
            )

            val processBuilder = ProcessBuilder(cmd)
            processBuilder.redirectErrorStream(true)
            processBuilder.directory(context.filesDir)

            val env = processBuilder.environment()
            env["LD_LIBRARY_PATH"] = libPath
            env["HOME"] = homePath
            env["TMPDIR"] = cachePath
            env["XDG_CONFIG_HOME"] = homePath
            env["XDG_CACHE_HOME"] = cachePath

            val proxyUrl = "http://127.0.0.1:$proxyPort"
            env["http_proxy"] = proxyUrl
            env["https_proxy"] = proxyUrl
            env["HTTP_PROXY"] = proxyUrl
            env["HTTPS_PROXY"] = proxyUrl

            val process = processBuilder.start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            var line: String?
            val globalProgressRegex = Pattern.compile("Transferred:.*, (\\d+)%")

            while (reader.readLine().also { line = it } != null) {
                line?.let { logLine ->
                    val matcher = globalProgressRegex.matcher(logLine)
                    if (matcher.find()) {
                        val percent = matcher.group(1)?.toIntOrNull() ?: 0
                        if (percent > currentProgress && percent < 100) {
                            currentProgress = percent
                            // Cập nhật % lên App
                            setProgress(workDataOf("progress" to currentProgress))
                            // Cập nhật % lên Thanh thông báo
                            updateForegroundNotification("Đang $modeText: $percent%")
                        }
                    }
                }
            }

            process.waitFor()
            val exitCode = process.exitValue()
            proxy.stop()

            if (exitCode == 0) {
                showResultNotification("✅ $modeText hoàn tất!")
                setProgress(workDataOf("progress" to 100))
                return Result.success()
            } else {
                showResultNotification("❌ Lỗi Sync (Code: $exitCode)")
                return Result.failure()
            }

        } catch (e: Exception) {
            e.printStackTrace()
            proxy.stop()
            showResultNotification("❌ Crash: ${e.message}")
            return Result.failure()
        }
    }

    // --- HÀM TẠO THÔNG BÁO FOREGROUND (BẮT BUỘC) ---
    private fun createForegroundInfo(message: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Obsidian Sync", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Obsidian Sync")
            .setContentText(message)
            .setOngoing(true) // Không cho vuốt tắt
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // Android 14 yêu cầu khai báo Type
        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(NOTIFICATION_ID_RUNNING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID_RUNNING, notification)
        }
    }

    // Cập nhật nội dung thông báo đang chạy
    private suspend fun updateForegroundNotification(message: String) {
        setForeground(createForegroundInfo(message))
    }

    // Thông báo kết quả (dùng ID khác để không bị xóa khi Worker tắt)
    private fun showResultNotification(message: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Obsidian Sync")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(NOTIFICATION_ID_RESULT, notification)
    }

    class TinyProxy(private val port: Int) {
        private var serverSocket: ServerSocket? = null
        @Volatile private var isRunning = true
        fun start() {
            thread {
                try {
                    serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
                    while (isRunning) {
                        val client = serverSocket?.accept() ?: break
                        thread { handleClient(client) }
                    }
                } catch (e: Exception) {}
            }
        }
        fun stop() { isRunning = false; try { serverSocket?.close() } catch (e: Exception) {} }
        private fun handleClient(client: Socket) {
            try {
                val input = client.getInputStream()
                val reader = BufferedReader(InputStreamReader(input))
                val requestLine = reader.readLine() ?: return
                if (!requestLine.startsWith("CONNECT")) { client.close(); return }
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val hostPort = parts[1].split(":")
                val host = hostPort[0]
                val port = hostPort.getOrNull(1)?.toInt() ?: 443
                while (reader.readLine().isNotEmpty()) {}
                val remote = Socket(host, port)
                val output = client.getOutputStream()
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                output.flush()
                thread { copyStream(input, remote.getOutputStream()) }
                copyStream(remote.getInputStream(), output)
                remote.close(); client.close()
            } catch (e: Exception) { try { client.close() } catch (ex: Exception) {} }
        }
        private fun copyStream(ins: InputStream, outs: OutputStream) {
            try {
                val buffer = ByteArray(4096)
                var bytesRead: Int
                while (ins.read(buffer).also { bytesRead = it } != -1) { outs.write(buffer, 0, bytesRead) }
                outs.flush()
            } catch (e: Exception) {}
        }
    }
}


