package com.bradlab.kiku

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap

/** KIKU 디자인 토큰 (design_handoff: 다크 배경 + 골드 액센트, 아이팟/Apple Music 감성). */
object KikuColors {
    val bg = Color(0xFF16181F)          // 앱 전체 배경 (순검정 대신 살짝 든 다크)
    val bgGradTop = Color(0xFF1B2130)   // 나우플레잉 상단 그라데이션
    val surface = Color(0xFF20252F)     // 카드/버튼 바탕
    val surface2 = Color(0xFF272D38)    // 눌림/트랙
    val border = Color(0x14FFFFFF)      // 구분선/보더 rgba(255,255,255,.08)
    val gold = Color(0xFFF3C14E)        // 액센트 골드
    val goldDim = Color(0xFFC99A3A)     // 골드 보조
    val goldBorder = Color(0x47F3C14E)  // 히어로 보더 rgba(243,193,78,.28)
    val goldFaint = Color(0x1AF3C14E)   // 링/장식 rgba(243,193,78,.10)
    val text = Color(0xFFFFFFFF)        // 텍스트 기본
    val textMuted = Color(0xFF9BA3B0)   // 뮤트
    val textFaint = Color(0xFF6B7280)   // 캡션/메타
    val chipText = Color(0xFFCBD2DC)    // 어휘 칩 텍스트
}

/** 컬렉션 아트: 한자 글리프 + 배경 그라데이션(위→아래). */
data class ArtSpec(val kanji: String, val colors: List<Color>)

/** 클립 카테고리로 아트(한자+그라데이션) 매핑. 문자열 키워드로 판별, 기본은 회사생활. */
fun clipArt(clip: Clip): ArtSpec {
    val c = clip.category
    return when {
        "회사" in c -> ArtSpec("会社", listOf(Color(0xFF2B3A67), Color(0xFF16213E)))  // 회사생활
        "여행" in c -> ArtSpec("旅", listOf(Color(0xFF1F5C57), Color(0xFF0E2E2B)))
        "회화" in c -> ArtSpec("会話", listOf(Color(0xFF6B3F1E), Color(0xFF331C0C)))  // 생활회화
        else -> ArtSpec("会社", listOf(Color(0xFF2B3A67), Color(0xFF16213E)))
    }
}

/** 카테고리 라벨에서 레벨 접두("N4 ") 제거해 표시용 이름만. */
fun Clip.displayCategory(): String = category.removePrefix("${level} ").ifEmpty { category }

/** 깔끔한 벡터 셔플 아이콘 — 이모지(🔀) 대신 직접 그린다(모노크롬, 색 지정 가능). */
@Composable
fun ShuffleGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val sw = (size.minDimension * 0.09f).coerceAtLeast(2f)
        fun p(x: Float, y: Float) = Offset(w * x, h * y)
        // 교차하는 두 대각선
        drawLine(color, p(0.12f, 0.30f), p(0.70f, 0.70f), sw, cap = StrokeCap.Round)
        drawLine(color, p(0.12f, 0.70f), p(0.70f, 0.30f), sw, cap = StrokeCap.Round)
        // 오른쪽 화살촉 2개
        drawLine(color, p(0.70f, 0.70f), p(0.56f, 0.68f), sw, cap = StrokeCap.Round)
        drawLine(color, p(0.70f, 0.70f), p(0.68f, 0.55f), sw, cap = StrokeCap.Round)
        drawLine(color, p(0.70f, 0.30f), p(0.56f, 0.32f), sw, cap = StrokeCap.Round)
        drawLine(color, p(0.70f, 0.30f), p(0.68f, 0.45f), sw, cap = StrokeCap.Round)
    }
}
