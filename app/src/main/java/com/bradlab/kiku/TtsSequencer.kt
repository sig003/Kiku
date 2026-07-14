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
    private val playChime: (suspend () -> Unit)? = null,   // CHIME 스텝에서 효과음(딩동) 재생·대기
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
    private var speakerOrder: List<String> = emptyList()   // 대화 화자 등장 순서(화자별 목소리 매핑용)
    private var pairVoiceSwap: List<Boolean> = emptyList()  // 대화: 짝(exchange)마다 A/B 남녀 무작위 교체

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** 클립을 적재하고 [startSentence]로 위치 설정(저장된 진행 위치 복원용). 재생은 하지 않는다. */
    fun load(clip: Clip, startSentence: Int = 0, shuffled: Boolean = false) {
        this.clip = clip
        playJob?.cancel()
        tts.stop()
        steps = clip.toSteps()
        totalSentences = clip.sentences.size
        speakerOrder = clip.sentences.mapNotNull { it.speaker }.distinct()   // 대화: A/B… 등장 순서
        // 대화면 짝마다 남녀 순서를 무작위로(남→여, 여→남 섞기). 화자 2명일 때만.
        pairVoiceSwap = if (speakerOrder.size >= 2)
            List((clip.sentences.size + 1) / 2) { kotlin.random.Random.nextBoolean() }
        else emptyList()
        val start = startSentence.coerceIn(0, (totalSentences - 1).coerceAtLeast(0))
        currentStepIndex = firstStepIndexOf(start)
        _state.value = PlayerUiState(
            clipId = clip.id,
            mode = clip.mode,
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
                _state.update { st ->
                    if (clip?.mode == ClipMode.QUIZ || clip?.mode == ClipMode.LISTENING) {
                        // QUIZ/LISTENING: 화면은 스텝의 showJp/showKr을 따라감(null=유지). speaker 배지는 숨김.
                        var n = st.copy(sentenceIndex = step.sentenceIndex, kind = step.kind, speaker = null)
                        step.showJp?.let { n = n.copy(sentenceJp = it, words = emptyList()) }
                        step.showKr?.let { n = n.copy(sentenceKr = it) }
                        n
                    } else {
                        st.withSentence(step.sentenceIndex).copy(kind = step.kind)
                    }
                }
                if (step.kind == StepKind.CHIME) {
                    playChime?.invoke()   // 딩동 효과음 재생·대기 (음원 길이만큼)
                } else {
                    if (speed != appliedRate) { tts.setSpeechRate(speed); appliedRate = speed }    // 속도 바뀔 때만
                    applyVoice(step)   // 문장 홀짝으로 목소리 번갈아(+언어), 바뀔 때만 재설정
                    tts.speakAndAwait(vocalize(step.text, step.locale)) // onDone까지 대기 (§2.4)
                }
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
        Log.i("KikuVoices", "선택 — ja=${jaVoices.map { it.name }} ko=${koVoices.map { it.name }} (여성, 남성 순)")
    }

    /** 오프라인·뚜렷한 화자(-x-, 구형 htm 제외) 중 성별이 다른 목소리 2개(여성, 남성 순). 없으면 폴백. */
    private fun pickAlternatingVoices(all: List<Voice>, lang: String): List<Voice> {
        val cands = all.filter {
            it.locale.language == lang && !it.isNetworkConnectionRequired &&
                it.name.contains("-x-") && !it.name.contains("-htm-")
        }.sortedBy { it.name }.distinctBy { it.name.substringBeforeLast("-") } // -local/-network 중복 제거
        if (cands.isEmpty()) return all.filter { it.locale.language == lang }.take(1)
        // 알려진 남성 코드(구글 온디바이스)로 [여성, 남성] 순서를 맞춘다 → 언어 간 슬롯 성별 정렬.
        val maleCodes = if (lang == "ko") listOf("koc", "kod") else listOf("jac", "jad")
        val male = cands.firstOrNull { v -> maleCodes.any { v.name.contains("-$it") } }
        val female = cands.firstOrNull { it != male }
        return listOfNotNull(female, male).ifEmpty { cands.take(2) }.take(2)
    }

    /** 현재 스텝에 목소리를 적용 — 문장 인덱스 홀짝으로 두 목소리를 번갈아. 바뀔 때만 재설정. */
    private fun applyVoice(step: PlaybackStep) {
        val isJa = step.locale.language == Locale.JAPANESE.language
        val voices = if (isJa) jaVoices else koVoices
        if (voices.isNotEmpty()) {
            // 대화: 일본어 문장 + 한국어 해석은 화자별 목소리(같은 슬롯 → 성별 일치, 짝마다 순서 무작위).
            //       단어(일/한)만 한 목소리(나레이터, 인덱스 0)로 통일.
            // 일반 문장(DRILL): 문장 순서로 번갈아(기존 동작).
            val idx = if (clip?.mode == ClipMode.QUIZ || clip?.mode == ClipMode.LISTENING) {
                // 화자별 목소리(일본어·한국어 공통). 男=슬롯1(남성), 女=슬롯0(여성).
                // 화자 없는 것(상황·문제·선택지·번호·정답)은 내레이터(슬롯0).
                when (step.speaker) {
                    "B", "男" -> 1
                    else -> 0   // "A"/"女"/null
                }
            } else {
                val speaker = clip?.sentences?.getOrNull(step.sentenceIndex)?.speaker
                val speakerIdx = speaker?.let { speakerOrder.indexOf(it) }?.takeIf { it >= 0 }
                val isWord = step.kind == StepKind.WORD_JP || step.kind == StepKind.WORD_KR
                when {
                    speakerIdx != null && !isWord -> {   // 일본어 문장 + 한국어 해석 → 화자별
                        val swap = pairVoiceSwap.getOrElse(step.sentenceIndex / 2) { false }
                        speakerIdx + if (swap) 1 else 0
                    }
                    speakerIdx != null -> 0              // 대화의 단어 → 고정 목소리
                    else -> step.sentenceIndex            // 일반 문장
                }
            }
            val v = voices[idx % voices.size]
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
     * 낭독용 텍스트 변환.
     *  - 문법 라벨 괄호만 제거: 단어 뜻의 "(피해수동)" 등이 음성으로 읽히지 않게(화면 칩엔 유지).
     *    "(정답)"·"(남에게)"처럼 뜻에 필요한 괄호는 남긴다.
     *  - 문법 자리표시 물결표(～): 한국어 "～해요" → "무엇무엇 해요"(패턴 힌트), 일본어는 그냥 제거.
     */
    private fun vocalize(text: String, locale: Locale): String {
        val placeholder = if (locale.language == Locale.KOREAN.language) "무엇무엇 " else ""
        return text
            .replace(GRAMMAR_LABEL, "")   // 문법 라벨 괄호만 제거
            .replace("～", placeholder).replace("~", placeholder)
            .replace(Regex("\\s{2,}"), " ").trim()
    }

    /** 상태에 현재 문장 내용(일/한/단어)을 채운다. */
    private fun PlayerUiState.withSentence(i: Int): PlayerUiState {
        val s = clip?.sentences?.getOrNull(i)
        return copy(
            sentenceIndex = i,
            sentenceJp = s?.jp ?: "",
            sentenceKr = s?.kr ?: "",
            words = s?.words ?: emptyList(),
            speaker = s?.speaker,
        )
    }
}

/**
 * 낭독 시 제거할 "문법 라벨 괄호" — 괄호 안에 문법 용어가 들어간 것만(예: "(피해수동)", "(겸양)").
 * "(정답)"·"(남에게)"처럼 뜻에 필요한 괄호는 매칭 안 돼 그대로 읽힌다.
 */
private val GRAMMAR_LABEL = Regex(
    "[（(][^）)]*(?:수동|사역|겸양|존경|겸손|경어|전문|양태|의지|추측|회상|가정|명령|자동사|타동사)[^）)]*[）)]"
)

/** 시퀀서가 노출하는 재생 상태 — UI는 이것만 구독해 그린다(§3 단방향). */
data class PlayerUiState(
    val clipId: Int = -1,
    val mode: ClipMode = ClipMode.DRILL,   // 셔플 버튼 노출 판단용(DRILL만 셔플 가능)
    val title: String = "",
    val playing: Boolean = false,
    val sentenceIndex: Int = 0,        // 0-based
    val totalSentences: Int = 0,
    val speaker: String? = null,       // 대화 화자(A/B…). 일반 문장은 null
    val sentenceJp: String = "",       // 현재 문장 일본어
    val sentenceKr: String = "",       // 현재 문장 한국어
    val words: List<Word> = emptyList(), // 현재 문장 단어
    val kind: StepKind? = null,        // 지금 읽는 스텝 종류(JP/KR/단어)
    val speed: Float = 1.0f,
    val shuffled: Boolean = false,     // 무작위 순서 재생 중인지(랜덤/셔플)
    val finished: Boolean = false,
)
