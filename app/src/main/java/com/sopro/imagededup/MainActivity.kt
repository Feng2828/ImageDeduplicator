package com.sopro.imagededup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sopro.imagededup.ui.MainScreen
import com.sopro.imagededup.ui.MainViewModel
import com.sopro.imagededup.ui.theme.ImageDedupTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ImageDedupTheme {
                val viewModel: MainViewModel = viewModel()
                MainScreen(viewModel)
            }
        }
    }
}
