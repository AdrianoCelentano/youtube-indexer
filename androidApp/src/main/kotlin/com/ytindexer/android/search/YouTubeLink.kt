package com.ytindexer.android.search

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens a video in the YouTube app, falling back to the browser.
 *
 * Deep-linking rather than embedding a player: it keeps playback inside YouTube's own
 * app, which is the compliant path for ads, DRM and watch history, and avoids building a
 * player for v1. See Implementation Plan §7.
 */
fun openVideo(
    context: Context,
    videoId: String,
) {
    val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId"))
    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))

    try {
        context.startActivity(appIntent)
    } catch (
        @Suppress("SwallowedException") e: ActivityNotFoundException,
    ) {
        // Expected, not exceptional: the YouTube app simply is not installed. The web
        // URL always resolves to a browser, so there is nothing to report.
        context.startActivity(webIntent)
    }
}
