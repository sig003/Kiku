package com.bradlab.kiku

import android.content.Intent
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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

    // 백그라운드 재생 알림 표시를 위한 POST_NOTIFICATIONS (API 33+) 런타임 요청
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 거부돼도 서비스 자체는 동작 — 알림만 안 보임 */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var status by remember { mutableStateOf("TTS 초기화 중…") }
    var jpReady by remember { mutableStateOf(false) }
    var krReady by remember { mutableStateOf(false) }
    var voiceDump by remember { mutableStateOf("") }
    var jpVoices by remember { mutableStateOf<List<Voice>>(emptyList()) } // 목소리 비교용
    var pitch by remember { mutableStateOf(1.0f) }                        // 음 높낮이 실험

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
                // 시작 시 일본어 목소리 목록을 Logcat에 자동 덤프 (태그: KikuVoices)
                voiceDump = describeJapaneseVoices(e, logToLogcat = true)
                jpVoices = japaneseVoices(e)   // 목소리별 재생 버튼 생성용
            } else {
                status = "TTS 초기화 실패 (status=$initStatus)"
            }
        }
        engine
    }

    // TtsSequencer (TODO 2) — 클립 하나를 설계된 흐름대로 연속 재생. 검증용으로 화면이 구동.
    val sequencer = remember { TtsSequencer(tts, scope) }
    LaunchedEffect(Unit) { sequencer.load(sampleDrillClip()) }
    val ui by sequencer.state.collectAsState()

    DisposableEffect(Unit) {
        onDispose {
            sequencer.release()
            tts.stop()
            tts.shutdown()
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(status)

        // ── TtsSequencer 검증 (TODO 2) — 클립 전체 흐름 연속 재생 + 문장 이동/속도 ──
        Text("═ 시퀀서: ${ui.title} ═")
        Text(
            "문장 ${ui.sentenceIndex + 1}/${ui.totalSentences}" +
                (ui.kind?.let { " · $it" } ?: "") +
                (if (ui.finished) " · (끝)" else if (ui.playing) " · 재생중" else " · 정지")
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { sequencer.prev() }) { Text("◀ 이전") }
            Button(onClick = { sequencer.replay() }) { Text("↻ 다시") }
            Button(onClick = { sequencer.next() }) { Text("다음 ▶") }
        }
        Button(onClick = { sequencer.playPause() }) {
            Text(if (ui.playing) "⏸ 일시정지" else "▶ 재생")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.8f, 1.0f, 1.2f).forEach { s ->
                Button(onClick = { sequencer.setSpeed(s) }) {
                    Text(if (s == ui.speed) "[$s]" else "$s")
                }
            }
        }

        Text("─ 단발 재생/음성 체크 ─")
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

        // §2.6 백그라운드 재생 검증 — 시작 후 화면 잠그고 소리 이어지는지 확인
        Text("─ 백그라운드 재생 검증 ─")
        Button(onClick = { PlaybackService.start(context) }) {
            Text("백그라운드 재생 시작")
        }
        Button(onClick = { PlaybackService.stop(context) }) {
            Text("백그라운드 재생 정지")
        }

        // 일본어 목소리 비교 (TODO 1) — 같은 문장을 목소리별로 재생해 귀로 비교
        Text("─ 일본어 목소리 비교 ─")

        // 피치(음 높낮이) 선택 — 선택 후 아래 목소리 버튼으로 재생하면 반영됨
        Text("피치: ${pitch}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.8f, 1.0f, 1.2f).forEach { p ->
                Button(onClick = { pitch = p }) { Text(if (p == pitch) "[$p]" else "$p") }
            }
        }

        // 기기에 깔린 일본어 목소리마다 재생 버튼 — 같은 샘플 문장을 그 목소리+피치로
        if (jpVoices.isEmpty()) {
            Text("일본어 목소리를 찾지 못함 (설치/조회 실패)")
        } else {
            jpVoices.forEach { v ->
                val net = if (v.isNetworkConnectionRequired) "네트워크" else "오프라인"
                Button(onClick = {
                    scope.launch { tts.speakWithVoice(SAMPLE_JP, v, pitch) }
                }) {
                    Text("▶ ${v.name}  (품질 ${v.quality}, $net)")
                }
            }
        }

        // 원시 목록 덤프 (Logcat 확인용) — 필요할 때만
        Button(onClick = { voiceDump = describeJapaneseVoices(tts, logToLogcat = true) }) {
            Text("목소리 목록 새로고침 / Logcat 덤프")
        }
        if (voiceDump.isNotEmpty()) Text(voiceDump)
    }
}

/** 목소리 비교에 쓰는 공통 샘플 문장 (음색 차이가 잘 드러나도록 조금 길게). */
private const val SAMPLE_JP = "すみません、電車は何時に来ますか。ちょっと急いでいます。"

/**
 * 기기에 설치된 일본어(`ja`) 목소리를 사람이 읽을 수 있는 문자열로 정리한다.
 * 각 Voice: 이름 / 품질(QUALITY_*, 100~500) / 네트워크 필요 여부.
 * @param logToLogcat true면 태그 "KikuVoices"로도 출력 — adb logcat으로 확인 가능.
 */
/** 기기에 설치된 일본어(`ja`) 목소리를 이름순으로 안전하게 조회. 실패 시 빈 리스트. */
private fun japaneseVoices(tts: TextToSpeech): List<Voice> = try {
    tts.voices?.filter { it.locale.language == Locale.JAPANESE.language }
        ?.sortedBy { it.name } ?: emptyList()
} catch (e: Exception) {
    Log.w("KikuVoices", "voices 조회 실패", e)
    emptyList()
}

private fun describeJapaneseVoices(tts: TextToSpeech, logToLogcat: Boolean): String {
    val voices: List<Voice> = japaneseVoices(tts)
    if (logToLogcat) Log.i("KikuVoices", "일본어 목소리 ${voices.size}개")
    return buildString {
        append("일본어 목소리 ${voices.size}개\n")
        voices.forEach { v ->
            val net = if (v.isNetworkConnectionRequired) "네트워크" else "오프라인"
            append("• ${v.name}  (품질 ${v.quality}, $net)\n")
            if (logToLogcat) {
                Log.i(
                    "KikuVoices",
                    "${v.name} | quality=${v.quality} | network=${v.isNetworkConnectionRequired} | features=${v.features}",
                )
            }
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
        setPitch(1.0f)   // 목소리 비교로 바뀐 피치가 남지 않게 기본값 복귀
        language = locale
        speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        cont.invokeOnCancellation { stop() }
    }

/**
 * 특정 [voice]와 [pitch]로 재생 — 목소리 비교(TODO 1)용.
 * `voice`가 로케일을 품고 있어 language 지정 없이 그 목소리로 바로 읽는다.
 */
suspend fun TextToSpeech.speakWithVoice(text: String, voice: Voice, pitch: Float) =
    suspendCancellableCoroutine { cont ->
        val id = "${voice.name}:${text.hashCode()}"
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
            @Deprecated("deprecated in API")
            override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
            override fun onError(utteranceId: String?, errorCode: Int) { if (cont.isActive) cont.resume(Unit) }
        })
        setSpeechRate(1.0f)
        setPitch(pitch)
        this.voice = voice
        speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        cont.invokeOnCancellation { stop() }
    }
