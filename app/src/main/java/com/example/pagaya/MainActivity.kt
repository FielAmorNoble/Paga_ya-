package com.example.pagaya

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.pagaya.ui.PagaYaNavHost
import com.example.pagaya.ui.theme.PagaYaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PagaYaTheme {
                PagaYaNavHost()
            }
        }
    }
}
