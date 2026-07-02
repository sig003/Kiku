package com.bradlab.kiku

import android.speech.tts.TextToSpeech
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
    private var steps: List<PlaybackStep> = emptyList()
    private var totalSentences = 0
    private var currentStepIndex = 0
    private var speed = 1.0f
    private var playJob: Job? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** 클립을 적재하고 맨 앞으로 리셋. 재생은 하지 않는다. */
    fun load(clip: Clip) {
        playJob?.cancel()
        tts.stop()
        steps = clip.toSteps()
        totalSentences = clip.sentences.size
        currentStepIndex = 0
        _state.value = PlayerUiState(
            title = "${clip.category} — ${clip.title}",
            totalSentences = totalSentences,
            speed = speed,
        )
    }

    fun playPause() {
        if (_state.value.playing) pause() else play()
    }

    /** 현재 위치부터 연속 재생. 이미 재생 중이면 무시. */
    fun play() {
        if (playJob?.isActive == true || steps.isEmpty()) return
        playJob = scope.launch {
            _state.update { it.copy(playing = true, finished = false) }
            while (currentStepIndex < steps.size) {
                val step = steps[currentStepIndex]
                _state.update { it.copy(sentenceIndex = step.sentenceIndex, kind = step.kind) }
                tts.setSpeechRate(speed)                 // 매 스텝 속도 반영 → 다음 스텝부터 적용
                tts.speakAndAwait(step.text, step.locale) // onDone까지 대기 (§2.4)
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
        _state.update { it.copy(sentenceIndex = target, kind = null, finished = false, playing = false) }
        if (wasPlaying) play()
    }

    private fun firstStepIndexOf(sentence: Int): Int =
        steps.indexOfFirst { it.sentenceIndex == sentence }.let { if (it < 0) 0 else it }
}

/** 시퀀서가 노출하는 재생 상태 — UI는 이것만 구독해 그린다(§3 단방향). */
data class PlayerUiState(
    val title: String = "",
    val playing: Boolean = false,
    val sentenceIndex: Int = 0,        // 0-based
    val totalSentences: Int = 0,
    val kind: StepKind? = null,        // 지금 읽는 스텝 종류(JP/KR/단어)
    val speed: Float = 1.0f,
    val finished: Boolean = false,
)
