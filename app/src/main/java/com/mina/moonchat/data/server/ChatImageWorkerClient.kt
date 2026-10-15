package com.mina.moonchat.data.server

import com.google.firebase.auth.FirebaseAuth
import com.mina.moonchat.BuildConfig
import com.mina.moonchat.models.ChatImageMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class ChatImageUploadResult(
    val media: ChatImageMedia,
    val createdAt: Date
)

internal class WorkerRequestException(
    val httpStatus: Int,
    val errorCode: String,
    message: String
) : IOException(message) {
    val isPermanent: Boolean
        get() = httpStatus in setOf(400, 401, 403, 404, 409, 413, 415, 422)
}

/** Calls the private Cloudflare Worker; the Firebase ID token is never persisted or logged. */
internal class ChatImageWorkerClient {
    private val baseUrl: String
        get() {
            val configured = BuildConfig.CHAT_IMAGE_WORKER_URL.trim().trimEnd('/')
            if (configured.isBlank()) {
                throw IllegalStateException("Set CHAT_IMAGE_WORKER_URL in local.properties before sending images.")
            }
            val url = URL(configured)
            require(url.protocol.equals("https", ignoreCase = true) && url.host.isNotBlank()) {
                "The chat image Worker URL must be an HTTPS URL."
            }
            return configured
        }

    suspend fun uploadAndFinalize(
        recipientId: String,
        messageId: String,
        file: File?,
        mimeType: String,
        fileSizeBytes: Long,
        width: Int,
        height: Int
    ): ChatImageUploadResult {
        val prepare = postJson(
            "/v1/chat-images/prepare",
            JSONObject()
                .put("recipientId", recipientId)
                .put("messageId", messageId)
                .put("mimeType", mimeType)
                .put("fileSizeBytes", fileSizeBytes)
                .put("width", width)
                .put("height", height)
        )

        if (!prepare.optBoolean("uploadComplete", false)) {
            val source = file ?: throw IOException("The saved image file is missing; select the image again.")
            if (!source.isFile || source.length() != fileSizeBytes) {
                throw IOException("The saved image file is unavailable or has changed; select the image again.")
            }
            val uploadUrl = prepare.optString("uploadUrl")
            val fields = prepare.optJSONObject("fields")
                ?: throw IOException("The Worker returned an invalid upload form.")
            withContext(Dispatchers.IO) {
                uploadToCloudinary(uploadUrl, fields, source, mimeType)
            }
        }

        val finalized = postJson(
            "/v1/chat-images/finalize",
            JSONObject().put("recipientId", recipientId).put("messageId", messageId)
        )
        if (finalized.optString("messageId") != messageId || finalized.optString("type") != "IMAGE") {
            throw IOException("The Worker returned an unexpected finalized message.")
        }
        val mediaJson = finalized.optJSONObject("media")
            ?: throw IOException("The Worker did not return image metadata.")
        val media = ChatImageMedia.fromJson(mediaJson.toString())
            ?: throw IOException("The Worker returned invalid image metadata.")
        val createdAt = parseWorkerDate(finalized.optString("createdAt"))
            ?: Date()
        return ChatImageUploadResult(media, createdAt)
    }

    private suspend fun postJson(path: String, body: JSONObject): JSONObject {
        val token = firebaseIdToken()
        return withContext(Dispatchers.IO) {
            val connection = openHttpsConnection(URL(baseUrl + path))
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                readJsonResponse(connection)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun uploadToCloudinary(
        endpoint: String,
        fields: JSONObject,
        file: File,
        mimeType: String
    ) {
        val url = URL(endpoint)
        require(url.protocol.equals("https", ignoreCase = true) && url.host.equals("api.cloudinary.com", true)) {
            "The Worker returned an untrusted Cloudinary upload URL."
        }
        val boundary = "MoonChat-${UUID.randomUUID()}"
        val connection = openHttpsConnection(url)
        val fieldParts = mutableListOf<ByteArray>()
        val keys = fields.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = fields.optString(key)
            val safeName = key.replace("\"", "").replace("\r", "").replace("\n", "")
            fieldParts += (
                "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"$safeName\"\r\n\r\n" +
                    "$value\r\n"
                ).toByteArray(Charsets.UTF_8)
        }
        val filename = when (mimeType) {
            "image/png" -> "moonchat-image.png"
            "image/webp" -> "moonchat-image.webp"
            else -> "moonchat-image.jpg"
        }
        val fileHeader = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n" +
                "Content-Type: $mimeType\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        val closing = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val contentLength = fieldParts.sumOf { it.size.toLong() } + fileHeader.size + file.length() + closing.size

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setRequestProperty("Accept", "application/json")
            connection.setFixedLengthStreamingMode(contentLength)
            connection.outputStream.use { output ->
                fieldParts.forEach { part -> output.write(part) }
                output.write(fileHeader)
                FileInputStream(file).use { input -> input.copyTo(output) }
                output.write(closing)
            }
            readJsonResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun openHttpsConnection(url: URL): HttpURLConnection {
        require(url.protocol.equals("https", ignoreCase = true)) { "Only HTTPS requests are allowed." }
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 45_000
            useCaches = false
            instanceFollowRedirects = false
        }
    }

    private fun readJsonResponse(connection: HttpURLConnection): JSONObject {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(128 * 1024) }.orEmpty()
        val body = try {
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (_: Exception) {
            JSONObject()
        }
        if (status !in 200..299) {
            val error = body.optJSONObject("error")
            val code = error?.optString("code").orEmpty().ifBlank { "http_$status" }
            val message = error?.optString("message").orEmpty().ifBlank {
                if (text.isNotBlank()) text else "The image service returned HTTP $status."
            }
            throw WorkerRequestException(
                httpStatus = status,
                errorCode = code,
                message = message
            )
        }
        return body
    }

    private suspend fun firebaseIdToken(): String = suspendCancellableCoroutine { continuation ->
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            continuation.resumeWithException(IllegalStateException("Sign in before sending an image."))
            return@suspendCancellableCoroutine
        }
        user.getIdToken(false)
            .addOnSuccessListener { result ->
                val token = result.token
                if (token.isNullOrBlank()) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("Could not refresh your sign-in token."))
                } else if (continuation.isActive) {
                    continuation.resume(token)
                }
            }
            .addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
    }

    private fun parseWorkerDate(value: String): Date? {
        if (value.isBlank()) return null
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }.parse(value)
        } catch (_: Exception) {
            null
        }
    }
}
