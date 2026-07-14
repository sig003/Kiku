package com.bradlab.kiku

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 라이브러리 화면 (design_handoff §1). 브랜드바 + 전체 랜덤 히어로 + 컬렉션 행. */
@Composable
fun ClipListScreen(onOpen: (Int, Boolean, String?) -> Unit, bottomPadding: androidx.compose.ui.unit.Dp = 0.dp) {
    val context = LocalContext.current
    val clips by produceState(initialValue = emptyList<Clip>()) {
        value = AssetClipRepository(context).clips()
    }
    var filter by remember { mutableStateOf("전체") }   // 전체 / N4 / N3
    val shown = clips.filter { filter == "전체" || it.level == filter }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KikuColors.bg)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 16.dp),
    ) {
        BrandBar()
        Spacer(Modifier.height(20.dp))
        HeroRandomCard(level = filter, onClick = { onOpen(AssetClipRepository.RANDOM_CLIP_ID, false, filter) })
        Spacer(Modifier.height(20.dp))
        LevelFilter(selected = filter, onSelect = { filter = it })
        Spacer(Modifier.height(6.dp))
        if (shown.isEmpty()) {
            Text(
                "${filter} 콘텐츠는 준비 중이에요.",
                color = KikuColors.textMuted, fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 32.dp),
            )
        }
        shown.forEachIndexed { i, clip ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(KikuColors.border))
            CollectionRow(clip, onOpen)
        }
        Spacer(Modifier.height(24.dp + bottomPadding))   // 미니 플레이어 활성 시 마지막 항목 안 가리게
    }
}

@Composable
private fun LevelFilter(selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(KikuColors.surface).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf("전체", "N4", "N3").forEach { lv ->
            val on = lv == selected
            Box(
                Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) KikuColors.gold else KikuColors.surface)
                    .clickable { onSelect(lv) }.padding(horizontal = 16.dp, vertical = 6.dp),
            ) { Text(lv, color = if (on) KikuColors.bg else KikuColors.textMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun BrandBar() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(KikuColors.gold),
            contentAlignment = Alignment.Center,
        ) {
            Text("聞", color = KikuColors.bg, fontSize = 24.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.size(12.dp))
        Column {
            Text("KIKU", color = KikuColors.text, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text("JLPT LISTENING", color = KikuColors.textFaint, fontSize = 11.sp, letterSpacing = 1.6.sp)
        }
    }
}

@Composable
private fun HeroRandomCard(level: String, onClick: () -> Unit) {
    val all = level == "전체"
    val title = if (all) "전체 랜덤" else "$level 랜덤"
    val subtitle = if (all) "모든 한 문장 랜덤 듣기" else "$level 한 문장 랜덤 듣기"
    val cta = if (all) "▶ 100문장 시작" else "▶ $level 랜덤 시작"
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.radialGradient(listOf(Color3A2E12, Color171821)))
            .border(1.dp, KikuColors.goldBorder, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(20.dp),
    ) {
        // 우하단 셔플 장식
        Icon(
            painterResource(R.drawable.ic_shuffle),
            contentDescription = null,
            tint = KikuColors.goldFaint,
            modifier = Modifier.align(Alignment.BottomEnd).size(84.dp),
        )
        Column {
            Text("◇ 오늘의 듣기", color = KikuColors.gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp)
            Spacer(Modifier.height(8.dp))
            Text(title, color = KikuColors.text, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                color = KikuColors.textMuted,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth(0.8f),
            )
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.clip(RoundedCornerShape(999.dp)).background(KikuColors.gold).padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(cta, color = KikuColors.bg, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
private fun CollectionRow(clip: Clip, onOpen: (Int, Boolean, String?) -> Unit) {
    val art = clipArt(clip)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(clip.id, false, null) }   // 행 탭 = 순서대로
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 아트 타일
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Brush.verticalGradient(art.colors)),
            contentAlignment = Alignment.Center,
        ) {
            Text(art.kanji, color = KikuColors.text, fontSize = 22.sp, fontWeight = FontWeight.Black)
            // 대화·실전청해(남/여 대화) 클립이면 "2명" 표시(우하단 배지)
            if (clip.mode == ClipMode.DIALOGUE || clip.mode == ClipMode.LISTENING) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(22.dp)
                        .clip(RoundedCornerShape(topStart = 10.dp, bottomEnd = 14.dp))
                        .background(KikuColors.bg.copy(alpha = 0.75f)),
                    contentAlignment = Alignment.Center,
                ) { PeopleGlyph(KikuColors.gold, Modifier.size(15.dp)) }
            }
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text("${clip.level} · ${clip.displayCategory()}", color = KikuColors.textFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(clip.title, color = KikuColors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val unit = if (clip.mode == ClipMode.QUIZ || clip.mode == ClipMode.LISTENING) "문제" else "문장"
            Text("${clip.sentences.size}$unit · ${clip.mode.description()}", color = KikuColors.textMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.size(8.dp))
        // 재생 원형 아이콘
        Box(
            Modifier.size(34.dp).clip(CircleShape).border(1.5.dp, KikuColors.border, CircleShape)
                .clickable { onOpen(clip.id, false, null) },
            contentAlignment = Alignment.Center,
        ) {
            Text("▶", color = KikuColors.textMuted, fontSize = 13.sp)
        }
    }
}

/** 클립 성격 설명 — 카드 서브라인에 표시. */
private fun ClipMode.description(): String = when (this) {
    ClipMode.DRILL -> "한 문장 듣기"
    ClipMode.DIALOGUE -> "대화 듣기"
    ClipMode.LISTENING -> "실전 청해"
    ClipMode.QUIZ -> "즉시응답"
}

private val Color3A2E12 = androidx.compose.ui.graphics.Color(0xFF3A2E12)
private val Color171821 = androidx.compose.ui.graphics.Color(0xFF171821)
