// Copyright © 2026 Ofir Meguri
// Licensed under the Apache License, Version 2.0
// See LICENSE file for details.

package com.ofir.syncfolder.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.ofir.syncfolder.data.AppDb
import com.ofir.syncfolder.data.FileRecord
import com.ofir.syncfolder.drive.DriveClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SyncResult(val uploaded: Int, val skipped: Int)

/**
 * Thrown when a folder listing fails or comes back empty in a way that indicates a
 * transient SAF/provider failure (e.g. CursorWindow overflow on very large folders)
 * rather than a genuinely empty folder. Treated as retryable so the sync is not
 * silently marked successful with nothing uploaded.
 */
class FolderListingException(message: String) : Exception(message)

object SyncEngine {

    /**
     * Serialises sync runs. The chained schedule, the content-URI trigger and manual
     * sync can all fire independently, and two concurrent runs would both enumerate
     * and both upload. A queued run re-enumerates and finds nothing changed, which is
     * correct and cheap.
     */
    private val mutex = Mutex()

    fun parseExtensionFilter(raw: String?): Set<String> =
        raw?.split(",")
            ?.map { it.trim().removePrefix(".").lowercase() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    private data class Entry(
        val documentId: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
        val mime: String,
        val fallbackDocument: DocumentFile? = null
    )

    suspend fun sync(
        context: Context,
        treeUri: Uri,
        accessToken: String,
        folderName: String,
        db: AppDb,
        extensionFilter: Set<String> = emptySet(),
        syncFromMs: Long? = null,
        onProgress: (currentFile: String, done: Int, uploading: Boolean) -> Unit
    ): SyncResult = mutex.withLock {
        val drive = DriveClient(accessToken)
        val rootDriveId = drive.ensureFolder(folderName, "root")
        val resolver = context.contentResolver
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        android.util.Log.d("SyncEngine", "Starting sync. treeUri=$treeUri, rootDocId=$rootDocId, folderName=$folderName")

        var uploaded = 0
        var skipped = 0
        val dao = db.fileRecordDao()
        // Snapshot of how many records we already have. Used to distinguish a
        // genuinely empty folder from a listing that silently failed.
        val existingCount = dao.count()

        // Reads all children of a tree document in a single cursor pass. Unlike
        // DocumentFile.listFiles(), a query failure is surfaced (thrown) instead of
        // swallowed into an empty list, and there is no per-file IPC round trip.
        fun listChildren(parentDocId: String, fallbackParent: DocumentFile?): List<Entry> {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            val cursor = try {
                resolver.query(childrenUri, projection, null, null, null)
            } catch (e: Exception) {
                android.util.Log.e("SyncEngine", "Query failed for $parentDocId", e)
                throw FolderListingException("Listing query failed for $parentDocId: ${e.message}")
            } ?: run {
                android.util.Log.e("SyncEngine", "Query returned null for $parentDocId")
                throw FolderListingException("Listing returned null cursor for $parentDocId")
            }

            val entries = ArrayList<Entry>()
            cursor.use { c ->
                val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val modIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                try {
                    while (c.moveToNext()) {
                        val id = c.getString(idIdx) ?: continue
                        val name = c.getString(nameIdx) ?: continue
                        val mime = c.getString(mimeIdx) ?: ""
                        entries.add(
                            Entry(
                                documentId = id,
                                name = name,
                                isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                                size = if (c.isNull(sizeIdx)) 0L else c.getLong(sizeIdx),
                                lastModified = if (c.isNull(modIdx)) 0L else c.getLong(modIdx),
                                mime = mime
                            )
                        )
                    }
                } catch (e: Exception) {
                    // CursorWindow overflow / provider death mid-iteration. Surface it
                    // so the run fails and retries instead of syncing a partial list.
                    android.util.Log.e("SyncEngine", "Iteration failed for $parentDocId", e)
                    throw FolderListingException("Listing iteration failed for $parentDocId: ${e.message}")
                }
            }
            if (entries.isEmpty() && fallbackParent != null) {
                // Some ExternalStorageProvider builds can return a successful but empty
                // cursor for a non-empty directory. Retry through the previous
                // DocumentFile implementation before treating the listing as failed.
                val fallbackEntries = fallbackParent.listFiles().mapNotNull { document ->
                    val name = document.name ?: return@mapNotNull null
                    val documentId = runCatching {
                        DocumentsContract.getDocumentId(document.uri)
                    }.getOrNull() ?: return@mapNotNull null
                    Entry(
                        documentId = documentId,
                        name = name,
                        isDirectory = document.isDirectory,
                        size = document.length(),
                        lastModified = document.lastModified(),
                        mime = document.type.orEmpty(),
                        fallbackDocument = document
                    )
                }
                if (fallbackEntries.isNotEmpty()) {
                    android.util.Log.w(
                        "SyncEngine",
                        "Bulk listing was empty for $parentDocId; DocumentFile found ${fallbackEntries.size} entries"
                    )
                    return fallbackEntries
                }
            }
            android.util.Log.d("SyncEngine", "Found ${entries.size} entries for $parentDocId")
            return entries
        }

        suspend fun recurse(
            parentDocId: String,
            driveFolderId: String,
            relPrefix: String,
            fallbackParent: DocumentFile?
        ) {
            for (entry in listChildren(parentDocId, fallbackParent)) {
                val name = entry.name
                val relPath = if (relPrefix.isEmpty()) name else "$relPrefix/$name"

                if (entry.isDirectory) {
                    val subId = drive.ensureFolder(name, driveFolderId)
                    recurse(entry.documentId, subId, relPath, entry.fallbackDocument)
                } else {
                    if (extensionFilter.isNotEmpty() && name.substringAfterLast('.', "").lowercase() !in extensionFilter) {
                        continue
                    }
                    if (syncFromMs != null && entry.lastModified < syncFromMs) {
                        skipped++
                        onProgress(name, uploaded + skipped, false)
                        continue
                    }
                    val existing = dao.findByPath(relPath)
                    if (existing != null &&
                        existing.size == entry.size &&
                        existing.lastModified == entry.lastModified
                    ) {
                        skipped++
                        onProgress(name, uploaded + skipped, false)
                        continue
                    }

                    onProgress(name, uploaded + skipped, true)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.documentId)
                    val stream = resolver.openInputStream(docUri) ?: continue
                    val mime = entry.mime.ifEmpty { "application/octet-stream" }
                    val driveId = stream.use {
                        drive.uploadFile(name, driveFolderId, it, mime, entry.size)
                    }
                    dao.upsert(FileRecord(relPath, entry.size, entry.lastModified, driveId))
                    uploaded++
                }
            }
        }

        val rootDocument = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw FolderListingException("Cannot open selected folder")
        recurse(rootDocId, rootDriveId, "", rootDocument)

        // Fail-loud guard: if we have synced files before but this run enumerated
        // nothing at all, the listing almost certainly failed silently. Do not mark
        // the run successful (which would advance last_sync and hide the problem).
        if (uploaded == 0 && skipped == 0 && existingCount > 0) {
            throw FolderListingException("Folder listing came back empty despite $existingCount existing records")
        }

        SyncResult(uploaded, skipped)
    }
}
