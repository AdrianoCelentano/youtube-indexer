package com.ytindexer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * A video thumbnail at YouTube's standard 16:9 aspect ratio.
 *
 * Shared by both app surfaces so search results look the same shape on the phone and on
 * TV, even though the surrounding row/card layout differs. [url] is nullable because
 * `Video.thumbnailUrl` is: older or since-deleted videos may never have had one captured.
 *
 * The background shows through while the image loads and if it fails (offline, or no
 * thumbnail). Deliberately not a broken-image icon: on a card grid a plain colour reads
 * better than the same glyph repeated a dozen times across a row of failed loads.
 */
@Composable
fun VideoThumbnail(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(ThumbnailCornerRadius),
) {
    Box(
        modifier =
            modifier
                .aspectRatio(THUMBNAIL_ASPECT_RATIO)
                .clip(shape)
                .background(PlaceholderColor),
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private val PlaceholderColor = Color(0xFF2A2A2A)
private val ThumbnailCornerRadius = 8.dp
private const val THUMBNAIL_ASPECT_RATIO = 16f / 9f
