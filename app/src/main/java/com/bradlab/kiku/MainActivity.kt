package com.bradlab.kiku

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.bradlab.kiku.ui.theme.KikuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 스플래시 설치는 super.onCreate 전에 (OS가 런치 스플래시를 본 테마로 전환)
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KikuTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TtsCheckScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}