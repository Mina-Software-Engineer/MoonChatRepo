package com.mina.moonchat.data.server

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

internal data class StoredImageAttachment(
    val file: File,
    val mimeType: String,
    val fileSizeBytes: Long,
    val width: Int,
    val height: Int
)

internal object ImageAttachmentStore {
    const val MAX_IMAGE_BYTES = 8L * 1024L * 1024L
    private const val MAX_DIMENSION = 8192
    private const val MAX_PIXELS = 40_000_000L

    fun copyAndValidate(context: Context, source: Uri): StoredImageAttachment {
        val mimeType = context.contentResolver.getType(source)
            ?.lowercase()
            ?.let { if (it == "image/jpg") "image/jpeg" else it }
            ?: throw IOException("Could not determine the selected image type.")
        if (mimeType !in setOf("image/jpeg", "image/png", "image/webp")) {
            throw IOException("Choose a JPEG, PNG, or WebP image.")
        }

        val folder = File(context.filesDir, "pending_chat_images")
        if (!folder.exists() && !folder.mkdirs()) throw IOException("Could not prepare private image storage.")
        val extension = when (mimeType) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> ".jpg"
        }
        val target = File(folder, "${UUID.randomUUID()}$extension")
        try {
            val input = context.contentResolver.openInputStream(source)
                ?: throw IOException("Could not open the selected image.")
            input.use { sourceStream ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = sourceStream.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_IMAGE_BYTES) {
                            throw IOException("Images must be 8 MiB or smaller.")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                }
            }

            val size = target.length()
            if (size <= 0L) throw IOException("The selected image is empty.")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) throw IOException("The selected file is not a supported image.")
            if (width > MAX_DIMENSION || height > MAX_DIMENSION || width.toLong() * height > MAX_PIXELS) {
                throw IOException("Image dimensions exceed the supported limit.")
            }
            return StoredImageAttachment(target, mimeType, size, width, height)
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }
}
