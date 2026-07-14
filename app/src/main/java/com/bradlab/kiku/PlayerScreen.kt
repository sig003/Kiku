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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 나우플레잉 화면 (design_handoff §2). 디스크 아트 + 문장/뜻/어휘 + 스크러버 + 컨트롤. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(clipId: Int, shuffle: Boolean, fresh: Boolean, randomLevel: String? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    var service by remember { mutableStateOf<PlaybackService?>(null) }
    DisposableEffectBind(context) { service = it }
    // fresh=true면 새로 적재(재시작). 미니플레이어로 복귀(fresh=false)면 이미 그 클립이면 그대로 둠.
    LaunchedEffect(service, clipId, shuffle) {
        val s = service ?: return@LaunchedEffect
        if (fresh || s.state.value.clipId != clipId) s.open(clipId, shuffle, randomLevel)
    }
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
            Modifier.fillMaxSize().systemBarsPadding()
                .padding(horizontal = 22.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 상단 바 — 좌: 뒤로 / 중앙: 라벨·제목 / 우: 🔀 셔플(현재 클립 섞어 재생)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CircleBtn("‹", 46.dp, KikuColors.surface, KikuColors.text, big = true, onClick = onBack)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (ui.clipId == AssetClipRepository.RANDOM_CLIP_ID) "RANDOM" else "N4",
                        color = KikuColors.textFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp,
                    )
                    Text(ui.title.ifEmpty { "재생" }, color = KikuColors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(46.dp))   // 제목 중앙 정렬 균형용(셔플은 아래 컨트롤로 내림)
            }

            Spacer(Modifier.height(14.dp))
            Disc(progress = progress, indexLabel = "${ui.sentenceIndex + 1} / ${ui.totalSentences}")
            Spacer(Modifier.height(18.dp))

            // 문장·해석·어휘 — 길이 변화를 이 영역에서만 흡수(내부 스크롤). 아래 컨트롤은 고정.
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // 화자(대화 클립일 때만)
                ui.speaker?.let { sp ->
                    Text(sp, color = KikuColors.gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.height(6.dp))
                }
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
                // 셔플 — 모드별 블록 단위로 섞음(대화=A/B 짝, 퀴즈·실전청해=문항, 단문=문장).
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(KikuColors.surface)
                        .clickable { service?.setShuffle(!ui.shuffled) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_shuffle),
                        contentDescription = "랜덤",
                        tint = if (ui.shuffled) KikuColors.gold else KikuColors.textMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
                CircleBtn("⏮", 50.dp, KikuColors.surface, KikuColors.text) { service?.prev() }
                CircleBtn(if (ui.playing) "❚❚" else "▶", 68.dp, KikuColors.gold, KikuColors.bg, big = true) { service?.playPause() }
                CircleBtn("⏭", 50.dp, KikuColors.surface, KikuColors.text) { service?.next() }
                CircleBtn("↻", 44.dp, KikuColors.surface, KikuColors.text) { service?.replay() }
            }

            Spacer(Modifier.height(16.dp))
            // 속도 세그먼트 — 중앙 정렬해 가운데(1.0)가 재생 버튼과 같은 중앙선에. 라벨은 아래로.
            Row(Modifier.clip(RoundedCornerShape(999.dp)).background(KikuColors.surface).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf(0.8f, 1.0f, 1.2f).forEach { s ->
                    val on = s == ui.speed
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) KikuColors.gold else KikuColors.surface)
                            .clickable { service?.setSpeed(s) }.padding(horizontal = 16.dp, vertical = 6.dp),
                    ) { Text("$s", color = if (on) KikuColors.bg else KikuColors.textMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            Spacer(Modifier.height(5.dp))
            Text("속도", color = KikuColors.textMuted, fontSize = 11.sp)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Disc(progress: Float, indexLabel: String) {
    // 이스터에그: 동심원이 재생 진행도(문장 위치)에 따라 작게→크게. 뒤로 가면 자동 축소.
    // TTS는 초 위치가 없어 진행도가 문장 단위(계단식)라, 애니메이션으로 계단을 부드럽게 덮는다.
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = 0.35f + 0.65f * progress.coerceIn(0f, 1f),
        label = "ringScale",
    )
    // 바깥 Box(클립 안 함)에 뱃지를 둬서 원형 클립에 잘리지 않게 한다.
    Box(Modifier.size(196.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(190.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(androidx.compose.ui.graphics.Color(0xFF232A38), androidx.compose.ui.graphics.Color(0xFF0E1118)))),
            contentAlignment = Alignment.Center,
        ) {
            // 진행도에 따라 커지는 골드 동심원 3개
            Canvas(Modifier.fillMaxSize()) {
                val maxR = size.minDimension / 2
                listOf(0.45f, 0.72f, 1.0f).forEach { frac ->
                    val r = maxR * frac * scale
                    if (r > 0) drawCircle(
                        color = KikuColors.gold.copy(alpha = 0.14f + 0.12f * progress.coerceIn(0f, 1f)), radius = r,
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
