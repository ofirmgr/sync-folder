package com.ofir.syncfolder.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class TokenExpiredException : IOException("Access token expired")

class DriveClient(private var accessToken: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun updateToken(token: String) { accessToken = token }

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
     */
    suspend fun uploadFile(
        name: String,
        parentId: String,
        stream: InputStream,
        mimeType: String
    ): String {
        val data = withContext(Dispatchers.IO) { stream.readBytes() }

        // Check if file already exists to decide create vs update
        val escaped = name.replace("\\", "\\\\").replace("'", "\\'")
        val q = "name='$escaped' and '$parentId' in parents and trashed=false"
        val existing = get("$BASE/files?q=${URLEncoder.encode(q, "UTF-8")}&fields=files(id)")
            .getJSONArray("files")

        val meta = JSONObject().apply {
            put("name", name)
            if (existing.length() == 0) put("parents", JSONArray().apply { put(parentId) })
        }

        return if (existing.length() > 0) {
            val fileId = existing.getJSONObject(0).getString("id")
            patchMultipart("$UPLOAD_BASE/files/$fileId?uploadType=multipart&fields=id", meta.toString(), mimeType, data)
                .getString("id")
        } else {
            postMultipart("$UPLOAD_BASE/files?uploadType=multipart&fields=id", meta.toString(), mimeType, data)
                .getString("id")
        }
    }

    private suspend fun get(url: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").build()
        val resp = http.newCall(req).execute()
        val body = resp.body?.string() ?: "{}"
        if (resp.code == 401) throw TokenExpiredException()
        if (!resp.isSuccessful) throw IOException("GET $url → ${resp.code}: $body")
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

    private suspend fun postMultipart(url: String, meta: String, mimeType: String, data: ByteArray): JSONObject =
        multipartRequest("POST", url, meta, mimeType, data)

    private suspend fun patchMultipart(url: String, meta: String, mimeType: String, data: ByteArray): JSONObject =
        multipartRequest("PATCH", url, meta, mimeType, data)

    private suspend fun multipartRequest(
        method: String,
        url: String,
        meta: String,
        mimeType: String,
        data: ByteArray
    ): JSONObject = withContext(Dispatchers.IO) {
        val boundary = "b${System.nanoTime()}"
        val bodyBytes = buildMultipart(boundary, meta, mimeType, data)
        val body = bodyBytes.toRequestBody("multipart/related; boundary=$boundary".toMediaType())
        val req = Request.Builder()
            .url(url)
            .method(method, body)
            .header("Authorization", "Bearer $accessToken")
            .build()
        val resp = http.newCall(req).execute()
        val respBody = resp.body?.string() ?: "{}"
        if (resp.code == 401) throw TokenExpiredException()
        if (!resp.isSuccessful) throw IOException("$method $url → ${resp.code}: $respBody")
        JSONObject(respBody)
    }

    private fun buildMultipart(boundary: String, meta: String, mimeType: String, data: ByteArray): ByteArray {
        val header = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: $mimeType\r\n\r\n"
        val footer = "\r\n--$boundary--\r\n"
        return header.toByteArray(Charsets.UTF_8) + data + footer.toByteArray(Charsets.UTF_8)
    }

    companion object {
        private const val BASE = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
        private const val MIME_FOLDER = "application/vnd.google-apps.folder"
    }
}
