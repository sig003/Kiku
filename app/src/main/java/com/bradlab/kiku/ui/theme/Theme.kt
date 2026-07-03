package com.bradlab.kiku.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import com.bradlab.kiku.KikuColors

// KIKU는 다크+골드 고정 테마(오디오 플레이어 감성). 시스템 라이트/다이내믹 컬러 미사용.
private val KikuScheme = darkColorScheme(
    primary = KikuColors.gold,
    onPrimary = KikuColors.bg,
    secondary = KikuColors.gold,
    background = KikuColors.bg,
    onBackground = KikuColors.text,
    surface = KikuColors.surface,
    onSurface = KikuColors.text,
    surfaceVariant = KikuColors.surface2,
    onSurfaceVariant = KikuColors.textMuted,
    secondaryContainer = KikuColors.surface,
    onSecondaryContainer = KikuColors.text,
    outline = KikuColors.border,
)

@Composable
fun KikuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KikuScheme,
        typography = Typography,
        content = content,
    )
}
