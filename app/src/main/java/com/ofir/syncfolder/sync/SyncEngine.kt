// Copyright © 2026 Ofir Meguri
// Licensed under the Apache License, Version 2.0
// See LICENSE file for details.

package com.ofir.syncfolder.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.ofir.syncfolder.data.AppDb
import com.ofir.syncfolder.data.FileRecord
import com.ofir.syncfolder.drive.DriveClient
import com.ofir.syncfolder.drive.TokenExpiredException

data class SyncResult(val uploaded: Int, val skipped: Int)

object SyncEngine {

    fun parseExtensionFilter(raw: String?): Set<String> =
        raw?.split(",")
            ?.map { it.trim().removePrefix(".").lowercase() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    suspend fun sync(
        context: Context,
        treeUri: Uri,
        accessToken: String,
        folderName: String,
        db: AppDb,
        extensionFilter: Set<String> = emptySet(),
        syncFromMs: Long? = null,
        onProgress: (currentFile: String, done: Int) -> Unit
    ): SyncResult {
        val drive = DriveClient(accessToken)
        val rootDriveId = drive.ensureFolder(folderName, "root")
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Cannot open tree URI")

        var uploaded = 0
        var skipped = 0
        val dao = db.fileRecordDao()

        suspend fun recurse(dir: DocumentFile, driveFolderId: String, relPrefix: String) {
            for (doc in dir.listFiles()) {
                val name = doc.name ?: continue
                val relPath = if (relPrefix.isEmpty()) name else "$relPrefix/$name"

                if (doc.isDirectory) {
                    val subId = drive.ensureFolder(name, driveFolderId)
                    recurse(doc, subId, relPath)
                } else {
                    if (extensionFilter.isNotEmpty() && name.substringAfterLast('.', "").lowercase() !in extensionFilter) {
                        continue
                    }
                    if (syncFromMs != null && doc.lastModified() < syncFromMs) {
                        skipped++
                        onProgress(name, uploaded + skipped)
                        continue
                    }
                    val existing = dao.findByPath(relPath)
                    if (existing != null &&
                        existing.size == doc.length() &&
                        existing.lastModified == doc.lastModified()
                    ) {
                        skipped++
                        onProgress(name, uploaded + skipped)
                        continue
                    }

                    onProgress(name, uploaded + skipped)
                    val stream = context.contentResolver.openInputStream(doc.uri)
                        ?: continue
                    val mime = doc.type ?: "application/octet-stream"
                    val driveId = stream.use {
                        drive.uploadFile(name, driveFolderId, it, mime, doc.length())
                    }
                    dao.upsert(FileRecord(relPath, doc.length(), doc.lastModified(), driveId))
                    uploaded++
                }
            }
        }

        recurse(rootDoc, rootDriveId, "")
        return SyncResult(uploaded, skipped)
    }
}
