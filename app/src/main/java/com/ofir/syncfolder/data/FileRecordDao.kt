package com.ofir.syncfolder.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface FileRecordDao {
    @Query("SELECT * FROM file_records WHERE relativePath = :path")
    suspend fun findByPath(path: String): FileRecord?

    @Upsert
    suspend fun upsert(record: FileRecord)

    @Query("DELETE FROM file_records WHERE relativePath LIKE :prefix || '%'")
    suspend fun deleteByPrefix(prefix: String)
}
