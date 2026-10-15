package com.mina.moonchat.models

import com.google.firebase.database.PropertyName
import org.json.JSONObject

/** Metadata only; private image bytes are fetched through the authenticated Worker route. */
data class ChatImageMedia(
    @get:PropertyName("publicId") @set:PropertyName("publicId") var publicId: String = "",
    @get:PropertyName("assetId") @set:PropertyName("assetId") var assetId: String = "",
    @get:PropertyName("version") @set:PropertyName("version") var version: Long = 0,
    @get:PropertyName("resourceType") @set:PropertyName("resourceType") var resourceType: String = "",
    @get:PropertyName("deliveryType") @set:PropertyName("deliveryType") var deliveryType: String = "",
    @get:PropertyName("format") @set:PropertyName("format") var format: String = "",
    @get:PropertyName("mimeType") @set:PropertyName("mimeType") var mimeType: String = "",
    @get:PropertyName("width") @set:PropertyName("width") var width: Int = 0,
    @get:PropertyName("height") @set:PropertyName("height") var height: Int = 0,
    @get:PropertyName("bytes") @set:PropertyName("bytes") var bytes: Long = 0
) {
    fun toJson(): String = JSONObject()
        .put("publicId", publicId)
        .put("assetId", assetId)
        .put("version", version)
        .put("resourceType", resourceType)
        .put("deliveryType", deliveryType)
        .put("format", format)
        .put("mimeType", mimeType)
        .put("width", width)
        .put("height", height)
        .put("bytes", bytes)
        .toString()

    companion object {
        fun fromJson(value: String): ChatImageMedia? = try {
            val json = JSONObject(value)
            ChatImageMedia(
                publicId = json.optString("publicId"),
                assetId = json.optString("assetId"),
                version = json.optLong("version"),
                resourceType = json.optString("resourceType"),
                deliveryType = json.optString("deliveryType"),
                format = json.optString("format"),
                mimeType = json.optString("mimeType"),
                width = json.optInt("width"),
                height = json.optInt("height"),
                bytes = json.optLong("bytes")
            ).takeIf { it.publicId.isNotBlank() && it.deliveryType == "authenticated" }
        } catch (_: Exception) {
            null
        }
    }
}
