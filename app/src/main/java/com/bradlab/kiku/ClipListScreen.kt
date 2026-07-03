package com.bradlab.kiku

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 클립 목록 화면 (DESIGN.md §5.1). 카테고리·제목·문장 수·타입 배지를 카드로. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipListScreen(onOpen: (Int, Boolean) -> Unit) {
    val context = LocalContext.current
    val clips by produceState(initialValue = emptyList<Clip>()) {
        value = AssetClipRepository(context).clips()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("kiku — 클립") }) },
        modifier = Modifier.fillMaxSize(),
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 전체 랜덤 — 모든 클립 문장을 섞어 무작위로
            item(key = AssetClipRepository.RANDOM_CLIP_ID) {
                RandomCard(onClick = { onOpen(AssetClipRepository.RANDOM_CLIP_ID, false) })
            }
            items(clips, key = { it.id }) { clip ->
                ClipCard(clip, onOpen = onOpen)
            }
        }
    }
}

@Composable
private fun ClipCard(clip: Clip, onOpen: (Int, Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(clip.id, false) },  // 카드 탭 = 순서대로
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(clip.category, style = MaterialTheme.typography.labelMedium)
            Text(clip.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "${clip.sentences.size}문장 · ${clip.mode.badge()}",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { onOpen(clip.id, true) }) { Text("🔀 랜덤 재생") }  // 이 클립만 섞어서
        }
    }
}

@Composable
private fun RandomCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("🔀 전체 랜덤", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("모든 문장에서 무작위로 (열 때마다 새로 섞임)", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun ClipMode.badge(): String = when (this) {
    ClipMode.DRILL -> "드릴"
    ClipMode.DIALOGUE -> "대화"
    ClipMode.LISTENING -> "청해"
}
