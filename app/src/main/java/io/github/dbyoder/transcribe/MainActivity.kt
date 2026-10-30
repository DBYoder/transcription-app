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

    override fun onResume() {
        super.onResume()
        // The user may have just switched the bubble on or granted a permission in Settings.
        viewModel.refreshSetup()
    }

    override fun onStop() {
        super.onStop()
        // Android silences the mic for apps that go off screen, so finish any recording
        // started with this screen's button. (The bubble records separately.)
        if (!isChangingConfigurations) viewModel.onAppHidden()
    }
}
