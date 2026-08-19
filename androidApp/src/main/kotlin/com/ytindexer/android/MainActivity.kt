package com.ytindexer.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ytindexer.shared.AppInfo
import com.ytindexer.ui.Dimens

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MobileScaffoldingScreen()
                }
            }
        }
    }
}

@Composable
private fun MobileScaffoldingScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(Dimens.SpaceM),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = AppInfo.NAME, style = MaterialTheme.typography.headlineMedium)
        Text(text = AppInfo.greeting("Mobile"), style = MaterialTheme.typography.bodyMedium)
    }
}
