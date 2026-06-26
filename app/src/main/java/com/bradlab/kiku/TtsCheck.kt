package com.bradlab.kiku

import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 반나절 검증 1단계 (DESIGN.md §9):
 *  - 버튼 → 일본어/한국어 한 문장 TTS 재생
 *  - 일·한 음성 설치 여부 체크 (§7.2). 없으면 설치 인텐트 안내.
 * 본 구현(TtsSequencer)으로 가기 전, 음성 존재/품질만 확인하는 일회용 화면.
 */
@Composable
fun TtsCheckScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("TTS 초기화 중…") }
    var jpReady by remember { mutableStateOf(false) }
    var krReady by remember { mutableStateOf(false) }

    // TTS 인스턴스를 컴포지션 수명에 묶고, 떠날 때 정리.
    val tts = remember {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { initStatus ->
            if (initStatus == TextToSpeech.SUCCESS) {
                val e = engine!!
                jpReady = e.isLanguageAvailable(Locale.JAPANESE) >= TextToSpeech.LANG_AVAILABLE
                krReady = e.isLanguageAvailable(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE
                status = buildString {
                    append("초기화 완료 — ")
                    append("일본어 ${if (jpReady) "있음" else "없음"} / ")
                    append("한국어 ${if (krReady) "있음" else "없음"}")
                }
            } else {
                status = "TTS 초기화 실패 (status=$initStatus)"
            }
        }
        engine
    }

    DisposableEffect(Unit) {
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(status)

        Button(onClick = {
            scope.launch {
                tts.setSpeechRate(1.0f)
                tts.speakAndAwait("昨日は会社を休みました。", Locale.JAPANESE)
            }
        }) { Text("일본어 재생") }

        Button(onClick = {
            scope.launch {
                tts.speakAndAwait("어제는 회사를 쉬었습니다.", Locale.KOREAN)
            }
        }) { Text("한국어 재생") }

        Button(onClick = {
            scope.launch {
                // 일↔한 연속 — 언어 전환 레이턴시 체감용
                tts.speakAndAwait("昨日は会社を休みました。", Locale.JAPANESE)
                tts.speakAndAwait("어제는 회사를 쉬었습니다.", Locale.KOREAN)
            }
        }) { Text("일 → 한 연속 재생") }

        if (!jpReady || !krReady) {
            Button(onClick = {
                // §7.2 음성 데이터 설치 화면으로 유도
                context.startActivity(
                    Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                )
            }) { Text("TTS 음성 데이터 설치") }
        }
    }
}

/**
 * DESIGN.md §2.4 — speak()를 suspend로 감싸 onDone(또는 onError)에서 resume.
 * 본 구현에서 PlaybackStep을 받게 확장된다. 지금은 검증용으로 text+locale만.
 */
suspend fun TextToSpeech.speakAndAwait(text: String, locale: Locale) =
    suspendCancellableCoroutine { cont ->
        val id = text.hashCode().toString()
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
            @Deprecated("deprecated in API")
            override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
            override fun onError(utteranceId: String?, errorCode: Int) { if (cont.isActive) cont.resume(Unit) }
        })
        language = locale
        speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        cont.invokeOnCancellation { stop() }
    }
