package com.ytindexer.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ytindexer.shared.AppInfo
import com.ytindexer.ui.Dimens

/**
 * Placeholder screen proving `:shared` code renders on the TV surface.
 *
 * Internal rather than private so the Roborazzi screenshot test can render it
 * directly without going through the Activity.
 */
@Composable
internal fun TvScaffoldingScreen() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                // Overscan padding keeps content off the bezel on real TV panels.
                .padding(Dimens.TvOverscanPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = AppInfo.NAME, style = MaterialTheme.typography.displaySmall)
        Text(text = AppInfo.greeting("TV"), style = MaterialTheme.typography.bodyLarge)
    }
}
