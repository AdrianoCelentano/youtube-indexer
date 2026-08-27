package com.ytindexer.tv.search

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens a video in the YouTube app, falling back to the browser.
 *
 * Same approach as the phone app's `openVideo` (see its doc comment for why): the
 * `vnd.youtube:` scheme is honoured by YouTube's Android TV app too, so this needs no TV
 * variant of the intent itself, only its own copy since the two apps don't share code
 * across module boundaries.
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
