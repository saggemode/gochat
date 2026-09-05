package com.example.gochat.data.repository

import android.content.Context
import com.example.gochat.data.api.NetworkModule
import com.example.gochat.data.model.GifItem
import com.example.gochat.data.model.StickerItem
import com.example.gochat.data.model.StickerPack
import kotlinx.serialization.json.*
import okhttp3.Request

class MediaRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val giphyKey = "dc6zaTOxFJmzC" // Public Beta Key
    private val tenorKey = "LIVDSRZULELA"

    suspend fun searchGifs(query: String, limit: Int = 20): List<GifItem> {
        val giphyResults = fetchGiphy(query, limit / 2)
        val tenorResults = fetchTenor(query, limit / 2)
        val results = (giphyResults + tenorResults).shuffled()
        return results.ifEmpty { 
            fallbackGifs.filter { it.title.contains(query, ignoreCase = true) } 
        }
    }

    suspend fun getTrending(limit: Int = 20): List<GifItem> {
        val giphyResults = fetchGiphy("", limit / 2)
        val tenorResults = fetchTenor("", limit / 2)
        val results = (giphyResults + tenorResults).shuffled()
        return results.ifEmpty { fallbackGifs }
    }

    private fun fetchGiphy(query: String, limit: Int): List<GifItem> {
        return try {
            val url = if (query.isBlank()) {
                "https://api.giphy.com/v1/gifs/trending?api_key=$giphyKey&limit=$limit"
            } else {
                "https://api.giphy.com/v1/gifs/search?api_key=$giphyKey&q=$query&limit=$limit"
            }

            val response = NetworkModule.getOkHttpClient(context).newCall(
                Request.Builder().url(url).build()
            ).execute()

            val body = response.body?.string() ?: return emptyList()
            val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return emptyList()

            data.map {
                val images = it.jsonObject["images"]?.jsonObject
                GifItem(
                    id = it.jsonObject["id"]?.jsonPrimitive?.content ?: "",
                    title = it.jsonObject["title"]?.jsonPrimitive?.content ?: "GIF",
                    previewUrl = images?.get("fixed_height_small")?.jsonObject?.get("url")?.jsonPrimitive?.content ?: "",
                    fullUrl = images?.get("original")?.jsonObject?.get("url")?.jsonPrimitive?.content ?: "",
                    source = "giphy"
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun fetchTenor(query: String, limit: Int): List<GifItem> {
        return try {
            val url = if (query.isBlank()) {
                "https://tenor.googleapis.com/v2/featured?key=$tenorKey&client_key=gochat_app&limit=$limit&media_filter=gif,tinygif"
            } else {
                "https://tenor.googleapis.com/v2/search?q=$query&key=$tenorKey&client_key=gochat_app&limit=$limit&media_filter=gif,tinygif"
            }

            val response = NetworkModule.getOkHttpClient(context).newCall(
                Request.Builder().url(url).build()
            ).execute()

            val body = response.body?.string() ?: return emptyList()
            val results = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray ?: return emptyList()

            results.map {
                val media = it.jsonObject["media_formats"]?.jsonObject
                GifItem(
                    id = it.jsonObject["id"]?.jsonPrimitive?.content ?: "",
                    title = it.jsonObject["content_description"]?.jsonPrimitive?.content ?: "GIF",
                    previewUrl = media?.get("tinygif")?.jsonObject?.get("url")?.jsonPrimitive?.content ?: "",
                    fullUrl = media?.get("gif")?.jsonObject?.get("url")?.jsonPrimitive?.content ?: "",
                    source = "tenor"
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getStickerPacks(): List<StickerPack> {
        return listOf(
            StickerPack("pepe", "Pepe & Memes", "🐸", listOf(
                StickerItem("p1", "Pepe Clapping", "https://media.giphy.com/media/7rj2ZgttvgomY/giphy.gif"),
                StickerItem("p2", "Pepe Sad", "https://media.giphy.com/media/OPU6wzx8JrHna/giphy.gif"),
                StickerItem("p3", "Pepe Dance", "https://media.giphy.com/media/bkcbX8SqTCXHG/giphy.gif")
            )),
            StickerPack("shiba", "Doge & Pets", "🐕", listOf(
                StickerItem("s1", "Doge Bonk", "https://media.giphy.com/media/HxMhuDg7O4pKOhhcRC/giphy.gif"),
                StickerItem("s2", "Doge Dance", "https://media.giphy.com/media/oF5oUYTOhvZOE/giphy.gif")
            )),
            StickerPack("anime", "Anime & Chibi", "✨", listOf(
                StickerItem("a1", "Anime Wow", "https://media.giphy.com/media/111ebonMs90YLu/giphy.gif"),
                StickerItem("a2", "Chibi Wave", "https://media.giphy.com/media/vFKqnCdLPNOKc/giphy.gif")
            ))
        )
    }

    private val fallbackGifs = listOf(
        GifItem("fb1", "Excited Doge", "https://media.giphy.com/media/oF5oUYTOhvZOE/giphy.gif", "https://media.giphy.com/media/oF5oUYTOhvZOE/giphy.gif", "fallback"),
        GifItem("fb2", "Mind Blown", "https://media.giphy.com/media/26ufdipQqU2lhNA4g/giphy.gif", "https://media.giphy.com/media/26ufdipQqU2lhNA4g/giphy.gif", "fallback"),
        GifItem("fb3", "Cat Vibing", "https://media.giphy.com/media/JIX9t2j0ZTN9S/giphy.gif", "https://media.giphy.com/media/JIX9t2j0ZTN9S/giphy.gif", "fallback")
    )
}
