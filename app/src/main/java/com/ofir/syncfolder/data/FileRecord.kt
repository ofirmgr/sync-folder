package com.ofir.syncfolder.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "file_records")
data class FileRecord(
    @PrimaryKey val relativePath: String,
    val size: Long,
    val lastModified: Long,
    val driveFileId: String
)
