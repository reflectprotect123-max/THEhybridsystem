package com.macroplus.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.macroplus.app.ui.nav.MacroPlusNavHost
import com.macroplus.app.ui.theme.MacroPlusTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContainer = (application as MacroPlusApplication).appContainer
        setContent {
            MacroPlusTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MacroPlusNavHost(appContainer)
                }
            }
        }
    }
}
