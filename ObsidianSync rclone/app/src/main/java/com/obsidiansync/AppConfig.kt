package com.obsidiansync

import android.content.Context
import androidx.core.content.edit

object AppConfig {
    private const val PREFS = "ObsidianPrefs"

    fun saveLocalPath(context: Context, path: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("local_path", path) }
    }

    fun getLocalPath(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("local_path", "/storage/emulated/0/Documents/Obsidian")!!
    }

    // Lưu đường dẫn thực tế cho Rclone (VD: gdrive:root_id)
    fun saveRemotePath(context: Context, path: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("remote_path", path) }
    }

    fun getRemotePath(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("remote_path", "gdrive:")!!
    }

    // --- MỚI: Lưu tên hiển thị folder để hiện lên UI khi mở lại App ---
    fun saveRemoteDisplayName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("remote_name", name) }
    }

    fun getRemoteDisplayName(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("remote_name", "Mặc định (Root)")!!
    }
    // ----------------------------------------------------------------

    fun saveUserEmail(context: Context, email: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("user_email", email) }
    }

    fun getUserEmail(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("user_email", null)
    }
}