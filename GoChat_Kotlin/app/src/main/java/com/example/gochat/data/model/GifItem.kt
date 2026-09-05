package com.example.gochat.data.model

import kotlinx.serialization.Serializable

@Serializable
data class GifItem(
    val id: String,
    val title: String,
    val previewUrl: String,
    val fullUrl: String,
    val source: String // "giphy" or "tenor"
)

@Serializable
data class StickerPack(
    val id: String,
    val name: String,
    val iconEmoji: String,
    val stickers: List<StickerItem>
)

@Serializable
data class StickerItem(
    val id: String,
    val name: String,
    val url: String
)
