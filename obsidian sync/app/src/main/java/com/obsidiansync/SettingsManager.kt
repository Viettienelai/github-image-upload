package com.obsidiansync

import android.content.Context
import android.net.Uri

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("obsid_sync_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LOCAL_URI = "local_uri"
        private const val KEY_DRIVE_FOLDER_ID = "drive_folder_id"
        private const val KEY_DRIVE_FOLDER_NAME = "drive_folder_name"
        private const val KEY_ACCOUNT_EMAIL = "account_email"
    }

    var localFolderUri: Uri?
        get() = prefs.getString(KEY_LOCAL_URI, null)?.let { Uri.parse(it) }
        set(value) = prefs.edit().putString(KEY_LOCAL_URI, value?.toString()).apply()

    var driveFolderId: String?
        get() = prefs.getString(KEY_DRIVE_FOLDER_ID, null)
        set(value) = prefs.edit().putString(KEY_DRIVE_FOLDER_ID, value).apply()

    var driveFolderName: String?
        get() = prefs.getString(KEY_DRIVE_FOLDER_NAME, "Chưa chọn Drive")
        set(value) = prefs.edit().putString(KEY_DRIVE_FOLDER_NAME, value).apply()

    var accountEmail: String?
        get() = prefs.getString(KEY_ACCOUNT_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_ACCOUNT_EMAIL, value).apply()
}