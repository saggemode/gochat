package com.example.gochat.core.utils

import com.example.gochat.data.api.GoChatApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import java.net.URI
import java.util.regex.Pattern

data class LinkPreview(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val domain: String? = null
)

object LinkPreviewManager {

    var apiService: GoChatApiService? = null

    private val urlPattern = Pattern.compile(
        "(?:^|[\\W])((ht|f)tp(s?):\\/\\/|www\\.)" +
                "(([\\w\\-]+\\.){1,27}[a-z]{2,10})" +
                "(:[0-9]{1,5})?" +
                "(\\/[\\w\\.\\?@\\-%!\\+=\\=\\&]*)?",
        Pattern.CASE_INSENSITIVE
    )

    fun extractUrl(text: String): String? {
        val matcher = urlPattern.matcher(text)
        return if (matcher.find()) {
            val url = (matcher.group(1) ?: "") + (matcher.group(4) ?: "") + (matcher.group(6) ?: "") + (matcher.group(7) ?: "")
            if (!url.startsWith("http")) "https://$url" else url
        } else null
    }

    suspend fun getPreview(url: String): LinkPreview? = withContext(Dispatchers.IO) {
        // 1. Try Go backend unfurl API first (provides caching and server-side SSRF-safe resolution)
        val api = apiService
        if (api != null) {
            try {
                val resp = api.unfurlUrl(url)
                if (resp.isSuccessful && resp.body() != null) {
                    val body = resp.body()!!
                    val title = body["title"]?.jsonPrimitive?.contentOrNull
                    val desc = body["description"]?.jsonPrimitive?.contentOrNull
                    val image = body["image"]?.jsonPrimitive?.contentOrNull
                    val siteName = body["site_name"]?.jsonPrimitive?.contentOrNull
                    val fallbackDomain = try { URI(url).host?.removePrefix("www.") } catch (_: Exception) { null }

                    if (!title.isNullOrBlank() || !desc.isNullOrBlank() || !image.isNullOrBlank()) {
                        return@withContext LinkPreview(
                            url = url,
                            title = title?.takeIf { it.isNotBlank() },
                            description = desc?.takeIf { it.isNotBlank() },
                            imageUrl = image?.takeIf { it.isNotBlank() },
                            domain = siteName ?: fallbackDomain
                        )
                    }
                }
            } catch (_: Exception) {
                // Fall back to client-side parsing if remote API is unreachable
            }
        }

        // 2. Fallback to client-side Jsoup extraction
        try {
            val doc = Jsoup.connect(url)
                .timeout(5000)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .get()

            val ogTitle = doc.select("meta[property=og:title]").attr("content")
            val title = if (ogTitle.isNotBlank()) ogTitle else doc.title()
            
            val ogDesc = doc.select("meta[property=og:description]").attr("content")
            val metaDesc = doc.select("meta[name=description]").attr("content")
            val description = if (ogDesc.isNotBlank()) ogDesc else metaDesc
            
            val image = doc.select("meta[property=og:image]").attr("content")
            val domain = try { URI(url).host?.removePrefix("www.") } catch (_: Exception) { null }

            LinkPreview(
                url = url,
                title = title.takeIf { it.isNotBlank() },
                description = description.takeIf { it.isNotBlank() },
                imageUrl = image.takeIf { it.isNotBlank() },
                domain = domain
            )
        } catch (_: Exception) {
            null
        }
    }
}
