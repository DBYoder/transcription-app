package io.github.dbyoder.transcribe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.dbyoder.transcribe.ui.MainScreen
import io.github.dbyoder.transcribe.ui.TranscribeTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TranscribeTheme {
                MainScreen(viewModel)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Until the floating bubble (phase 2) adds a foreground service, Android silences
        // the mic once the app is off screen, so finish any recording now.
        if (!isChangingConfigurations) viewModel.onAppHidden()
    }
}
