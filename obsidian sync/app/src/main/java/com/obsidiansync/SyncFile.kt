package com.obsidiansync

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_index")
data class SyncFile(
    @PrimaryKey val localPath: String,  // Đường dẫn trên điện thoại
    val driveFileId: String,            // ID Google cấp cho file
    val lastModifiedLocal: Long,        // Thời gian sửa máy lần cuối
    val lastFileSize: Long,             // Kích thước file lần cuối
    val fileHash: String                // Mã MD5/SHA để kiểm tra nội dung
)