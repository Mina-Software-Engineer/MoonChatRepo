package com.mina.moonchat.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.mina.moonchat.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object CloudinaryImageUploader {

    suspend fun uploadProfileImage(
        context: Context,
        imageUri: Uri,
        userId: String
    ): String = withContext(Dispatchers.IO) {
        val cloudName = BuildConfig.CLOUDINARY_CLOUD_NAME.trim()
        val uploadPreset = BuildConfig.CLOUDINARY_UPLOAD_PRESET.trim()

        require(cloudName.isNotEmpty()) {
            "Missing Cloudinary cloud name. Add CLOUDINARY_CLOUD_NAME to local.properties."
        }
        require(uploadPreset.isNotEmpty()) {
            "Missing Cloudinary upload preset. Add CLOUDINARY_UPLOAD_PRESET to local.properties."
        }

        val imageBytes = compressImage(context, imageUri)
        val boundary = "MoonChatBoundary${System.currentTimeMillis()}"
        val connection = (URL("https://api.cloudinary.com/v1_1/$cloudName/image/upload")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doInput = true
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        try {
            DataOutputStream(connection.outputStream).use { output ->
                writeTextPart(output, boundary, "upload_preset", uploadPreset)
                writeTextPart(
                    output,
                    boundary,
                    "public_id",
                    "${userId}_profile_${System.currentTimeMillis()}"
                )
                writeTextPart(output, boundary, "folder", "moonchat/profile_images")
                writeFilePart(
                    output = output,
                    boundary = boundary,
                    fieldName = "file",
                    fileName = "${userId}_profile.jpg",
                    mimeType = "image/jpeg",
                    bytes = imageBytes
                )
                output.writeBytes("--$boundary--\r\n")
                output.flush()
            }

            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (responseCode !in 200..299) {
                throw IOException("Cloudinary upload failed ($responseCode): $responseBody")
            }

            val secureUrl = JSONObject(responseBody).optString("secure_url")
            if (secureUrl.isBlank()) {
                throw IOException("Cloudinary response did not contain secure_url")
            }

            secureUrl
        } finally {
            connection.disconnect()
        }
    }

    private fun compressImage(context: Context, imageUri: Uri): ByteArray {
        val bitmap = context.contentResolver.openInputStream(imageUri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream)
        } ?: throw IOException("Unable to read selected image")

        return ByteArrayOutputStream().use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 25, outputStream)
            outputStream.toByteArray()
        }
    }

    private fun writeTextPart(
        output: DataOutputStream,
        boundary: String,
        fieldName: String,
        value: String
    ) {
        output.writeBytes("--$boundary\r\n")
        output.writeBytes("Content-Disposition: form-data; name=\"$fieldName\"\r\n\r\n")
        output.writeBytes(value)
        output.writeBytes("\r\n")
    }

    private fun writeFilePart(
        output: DataOutputStream,
        boundary: String,
        fieldName: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ) {
        output.writeBytes("--$boundary\r\n")
        output.writeBytes(
            "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"\r\n"
        )
        output.writeBytes("Content-Type: $mimeType\r\n\r\n")
        output.write(bytes)
        output.writeBytes("\r\n")
    }
}
