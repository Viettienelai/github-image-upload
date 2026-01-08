package com.obsidiansync

import androidx.room.*

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_index")
    suspend fun getAllFiles(): List<SyncFile>

    @Query("SELECT * FROM sync_index WHERE localPath = :path LIMIT 1")
    suspend fun getFileByPath(path: String): SyncFile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: SyncFile)

    @Query("DELETE FROM sync_index WHERE localPath = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM sync_index")
    suspend fun clearAll()
}