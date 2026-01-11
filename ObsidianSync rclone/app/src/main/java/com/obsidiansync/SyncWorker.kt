package com.obsidiansync

import android.app.*
import android.content.Context
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.google.android.gms.auth.GoogleAuthUtil
import android.accounts.Account
import java.io.File
import java.net.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val CHANNEL = "obsidian_sync"

    override suspend fun doWork(): Result {
        val isUpload = inputData.getBoolean("is_upload", true)
        setForeground(getNotif(if (isUpload) "Đang Upload..." else "Đang Download...", true))

        // 1. Refresh Token an toàn
        try {
            val email = AppConfig.getUserEmail(applicationContext) ?: return Result.failure()
            val token = GoogleAuthUtil.getToken(applicationContext, Account(email, "com.google"), "oauth2:https://www.googleapis.com/auth/drive")
            val conf = File(applicationContext.filesDir, "rclone.conf")
            if (conf.exists()) {
                val root = conf.readText().lines().find { it.startsWith("root_folder_id") } ?: ""
                conf.writeText("[gdrive]\ntype = drive\nscope = drive\ntoken = {\"access_token\":\"$token\",\"token_type\":\"Bearer\",\"expiry\":\"2030-01-01T00:00:00+07:00\"}\n$root")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Khởi động Proxy với Thread Pool
        val proxy = TinyProxy(10800).apply { start() }

        val lib = File(applicationContext.applicationInfo.nativeLibraryDir, "librclone.so").absolutePath
        val cache = applicationContext.cacheDir.absolutePath

        return try {
            val src = if (isUpload) AppConfig.getLocalPath(applicationContext) else AppConfig.getRemotePath(applicationContext)
            val dst = if (isUpload) AppConfig.getRemotePath(applicationContext) else AppConfig.getLocalPath(applicationContext)

            val pb = ProcessBuilder(
                lib, "--config", File(applicationContext.filesDir, "rclone.conf").absolutePath,
                "sync", src, dst,
                "--transfers", "4",
                "--checkers", "4", // Giảm checkers để tránh quá tải
                "--delete-during",
                "--create-empty-src-dirs",
                "--progress",
                "--cache-dir", cache,
                "--no-check-certificate",
                "--user-agent",
                "ObsidianSync",
                "--drive-chunk-size", "32M",
                "--drive-use-trash=false"
            ).redirectErrorStream(true).directory(applicationContext.filesDir)

            pb.environment().apply {
                put("LD_LIBRARY_PATH", applicationContext.applicationInfo.nativeLibraryDir)
                put("HOME", applicationContext.filesDir.absolutePath)
                put("TMPDIR", cache)
                put("http_proxy", "http://127.0.0.1:10800")
                put("https_proxy", "http://127.0.0.1:10800")
            }

            val process = pb.start()
            val regex = "Transferred:.*, (\\d+)%".toPattern()

            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    regex.matcher(line).takeIf { it.find() }?.group(1)?.toIntOrNull()?.let { p ->
                        setProgress(workDataOf("progress" to p))
                        if (p % 10 == 0) setForeground(getNotif("Đang Sync: $p%", true))
                    }
                }
            }

            if (process.waitFor() == 0) {
                Result.success()
            } else {
                notifyResult("❌ Lỗi Code: ${process.exitValue()}")
                Result.failure()
            }
        } catch (e: Exception) {
            notifyResult("❌ Crash: ${e.message}")
            Result.failure()
        } finally {
            proxy.stop()
        }
    }

    private fun getNotif(msg: String, running: Boolean): ForegroundInfo {
        val mgr = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) mgr.createNotificationChannel(NotificationChannel(CHANNEL, "Sync", NotificationManager.IMPORTANCE_LOW))

        val notif = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(if (running) android.R.drawable.stat_sys_download else android.R.drawable.stat_notify_error)
            .setContentTitle("Obsidian Sync").setContentText(msg).setOngoing(running).build()

        return if (Build.VERSION.SDK_INT >= 34) ForegroundInfo(1, notif, FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(1, notif)
    }

    private fun notifyResult(msg: String) = (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(2, getNotif(msg, false).notification)

    // --- FIX: DÙNG EXECUTOR SERVICE ĐỂ QUẢN LÝ THREAD ---
    class TinyProxy(private val port: Int) {
        @Volatile var isRunning = true
        private val executor = Executors.newCachedThreadPool() // Tự động tái sử dụng Thread
        private var serverSocket: ServerSocket? = null

        fun start() {
            // Chạy ServerSocket trên 1 thread riêng biệt
            executor.execute {
                try {
                    serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
                    // Set timeout để vòng lặp không bị treo vĩnh viễn khi stop()
                    serverSocket?.soTimeout = 2000

                    while (isRunning) {
                        try {
                            // Blocking call, đợi kết nối
                            val client = serverSocket?.accept()
                            if (client != null) {
                                // Xử lý kết nối bằng Thread Pool (KHÔNG TẠO THREAD MỚI VÔ TỘI VẠ)
                                executor.execute { handle(client) }
                            }
                        } catch (e: SocketTimeoutException) {
                            // Timeout để check biến isRunning, tiếp tục vòng lặp
                        } catch (e: Exception) {
                            if (isRunning) e.printStackTrace()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        fun stop() {
            isRunning = false
            try { serverSocket?.close() } catch (_: Exception) {}
            executor.shutdownNow() // Dừng tất cả các luồng xử lý
        }

        private fun handle(cl: Socket) = try { cl.use {
            val rd = it.getInputStream().bufferedReader()
            val req = rd.readLine()
            if (req?.startsWith("CONNECT") == true) {
                val parts = req.split(" ")
                val hostPort = parts[1].split(":")
                val host = hostPort[0]
                val port = hostPort.getOrNull(1)?.toInt() ?: 443

                // Đọc hết header thừa
                while (rd.readLine().isNotEmpty()) {}

                it.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".toByteArray())

                val rm = Socket(host, port)
                rm.use { remote ->
                    // Copy luồng dữ liệu 2 chiều
                    val t = Executors.newSingleThreadExecutor()
                    t.execute {
                        try { remote.inputStream.copyTo(it.getOutputStream()) } catch (_: Exception) {}
                    }
                    try { it.inputStream.copyTo(remote.getOutputStream()) } catch (_: Exception) {}
                    t.shutdownNow()
                }
            }
        }} catch (_: Exception) {}
    }
}