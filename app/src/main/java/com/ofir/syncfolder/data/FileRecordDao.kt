// Copyright © 2026 Ofir Meguri
// Licensed under the Apache License, Version 2.0
// See LICENSE file for details.

package com.ofir.syncfolder.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface FileRecordDao {
    @Query("SELECT * FROM file_records WHERE relativePath = :path")
    suspend fun findByPath(path: String): FileRecord?

    @Query("SELECT COUNT(*) FROM file_records")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(record: FileRecord)

    @Query("DELETE FROM file_records WHERE relativePath LIKE :prefix || '%'")
    suspend fun deleteByPrefix(prefix: String)

    @Query("DELETE FROM file_records")
    suspend fun clearAll()
}
