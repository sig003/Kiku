package com.bradlab.kiku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                JapaneseVoiceCheck()   // 일본어 TTS 없으면 설치 안내(없으면 한자가 한국어로 읽힘)
                KikuRoot()
            }
        }
    }
}

/**
 * 일본어 TTS 음성이 없으면 설치 안내. 없으면 일본어(한자)가 한국어 발음으로 잘못 읽힌다(§7.2).
 */
@Composable
private fun JapaneseVoiceCheck() {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(false) }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(Unit) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val ja = runCatching { tts?.isLanguageAvailable(java.util.Locale.JAPANESE) }
                    .getOrNull() ?: TextToSpeech.LANG_NOT_SUPPORTED
                missing = ja < TextToSpeech.LANG_AVAILABLE
            }
        }
        onDispose { runCatching { tts?.shutdown() } }
    }
    if (missing && !dismissed) {
        AlertDialog(
            onDismissRequest = { dismissed = true },
            title = { Text("일본어 음성 설치 필요") },
            text = { Text("이 기기에 일본어 TTS 음성이 없어, 일본어가 한국어 발음으로 잘못 읽힐 수 있어요. 일본어 음성을 설치하면 정상적으로 들립니다. (설정 → 언어 → 텍스트 음성 변환에서 일본어 데이터 설치)") },
            confirmButton = {
                TextButton(onClick = {
                    dismissed = true
                    runCatching { context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                }) { Text("설치하기") }
            },
            dismissButton = { TextButton(onClick = { dismissed = true }) { Text("나중에") } },
        )
    }
}

/**
 * 앱 루트 — 목록 ↔ 플레이어 2화면 전환 + 하단 미니 플레이어.
 *
 * 서비스 바인딩을 여기로 올려 [state]를 공유한다. 그래서 플레이어에서 뒤로 나가도(=목록)
 * 재생 중인 세션이 하단 미니 바로 남고, 탭하면 다시 플레이어로 복귀한다.
 */
@Composable
private fun KikuRoot() {
    val context = LocalContext.current
    var service by remember { mutableStateOf<PlaybackService?>(null) }
    DisposableEffect(Unit) {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
                service = (b as PlaybackService.LocalBinder).service()
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        context.bindService(Intent(context, PlaybackService::class.java), conn, Context.BIND_AUTO_CREATE)
        onDispose { context.unbindService(conn) }
    }
    val ui by produceState(initialValue = PlayerUiState(), key1 = service) {
        val s = service
        if (s == null) value = PlayerUiState() else s.state.collect { value = it }
    }

    // 목적지가 둘뿐이라 별도 네비 라이브러리 없이 상태로 처리.
    var openClipId by rememberSaveable { mutableStateOf<Int?>(null) }
    var openShuffle by rememberSaveable { mutableStateOf(false) }
    var openFresh by rememberSaveable { mutableStateOf(true) } // 새로 열기(재적재) vs 복귀(그대로)
    var openRandomLevel by rememberSaveable { mutableStateOf<String?>(null) } // 전체 랜덤의 레벨(전체/N4/N3)

    val sessionActive = ui.clipId >= 0 && ui.totalSentences > 0
    val id = openClipId
    if (id == null) {
        Box(Modifier.fillMaxSize()) {
            ClipListScreen(
                onOpen = { clipId, shuffle, level ->
                    openClipId = clipId; openShuffle = shuffle; openRandomLevel = level; openFresh = true
                },
                bottomPadding = if (sessionActive) 84.dp else 0.dp,   // 미니 바에 마지막 항목 안 가리게
            )
            if (sessionActive) {
                MiniPlayer(
                    ui = ui,
                    onTap = { openClipId = ui.clipId; openShuffle = ui.shuffled; openFresh = false },
                    onPlayPause = { service?.playPause() },
                    onClose = { service?.dismiss() },
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                )
            }
        }
    } else {
        PlayerScreen(
            clipId = id, shuffle = openShuffle, fresh = openFresh, randomLevel = openRandomLevel,
            onBack = { openClipId = null },
        )
    }
}

/** 하단 미니 플레이어 — 목록에서 현재 재생 세션을 보여주고, 탭하면 플레이어로 확장. */
@Composable
private fun MiniPlayer(
    ui: PlayerUiState,
    onTap: () -> Unit,
    onPlayPause: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(KikuColors.surface)
            .border(1.dp, KikuColors.border, RoundedCornerShape(16.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 미니 디스크
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            androidx.compose.ui.graphics.Color(0xFF232A38),
                            androidx.compose.ui.graphics.Color(0xFF0E1118),
                        )
                    )
                ),
            contentAlignment = Alignment.Center,
        ) { Text("聞", color = KikuColors.text, fontSize = 20.sp, fontWeight = FontWeight.Black) }

        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                ui.title.ifEmpty { if (ui.clipId == AssetClipRepository.RANDOM_CLIP_ID) "전체 랜덤" else "재생 중" },
                color = KikuColors.textFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                ui.sentenceJp.ifEmpty { "..." },
                color = KikuColors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(KikuColors.gold)
                .clickable(onClick = onPlayPause),
            contentAlignment = Alignment.Center,
        ) { Text(if (ui.playing) "❚❚" else "▶", color = KikuColors.bg, fontSize = 16.sp, fontWeight = FontWeight.Bold) }

        Spacer(Modifier.width(4.dp))
        // 닫기 — 재생 중단 + 미니바 숨김
        Box(
            Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { Text("✕", color = KikuColors.textMuted, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
    }
}
