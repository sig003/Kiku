package com.bradlab.kiku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 플레이어 화면 (DESIGN.md §5.2). 현재 문장 하이라이트·한국어 토글·진행바·컨트롤. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(clipId: Int, onBack: () -> Unit) {
    val context = LocalContext.current

    // 재생은 서비스가 소유(백그라운드 생존). 화면은 바인딩해 상태 구독 + 명령만.
    var service by remember { mutableStateOf<PlaybackService?>(null) }
    DisposableEffectBind(context) { service = it }
    androidx.compose.runtime.LaunchedEffect(service, clipId) { service?.open(clipId) }

    val ui by produceState(initialValue = PlayerUiState(), key1 = service) {
        val s = service
        if (s == null) value = PlayerUiState() else s.state.collect { value = it }
    }

    var showKr by remember { mutableStateOf(true) }
    BackHandler { onBack() }   // 뒤로가기 = 목록으로. 재생은 서비스라 계속됨.

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.title.ifEmpty { "재생" }, maxLines = 1) },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 진행 상태
            val total = ui.totalSentences.coerceAtLeast(1)
            LinearProgressIndicator(
                progress = { (ui.sentenceIndex + 1).toFloat() / total },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "문장 ${ui.sentenceIndex + 1}/${ui.totalSentences}" +
                    if (ui.finished) " · 끝" else if (ui.playing) " · ▶" else " · ⏸",
                style = MaterialTheme.typography.labelMedium,
            )

            // 현재 문장 (일본어 크게 — 지금 일본어 읽는 중이면 강조)
            Text(
                ui.sentenceJp,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                color = if (ui.kind == StepKind.JP) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
            )

            // 한국어 (토글)
            if (showKr) {
                Text(
                    ui.sentenceKr,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    color = if (ui.kind == StepKind.KR) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { showKr = !showKr }) {
                Text(if (showKr) "한국어 숨기기" else "한국어 보기")
            }

            // 단어 칩
            if (ui.words.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ui.words.forEach { w ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                "${w.jp} · ${w.kr}",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            // 컨트롤
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { service?.prev() }) { Text("◀ 이전") }
                OutlinedButton(onClick = { service?.replay() }) { Text("↻ 다시") }
                OutlinedButton(onClick = { service?.next() }) { Text("다음 ▶") }
            }
            Button(onClick = { service?.playPause() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (ui.playing) "⏸ 일시정지" else "▶ 재생")
            }

            // 속도
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("속도")
                listOf(0.8f, 1.0f, 1.2f).forEach { s ->
                    FilledTonalButton(onClick = { service?.setSpeed(s) }) {
                        Text(if (s == ui.speed) "[$s]" else "$s")
                    }
                }
            }
        }
    }
}

/** 서비스 바인딩을 DisposableEffect로 감싼 헬퍼. 화면이 떠날 때 언바인드. */
@Composable
private fun DisposableEffectBind(context: Context, onService: (PlaybackService?) -> Unit) {
    androidx.compose.runtime.DisposableEffect(Unit) {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
                onService((b as PlaybackService.LocalBinder).service())
            }
            override fun onServiceDisconnected(name: ComponentName?) { onService(null) }
        }
        context.bindService(Intent(context, PlaybackService::class.java), conn, Context.BIND_AUTO_CREATE)
        onDispose { context.unbindService(conn) }
    }
}
