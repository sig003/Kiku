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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 라이브러리 화면 (design_handoff §1). 브랜드바 + 전체 랜덤 히어로 + 컬렉션 행. */
@Composable
fun ClipListScreen(onOpen: (Int, Boolean) -> Unit) {
    val context = LocalContext.current
    val clips by produceState(initialValue = emptyList<Clip>()) {
        value = AssetClipRepository(context).clips()
    }

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
        HeroRandomCard(onClick = { onOpen(AssetClipRepository.RANDOM_CLIP_ID, false) })
        Spacer(Modifier.height(22.dp))
        Text(
            "컬렉션 · N4",
            color = KikuColors.textFaint,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(6.dp))
        clips.forEachIndexed { i, clip ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(KikuColors.border))
            CollectionRow(clip, onOpen)
        }
        Spacer(Modifier.height(24.dp))
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
            Text("Kiku", color = KikuColors.text, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text("JLPT LISTENING", color = KikuColors.textFaint, fontSize = 11.sp, letterSpacing = 1.6.sp)
        }
    }
}

@Composable
private fun HeroRandomCard(onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.radialGradient(listOf(Color3A2E12, Color171821)))
            .border(1.dp, KikuColors.goldBorder, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(20.dp),
    ) {
        // 우하단 셔플 장식 글리프
        Text(
            "🔀",
            fontSize = 80.sp,
            color = KikuColors.goldFaint,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
        Column {
            Text("◇ 오늘의 듣기", color = KikuColors.gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp)
            Spacer(Modifier.height(8.dp))
            Text("전체 랜덤", color = KikuColors.text, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "모든 문장에서 무작위로. 열 때마다 새로 섞여요.",
                color = KikuColors.textMuted,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth(0.8f),
            )
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.clip(RoundedCornerShape(999.dp)).background(KikuColors.gold).padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text("▶ 100문장 시작", color = KikuColors.bg, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
private fun CollectionRow(clip: Clip, onOpen: (Int, Boolean) -> Unit) {
    val art = clipArt(clip)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(clip.id, false) }   // 행 탭 = 순서대로
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 아트 타일
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Brush.verticalGradient(art.colors)),
            contentAlignment = Alignment.Center,
        ) {
            Text(art.kanji, color = KikuColors.text, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Text(
                clip.level,
                color = KikuColors.textMuted,
                fontSize = 8.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            )
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(clip.displayCategory(), color = KikuColors.textFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(clip.title, color = KikuColors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${clip.sentences.size}문장 · 드릴", color = KikuColors.textMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.size(8.dp))
        // 재생 원형 아이콘
        Box(
            Modifier.size(34.dp).clip(CircleShape).border(1.5.dp, KikuColors.border, CircleShape)
                .clickable { onOpen(clip.id, false) },
            contentAlignment = Alignment.Center,
        ) {
            Text("▶", color = KikuColors.textMuted, fontSize = 13.sp)
        }
    }
}

private val Color3A2E12 = androidx.compose.ui.graphics.Color(0xFF3A2E12)
private val Color171821 = androidx.compose.ui.graphics.Color(0xFF171821)
