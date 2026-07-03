package com.bradlab.kiku

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.bradlab.kiku.ui.theme.KikuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 스플래시 설치는 super.onCreate 전에 (OS가 런치 스플래시를 본 테마로 전환)
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KikuTheme {
                // 미디어 알림 표시용 권한 (안드로이드 13+). 거부돼도 재생은 됨(알림만 안 보임).
                val notifPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) {}
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                // 2화면 전환(목록 ↔ 플레이어). 목적지가 둘뿐이라 별도 네비 라이브러리 없이 상태로 처리.
                var openClipId by rememberSaveable { mutableStateOf<Int?>(null) }
                var openShuffle by rememberSaveable { mutableStateOf(false) }
                val id = openClipId
                if (id == null) {
                    ClipListScreen(onOpen = { clipId, shuffle -> openClipId = clipId; openShuffle = shuffle })
                } else {
                    PlayerScreen(clipId = id, shuffle = openShuffle, onBack = { openClipId = null })
                }
            }
        }
    }
}
