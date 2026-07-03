package com.bradlab.kiku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 나우플레잉 화면 (design_handoff §2). 디스크 아트 + 문장/뜻/어휘 + 스크러버 + 컨트롤. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(clipId: Int, shuffle: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    var service by remember { mutableStateOf<PlaybackService?>(null) }
    DisposableEffectBind(context) { service = it }
    LaunchedEffect(service, clipId, shuffle) { service?.open(clipId, shuffle) }
    val ui by produceState(initialValue = PlayerUiState(), key1 = service) {
        val s = service
        if (s == null) value = PlayerUiState() else s.state.collect { value = it }
    }
    var showKr by remember { mutableStateOf(true) }
    BackHandler { onBack() }

    val total = ui.totalSentences.coerceAtLeast(1)
    val progress = (ui.sentenceIndex + 1).toFloat() / total

    Box(
        Modifier.fillMaxSize().background(Brush.radialGradient(listOf(KikuColors.bgGradTop, KikuColors.bg))),
    ) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 상단 바 — 좌: 뒤로 / 중앙: 라벨·제목 / 우: 🔀 셔플(현재 클립 섞어 재생)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CircleBtn("‹", 38.dp, KikuColors.surface, KikuColors.text, onClick = onBack)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (ui.clipId == AssetClipRepository.RANDOM_CLIP_ID) "RANDOM" else "N4",
                        color = KikuColors.textFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp,
                    )
                    Text(ui.title.ifEmpty { "재생" }, color = KikuColors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(38.dp))   // 제목 중앙 정렬 균형용(셔플은 아래 컨트롤로 내림)
            }

            Spacer(Modifier.height(14.dp))
            Disc(indexLabel = "${ui.sentenceIndex + 1} / ${ui.totalSentences}")
            Spacer(Modifier.height(18.dp))

            // 일본어 문장 (읽는 중 골드)
            Text(
                ui.sentenceJp, color = if (ui.kind == StepKind.JP) KikuColors.gold else KikuColors.text,
                fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 33.sp,
            )
            Spacer(Modifier.height(8.dp))
            if (showKr) {
                Text(
                    ui.sentenceKr, color = if (ui.kind == StepKind.KR) KikuColors.gold else KikuColors.textMuted,
                    fontSize = 15.sp, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                if (showKr) "한국어 숨기기" else "한국어 보기",
                color = KikuColors.gold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { showKr = !showKr }.padding(4.dp),
            )

            // 어휘 칩
            if (ui.words.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ui.words.forEach { w ->
                        Box(
                            Modifier.clip(RoundedCornerShape(999.dp)).background(KikuColors.surface)
                                .border(1.dp, KikuColors.border, RoundedCornerShape(999.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) { Text("${w.jp} · ${w.kr}", color = KikuColors.chipText, fontSize = 12.5.sp) }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            // 스크러버 (TTS는 초 위치가 없어 문장 진행도로 대체)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(KikuColors.surface2)) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(KikuColors.gold))
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${ui.sentenceIndex + 1}", color = KikuColors.textFaint, fontSize = 11.sp)
                Text("${ui.totalSentences}", color = KikuColors.textFaint, fontSize = 11.sp)
            }

            Spacer(Modifier.height(16.dp))
            // 컨트롤 — 셔플 · 이전 · 재생 · 다음 · 다시듣기 (뮤직플레이어 배열)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(KikuColors.surface)
                        .clickable { service?.shuffleCurrent() },
                    contentAlignment = Alignment.Center,
                ) { ShuffleGlyph(KikuColors.gold, Modifier.size(20.dp)) }
                CircleBtn("⏮", 50.dp, KikuColors.surface, KikuColors.text) { service?.prev() }
                CircleBtn(if (ui.playing) "❚❚" else "▶", 68.dp, KikuColors.gold, KikuColors.bg, big = true) { service?.playPause() }
                CircleBtn("⏭", 50.dp, KikuColors.surface, KikuColors.text) { service?.next() }
                CircleBtn("↻", 44.dp, KikuColors.surface, KikuColors.text) { service?.replay() }
            }

            Spacer(Modifier.height(16.dp))
            // 속도 세그먼트
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("속도", color = KikuColors.textMuted, fontSize = 13.sp)
                Row(Modifier.clip(RoundedCornerShape(999.dp)).background(KikuColors.surface).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(0.8f, 1.0f, 1.2f).forEach { s ->
                        val on = s == ui.speed
                        Box(
                            Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) KikuColors.gold else KikuColors.surface)
                                .clickable { service?.setSpeed(s) }.padding(horizontal = 14.dp, vertical = 6.dp),
                        ) { Text("$s", color = if (on) KikuColors.bg else KikuColors.textMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Disc(indexLabel: String) {
    // 바깥 Box(클립 안 함)에 뱃지를 둬서 원형 클립에 잘리지 않게 한다.
    Box(Modifier.size(196.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(190.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(androidx.compose.ui.graphics.Color(0xFF232A38), androidx.compose.ui.graphics.Color(0xFF0E1118)))),
            contentAlignment = Alignment.Center,
        ) {
            // 동심원 링 3개 (inset 0/22/44)
            Canvas(Modifier.fillMaxSize()) {
                listOf(0.dp, 22.dp, 44.dp).forEach { inset ->
                    val r = (size.minDimension / 2) - inset.toPx()
                    if (r > 0) drawCircle(
                        color = KikuColors.gold.copy(alpha = 0.14f), radius = r,
                        center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            }
            Text("聞", color = KikuColors.text, fontSize = 84.sp, fontWeight = FontWeight.Black)
        }
        // 우상단 n/총 뱃지 — 클립 밖이라 안 잘림
        Box(
            Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(999.dp))
                .background(KikuColors.surface).padding(horizontal = 8.dp, vertical = 3.dp),
        ) { Text(indexLabel, color = KikuColors.textMuted, fontSize = 11.sp) }
    }
}

@Composable
private fun CircleBtn(glyph: String, size: androidx.compose.ui.unit.Dp, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, big: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = fg, fontSize = if (big) 26.sp else 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** 서비스 바인딩을 DisposableEffect로 감싼 헬퍼. 화면이 떠날 때 언바인드. */
@Composable
private fun DisposableEffectBind(context: Context, onService: (PlaybackService?) -> Unit) {
    DisposableEffect(Unit) {
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
