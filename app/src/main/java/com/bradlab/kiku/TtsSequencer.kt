package com.bradlab.kiku

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 재생 시퀀서 (DESIGN.md §2.5) — 이 앱의 재생 엔진.
 *
 * 클립을 [PlaybackStep] 리스트로 평탄화해두고, 코루틴 루프가 인덱스로 순회한다.
 * 연속 재생·문장 이동·다시듣기·속도는 전부 "인덱스 점프 + 파라미터 변경"으로 환원된다.
 *
 * UI/Activity 수명과 분리돼야 백그라운드 생존이 가능하므로(§2.6),
 * [scope]와 [tts]를 밖에서 주입받는다 — 지금은 화면(검증), TODO 4에서 PlaybackService가 소유.
 * 상태는 [state]로만 노출하고, 조작은 메서드로만 받는다(단방향).
 */
class TtsSequencer(
    private val tts: TextToSpeech,
    private val scope: CoroutineScope,
) {
    private var clip: Clip? = null
    private var steps: List<PlaybackStep> = emptyList()
    private var totalSentences = 0
    private var currentStepIndex = 0
    private var speed = 1.0f
    private var playJob: Job? = null
    // 엔진에 실제로 적용된 값 — 바뀔 때만 재설정해 이음새를 매끄럽게(발화마다 재설정 금지)
    private var appliedRate = -1f
    private var appliedLocale: Locale? = null
    private var appliedVoiceName: String? = null
    // 문장마다 번갈아 쓸 목소리(성별 다양화). 언어별 2개.
    private var jaVoices: List<Voice> = emptyList()
    private var koVoices: List<Voice> = emptyList()
    private var voicesReady = false

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** 클립을 적재하고 [startSentence]로 위치 설정(저장된 진행 위치 복원용). 재생은 하지 않는다. */
    fun load(clip: Clip, startSentence: Int = 0, shuffled: Boolean = false) {
        this.clip = clip
        playJob?.cancel()
        tts.stop()
        steps = clip.toSteps()
        totalSentences = clip.sentences.size
        val start = startSentence.coerceIn(0, (totalSentences - 1).coerceAtLeast(0))
        currentStepIndex = firstStepIndexOf(start)
        _state.value = PlayerUiState(
            clipId = clip.id,
            title = "${clip.category} — ${clip.title}",
            totalSentences = totalSentences,
            speed = speed,
            shuffled = shuffled,
        ).withSentence(start)
    }

    fun playPause() {
        if (_state.value.playing) pause() else play()
    }

    /** 현재 위치부터 연속 재생. 이미 재생 중이면 무시. */
    fun play() {
        if (playJob?.isActive == true || steps.isEmpty()) return
        ensureVoices()
        playJob = scope.launch {
            _state.update { it.copy(playing = true, finished = false) }
            while (currentStepIndex < steps.size) {
                val step = steps[currentStepIndex]
                _state.update { it.withSentence(step.sentenceIndex).copy(kind = step.kind) }
                if (speed != appliedRate) { tts.setSpeechRate(speed); appliedRate = speed }        // 속도 바뀔 때만
                applyVoice(step)   // 문장 홀짝으로 목소리 번갈아(+언어), 바뀔 때만 재설정
                tts.speakAndAwait(vocalize(step.text, step.locale)) // onDone까지 대기 (§2.4)
                if (step.pauseAfterMs > 0) delay(step.pauseAfterMs)
                currentStepIndex++
            }
            // 끝까지 도달 → 정지 상태로. (취소로 빠져나올 땐 finally가 없어도 아래 도달 안 함)
            _state.update { it.copy(playing = false, finished = true, kind = null) }
        }
    }

    /** 일시정지: 루프 취소 + 발화 중단. 현재 스텝 유지(재개 시 그 스텝부터). */
    fun pause() {
        playJob?.cancel()
        tts.stop()
        _state.update { it.copy(playing = false) }
    }

    fun next()   = jumpToSentence(_state.value.sentenceIndex + 1)
    fun prev()   = jumpToSentence(_state.value.sentenceIndex - 1)
    fun replay() = jumpToSentence(_state.value.sentenceIndex)   // 현재 문장 처음부터

    /** 속도 변경 — 다음 스텝부터 반영(§2.5). */
    fun setSpeed(value: Float) {
        speed = value
        _state.update { it.copy(speed = value) }
    }

    /** 세션 비우기 — 미니바 닫기(dismiss)용. 재생 중단 + 상태를 기본값으로 되돌려 UI에서 세션이 사라지게. */
    fun clear() {
        playJob?.cancel()
        tts.stop()
        clip = null
        steps = emptyList()
        totalSentences = 0
        currentStepIndex = 0
        _state.value = PlayerUiState()
    }

    /** 자원 정리 — 화면/서비스가 사라질 때. tts는 소유자가 닫는다. */
    fun release() {
        playJob?.cancel()
    }

    // ── 내부 ──────────────────────────────────────────────

    /**
     * 문장 단위 점프. 진행 중 스텝/정지를 확실히 끊기 위해 루프를 취소하고,
     * 인덱스를 옮긴 뒤 재생 중이었으면 재개한다(§2.5의 점프 = 인덱스 이동).
     */
    private fun jumpToSentence(sentence: Int) {
        if (totalSentences == 0) return
        val target = sentence.coerceIn(0, totalSentences - 1)
        val wasPlaying = _state.value.playing
        playJob?.cancel()
        tts.stop()
        currentStepIndex = firstStepIndexOf(target)
        _state.update { it.withSentence(target).copy(kind = null, finished = false, playing = false) }
        if (wasPlaying) play()
    }

    private fun firstStepIndexOf(sentence: Int): Int =
        steps.indexOfFirst { it.sentenceIndex == sentence }.let { if (it < 0) 0 else it }

    /** 언어별로 "문장마다 번갈아" 쓸 목소리 2개를 한 번만 고른다(성별 다양화). TTS 초기화 후 호출. */
    private fun ensureVoices() {
        if (voicesReady) return
        voicesReady = true
        val all = try { tts.voices?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
        jaVoices = pickAlternatingVoices(all, "ja")
        koVoices = pickAlternatingVoices(all, "ko")
        Log.i("KikuVoices", "번갈아 사용 — ja=${jaVoices.map { it.name }} ko=${koVoices.map { it.name }}")
    }

    /** 오프라인·뚜렷한 화자(-x-, 구형 htm 제외) 중 서로 다른 목소리 2개. 없으면 1개 폴백. */
    private fun pickAlternatingVoices(all: List<Voice>, lang: String): List<Voice> {
        val cands = all.filter {
            it.locale.language == lang && !it.isNetworkConnectionRequired &&
                it.name.contains("-x-") && !it.name.contains("-htm-")
        }.sortedBy { it.name }
        val distinct = cands.distinctBy { it.name.substringBeforeLast("-") } // -local/-network 중복 제거
        return distinct.take(2).ifEmpty { all.filter { it.locale.language == lang }.take(1) }
    }

    /** 현재 스텝에 목소리를 적용 — 문장 인덱스 홀짝으로 두 목소리를 번갈아. 바뀔 때만 재설정. */
    private fun applyVoice(step: PlaybackStep) {
        val voices = if (step.locale.language == Locale.JAPANESE.language) jaVoices else koVoices
        if (voices.isNotEmpty()) {
            val v = voices[step.sentenceIndex % voices.size]
            if (v.name != appliedVoiceName) {
                tts.voice = v
                appliedVoiceName = v.name
                appliedLocale = step.locale   // setVoice가 언어도 설정하므로 동기화
            }
        } else if (step.locale != appliedLocale) {
            tts.language = step.locale
            appliedLocale = step.locale
        }
    }

    /**
     * 낭독용 텍스트 변환 — 문법 자리표시 물결표(～) 처리.
     * 한국어 "～해요" → "무엇무엇 해요"(모국어라 패턴 힌트 자연스러움),
     * 일본어 "～てください" → "てください"(초보엔 なになに가 오히려 헷갈려 그냥 제거, 화면 칩엔 ～ 보임).
     */
    private fun vocalize(text: String, locale: Locale): String {
        val placeholder = if (locale.language == Locale.KOREAN.language) "무엇무엇 " else ""
        return text.replace("～", placeholder).replace("~", placeholder)
    }

    /** 상태에 현재 문장 내용(일/한/단어)을 채운다. */
    private fun PlayerUiState.withSentence(i: Int): PlayerUiState {
        val s = clip?.sentences?.getOrNull(i)
        return copy(
            sentenceIndex = i,
            sentenceJp = s?.jp ?: "",
            sentenceKr = s?.kr ?: "",
            words = s?.words ?: emptyList(),
        )
    }
}

/** 시퀀서가 노출하는 재생 상태 — UI는 이것만 구독해 그린다(§3 단방향). */
data class PlayerUiState(
    val clipId: Int = -1,
    val title: String = "",
    val playing: Boolean = false,
    val sentenceIndex: Int = 0,        // 0-based
    val totalSentences: Int = 0,
    val sentenceJp: String = "",       // 현재 문장 일본어
    val sentenceKr: String = "",       // 현재 문장 한국어
    val words: List<Word> = emptyList(), // 현재 문장 단어
    val kind: StepKind? = null,        // 지금 읽는 스텝 종류(JP/KR/단어)
    val speed: Float = 1.0f,
    val shuffled: Boolean = false,     // 무작위 순서 재생 중인지(랜덤/셔플)
    val finished: Boolean = false,
)
