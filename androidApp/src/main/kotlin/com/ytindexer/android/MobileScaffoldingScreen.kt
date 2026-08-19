package com.ytindexer.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ytindexer.shared.AppInfo
import com.ytindexer.ui.Dimens

/**
 * Placeholder screen proving `:shared` code renders on the phone surface.
 *
 * Internal rather than private so the Roborazzi screenshot test can render it
 * directly without going through the Activity.
 */
@Composable
internal fun MobileScaffoldingScreen() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(Dimens.SpaceM),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = AppInfo.NAME, style = MaterialTheme.typography.headlineMedium)
        Text(text = AppInfo.greeting("Mobile"), style = MaterialTheme.typography.bodyMedium)
    }
}
