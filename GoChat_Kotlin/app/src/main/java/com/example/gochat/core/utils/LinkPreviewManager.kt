package com.example.gochat.core.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
