// Copyright © 2026 Ofir Meguri
// Licensed under the Apache License, Version 2.0
// See LICENSE file for details.

package com.ofir.syncfolder.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class TokenExpiredException : IOException("Access token expired")

/** A Drive file ID no longer resolves — it was deleted or moved out of reach. */
class FileNotFoundOnDriveException(fileId: String) : IOException("Drive file $fileId not found")

class DriveClient(private val accessToken: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Returns Drive folder ID, creating it if it doesn't exist.
     * parentId = "root" for top-level, or a folder ID.
     */
    suspend fun ensureFolder(name: String, parentId: String): String {
        val escaped = name.replace("\\", "\\\\").replace("'", "\\'")
        val q = "name='$escaped' and mimeType='$MIME_FOLDER' and '$parentId' in parents and trashed=false"
        val url = "$BASE/files?q=${URLEncoder.encode(q, "UTF-8")}&fields=files(id)"
        val existing = get(url).getJSONArray("files")
        if (existing.length() > 0) return existing.getJSONObject(0).getString("id")

        val meta = JSONObject().apply {
            put("name", name)
            put("mimeType", MIME_FOLDER)
            put("parents", JSONArray().apply { put(parentId) })
        }
        return post("$BASE/files?fields=id", meta.toString()).getString("id")
    }

    /**
     * Uploads a file via multipart upload and returns the Drive file ID.
     * If a file with the same name already exists under parentId, it is updated.
     *
     * Pass [knownFileId] when the caller already tracks the Drive ID to skip the
     * name lookup — one round-trip per upload instead of two. A stale ID (the file
     * was removed in Drive) falls back to creating it again.
     */
    suspend fun uploadFile(
        name: String,
        parentId: String,
        stream: InputStream,
        mimeType: String,
        contentLength: Long,
        lastModifiedMs: Long,
        knownFileId: String? = null
    ): String {
        val existingId = knownFileId ?: findFileId(name, parentId)

        fun meta(isCreate: Boolean) = JSONObject().apply {
            put("name", name)
            if (isCreate) {
                put("parents", JSONArray().apply { put(parentId) })
                // createdTime is only settable at creation time; leave it alone on
                // update so re-uploads don't rewrite the original Drive create date.
                if (lastModifiedMs > 0) put("createdTime", formatRfc3339(lastModifiedMs))
            }
            // modifiedTime reflects the local file's last-modified time so Drive
            // sorting/dates match the source instead of the upload moment.
            if (lastModifiedMs > 0) put("modifiedTime", formatRfc3339(lastModifiedMs))
        }

        if (existingId != null) {
            try {
                return patchMultipart(
                    "$UPLOAD_BASE/files/$existingId?uploadType=multipart&fields=id",
                    meta(isCreate = false).toString(),
                    mimeType,
                    stream,
                    contentLength
                ).getString("id")
            } catch (e: FileNotFoundOnDriveException) {
                // Only reachable with a caller-supplied ID; the stream has not been
                // consumed because the request failed before the body was written.
                if (knownFileId == null) throw e
            }
        }

        return postMultipart(
            "$UPLOAD_BASE/files?uploadType=multipart&fields=id",
            meta(isCreate = true).toString(),
            mimeType,
            stream,
            contentLength
        ).getString("id")
    }

    /** Returns the ID of a non-trashed file named [name] under [parentId], or null. */
    suspend fun findFileId(name: String, parentId: String): String? {
        val escaped = name.replace("\\", "\\\\").replace("'", "\\'")
        val q = "name='$escaped' and '$parentId' in parents and trashed=false"
        val files = get("$BASE/files?q=${URLEncoder.encode(q, "UTF-8")}&fields=files(id)")
            .getJSONArray("files")
        return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    /** Returns (id, name) for every non-trashed file directly under [parentId]. */
    suspend fun listChildren(parentId: String): List<Pair<String, String>> {
        val q = "'$parentId' in parents and trashed=false"
        val encoded = URLEncoder.encode(q, "UTF-8")
        val out = mutableListOf<Pair<String, String>>()
        var pageToken: String? = null
        do {
            val page = StringBuilder("$BASE/files?q=$encoded&fields=nextPageToken,files(id,name)&pageSize=1000")
            pageToken?.let { page.append("&pageToken=").append(URLEncoder.encode(it, "UTF-8")) }
            val body = get(page.toString())
            val files = body.getJSONArray("files")
            for (i in 0 until files.length()) {
                val file = files.getJSONObject(i)
                out += file.getString("id") to file.getString("name")
            }
            pageToken = body.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        return out
    }

    /** Moves a file to the Drive trash. Already-missing files are treated as done. */
    suspend fun trashFile(fileId: String) {
        try {
            patchJson("$BASE/files/$fileId?fields=id", JSONObject().put("trashed", true).toString())
        } catch (e: FileNotFoundOnDriveException) {
            // Nothing to trash.
        }
    }

    private fun formatRfc3339(epochMs: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs))

    private suspend fun get(url: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").build()
        val resp = http.newCall(req).execute()
        val body = resp.body?.string() ?: "{}"
        if (resp.code == 401) throw TokenExpiredException()
        if (!resp.isSuccessful) throw IOException("GET $url → ${resp.code}: $body")
        JSONObject(body)
    }

    private suspend fun patchJson(url: String, json: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .patch(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Authorization", "Bearer $accessToken")
            .build()
        val resp = http.newCall(req).execute()
        val body = resp.body?.string() ?: "{}"
        if (resp.code == 401) throw TokenExpiredException()
        if (resp.code == 404) throw FileNotFoundOnDriveException(url.substringAfterLast('/').substringBefore('?'))
        if (!resp.isSuccessful) throw IOException("PATCH $url → ${resp.code}: $body")
        JSONObject(body)
    }

    private suspend fun post(url: String, json: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Authorization", "Bearer $accessToken")
            .build()
        val resp = http.newCall(req).execute()
        val body = resp.body?.string() ?: "{}"
        if (resp.code == 401) throw TokenExpiredException()
        if (!resp.isSuccessful) throw IOException("POST $url → ${resp.code}: $body")
        JSONObject(body)
    }

    private suspend fun postMultipart(
        url: String,
        meta: String,
        mimeType: String,
        stream: InputStream,
        contentLength: Long
    ): JSONObject = multipartRequest("POST", url, meta, mimeType, stream, contentLength)

    private suspend fun patchMultipart(
        url: String,
        meta: String,
        mimeType: String,
        stream: InputStream,
        contentLength: Long
    ): JSONObject = multipartRequest("PATCH", url, meta, mimeType, stream, contentLength)

    private suspend fun multipartRequest(
        method: String,
        url: String,
        meta: String,
        mimeType: String,
        stream: InputStream,
        contentLength: Long
    ): JSONObject = withContext(Dispatchers.IO) {
        val boundary = "b${System.nanoTime()}"
        val header = (
            "--$boundary\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
                "$meta\r\n" +
                "--$boundary\r\n" +
                "Content-Type: $mimeType\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        val footer = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val body = object : RequestBody() {
            override fun contentType() = "multipart/related; boundary=$boundary".toMediaType()

            override fun contentLength(): Long =
                if (contentLength >= 0) header.size + contentLength + footer.size else -1

            override fun writeTo(sink: BufferedSink) {
                sink.write(header)
                stream.source().use { source -> sink.writeAll(source) }
                sink.write(footer)
            }
        }
        val req = Request.Builder()
            .url(url)
            .method(method, body)
            .header("Authorization", "Bearer $accessToken")
            .build()
        val resp = http.newCall(req).execute()
        val respBody = resp.body?.string() ?: "{}"
        if (resp.code == 401) throw TokenExpiredException()
        if (resp.code == 404) throw FileNotFoundOnDriveException(url.substringAfterLast('/').substringBefore('?'))
        if (!resp.isSuccessful) throw IOException("$method $url → ${resp.code}: $respBody")
        JSONObject(respBody)
    }

    companion object {
        private const val BASE = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
        private const val MIME_FOLDER = "application/vnd.google-apps.folder"
    }
}
