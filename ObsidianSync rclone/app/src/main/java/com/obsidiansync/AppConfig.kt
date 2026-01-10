package com.obsidiansync

import android.content.Context
import androidx.core.content.edit

object AppConfig {
    private val Context.prefs get() = getSharedPreferences("ObsidianPrefs", Context.MODE_PRIVATE)

    fun saveLocalPath(ctx: Context, path: String) = ctx.prefs.edit { putString("local_path", path) }
    fun getLocalPath(ctx: Context) = ctx.prefs.getString("local_path", "/storage/emulated/0/Documents/Obsidian")!!

    fun saveRemotePath(ctx: Context, path: String) = ctx.prefs.edit { putString("remote_path", path) }
    fun getRemotePath(ctx: Context) = ctx.prefs.getString("remote_path", "gdrive:")!!

    fun saveRemoteDisplayName(ctx: Context, name: String) = ctx.prefs.edit { putString("remote_name", name) }
    fun getRemoteDisplayName(ctx: Context) = ctx.prefs.getString("remote_name", "Mặc định (Root)")!!

    fun saveUserEmail(ctx: Context, email: String) = ctx.prefs.edit { putString("user_email", email) }
    fun getUserEmail(ctx: Context) = ctx.prefs.getString("user_email", null)
}