package io.github.gooseandroid.ui.chat

import io.github.gooseandroid.data.models.*

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment

/**
 * Renders an image from a URL or base64 data URI inline in the message.
 */
@Composable
fun InlineImage(
    url: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context)
            .data(url)
            .crossfade(true)
            .build(),
        contentDescription = "Inline image",
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .clip(RoundedCornerShape(8.dp)),
        contentScale = ContentScale.Fit,
        error = {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.BrokenImage, contentDescription = "Failed to load image")
            }
        },
        loading = {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
    )
}

/**
 * Extracts image URLs from markdown content.
 * Matches: ![alt](url) and bare URLs ending in image extensions,
 * and base64 data URIs.
 */
fun extractImageUrls(content: String): List<String> {
    val urls = mutableListOf<String>()

    // Markdown image syntax: ![alt](url)
    val markdownImageRegex = Regex("!\\[.*?]\\((.*?)\\)")
    markdownImageRegex.findAll(content).forEach { match ->
        urls.add(match.groupValues[1])
    }

    // Base64 data URIs in the text (limit base64 portion to 10MB worth of chars)
    val base64Regex = Regex("(data:image/[^;]{1,20};base64,[A-Za-z0-9+/=]{20,14000000})")
    base64Regex.findAll(content).forEach { match ->
        val dataUri = match.groupValues[1]
        if (dataUri !in urls) {
            urls.add(dataUri)
        }
    }

    // Bare image URLs (http/https ending in common image extensions)
    val bareUrlRegex = Regex("(https?://\\S+\\.(?:png|jpg|jpeg|gif|webp|svg))(\\s|$|\\))")
    bareUrlRegex.findAll(content).forEach { match ->
        val bareUrl = match.groupValues[1]
        if (bareUrl !in urls) {
            urls.add(bareUrl)
        }
    }

    return urls
}

/**
 * Removes image markdown syntax from content so text renders cleanly.
 */
fun removeImageMarkdown(content: String): String {
    var result = content
    // Remove ![alt](url) patterns
    result = Regex("!\\[.*?]\\(.*?\\)").replace(result, "")
    // Remove standalone base64 data URIs
    result = Regex("data:image/[^;]+;base64,[A-Za-z0-9+/=]+").replace(result, "")
    return result.trim()
}
