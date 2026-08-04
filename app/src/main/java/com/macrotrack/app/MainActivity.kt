package com.macrotrack.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.macrotrack.app.ui.nav.MacroTrackNavHost
import com.macrotrack.app.ui.theme.MacroTrackTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContainer = (application as MacroTrackApplication).appContainer
        setContent {
            MacroTrackTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MacroTrackNavHost(appContainer)
                }
            }
        }
    }
}
