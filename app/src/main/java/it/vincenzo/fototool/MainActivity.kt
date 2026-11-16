package it.vincenzo.fototool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import it.vincenzo.fototool.ui.App
import it.vincenzo.fototool.ui.FotoToolTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FotoToolTheme { App() } }
    }
}
