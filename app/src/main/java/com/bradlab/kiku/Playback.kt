package com.bradlab.kiku

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * 재생 데이터 모델 + 스텝 평탄화 (DESIGN.md §2.3, §2.7, §4).
 *
 * 지금은 TtsSequencer(TODO 2) 검증용 — 순수 Kotlin 데이터 클래스와 하드코딩 샘플 클립.
 * TODO 3에서 @Serializable + assets 클립 JSON 로더로 확장한다(모델 형태는 그대로 유지).
 */

// ── 콘텐츠 모델 (§4) ──────────────────────────────────────────────

@Serializable
data class Word(
    val jp: String,   // 会社
    val kr: String,   // 회사
)

@Serializable
data class Sentence(
    val id: Int,
    val speaker: String? = null,                  // 대화·청해의 화자. null=일반 문장
    val jp: String,
    val kr: String,
    val words: List<Word> = emptyList(),
    val patternOverride: PlaybackPattern? = null, // 이 문장만 다르게 (최우선)
)

@Serializable
data class Clip(
    val id: Int,
    val level: String = "N4",                     // 난이도 "N5"/"N4"/"N3" — 목록 배지·필터용
    val category: String,
    val title: String,
    val mode: ClipMode = ClipMode.DRILL,
    val pattern: PlaybackPattern? = null,         // 클립 전체 커스텀 (있으면 mode 덮어씀)
    val sentences: List<Sentence> = emptyList(),
    val quiz: List<QuizItem> = emptyList(),       // QUIZ 모드(즉시응답) 전용 문항
)

/**
 * 즉시응답(即時応答) 문항 — JLPT 청해 유형(N3 문제5 / N4 문제4).
 * 짧은 말([promptJp])을 듣고, 3개 응답([options]) 중 알맞은 것([answer], 1-based)을 고른다.
 */
@Serializable
data class QuizItem(
    val id: Int,
    val promptJp: String,
    val promptKr: String,
    val options: List<Word>,   // jp/kr 쌍(응답 3개)
    val answer: Int,           // 정답 번호(1~3)
)

// ── 재생 패턴 프리셋 (§2.7) ───────────────────────────────────────

@Serializable
enum class ClipMode { DRILL, DIALOGUE, LISTENING, QUIZ }

@Serializable
data class PlaybackPattern(
    val jpRepeat: Int = 3,
    val pauseBetweenRepeatsMs: Long = 1500,  // JP 회차 사이 정지 — 한 번씩 듣고 곱씹을 시간
    val pauseAfterJpMs: Long = 3000,         // 마지막 JP 회차 뒤 정지 — 해석할 시간
    val readKr: Boolean = true,
    val pauseAfterKrMs: Long = 800,          // 한국어 뒤 정지
    val jpRepeatAfterKr: Int = 1,            // 뜻을 들은 뒤 다시 듣는 일본어 횟수
    val readWords: Boolean = true,
    val pauseBetweenSentencesMs: Long = 0,
)

fun ClipMode.toPattern(): PlaybackPattern = when (this) {
    ClipMode.DRILL     -> PlaybackPattern(jpRepeat = 3, pauseBetweenRepeatsMs = 2200, pauseAfterJpMs = 3000, readKr = true,  pauseAfterKrMs = 800, jpRepeatAfterKr = 1, readWords = true,  pauseBetweenSentencesMs = 2000)
    ClipMode.DIALOGUE  -> PlaybackPattern(jpRepeat = 3, pauseBetweenRepeatsMs = 2200, pauseAfterJpMs = 3000, readKr = true,  pauseAfterKrMs = 800, jpRepeatAfterKr = 1, readWords = true,  pauseBetweenSentencesMs = 2000)
    ClipMode.LISTENING -> PlaybackPattern(jpRepeat = 1, pauseBetweenRepeatsMs = 0,    pauseAfterJpMs = 0,    readKr = false, pauseAfterKrMs = 0,   jpRepeatAfterKr = 0, readWords = false, pauseBetweenSentencesMs = 800)
    ClipMode.QUIZ      -> PlaybackPattern()   // 미사용 — quizSteps()가 직접 스텝을 구성
}

/** 클립이 실제로 쓰는 패턴: 커스텀 pattern이 있으면 그것, 없으면 mode 프리셋. */
val Clip.effectivePattern: PlaybackPattern get() = pattern ?: mode.toPattern()

// ── 스텝 평탄화 (§2.3) ────────────────────────────────────────────

// JP: 일본어 문장 / KR: 한국어 해석 / WORD_JP: 일본어 단어 / WORD_KR: 단어 한국어 뜻 / CHIME: 효과음(딩동)
enum class StepKind { JP, KR, WORD_JP, WORD_KR, CHIME }

data class PlaybackStep(
    val sentenceIndex: Int,   // 어느 문장/문항에 속하는지 (점프/하이라이트용)
    val kind: StepKind,
    val text: String,
    val locale: Locale,
    val pauseAfterMs: Long = 0,
    val speaker: String? = null,   // QUIZ 음성 배역: "A"=질문, "B"=응답. 그 외 null(내레이터)
)

/** 한 문장 → 스텝 펼침. 펼치는 방식은 PlaybackPattern이 결정한다. */
fun Sentence.toSteps(index: Int, p: PlaybackPattern): List<PlaybackStep> = buildList {
    // JP 최초 회차 — 회차 사이 정지를 둬 한 번씩 듣고 곱씹게 한다
    repeat(maxOf(0, p.jpRepeat - 1)) {
        add(PlaybackStep(index, StepKind.JP, jp, Locale.JAPANESE, pauseAfterMs = p.pauseBetweenRepeatsMs))
    }
    // 마지막 JP 회차 뒤 정지(해석할 시간)
    if (p.jpRepeat >= 1) add(PlaybackStep(index, StepKind.JP, jp, Locale.JAPANESE, pauseAfterMs = p.pauseAfterJpMs))
    // 한국어 해석
    if (p.readKr) add(PlaybackStep(index, StepKind.KR, kr, Locale.KOREAN, pauseAfterMs = p.pauseAfterKrMs))
    // 뜻을 들은 뒤 다시 듣는 일본어
    repeat(maxOf(0, p.jpRepeatAfterKr)) {
        add(PlaybackStep(index, StepKind.JP, jp, Locale.JAPANESE, pauseAfterMs = p.pauseBetweenRepeatsMs))
    }
    // 단어(일→한)
    if (p.readWords) words.forEach { w ->
        add(PlaybackStep(index, StepKind.WORD_JP, w.jp, Locale.JAPANESE))
        add(PlaybackStep(index, StepKind.WORD_KR, w.kr, Locale.KOREAN))
    }
}

/**
 * QUIZ 클립은 sentences가 없어도 되므로, 재생/내비게이션용으로 문항 prompt를 문장으로 파생한다.
 * (sentences.size = 문항 수 → totalSentences·다음/이전·화면 표시가 그대로 동작)
 */
fun Clip.normalized(): Clip =
    if (mode == ClipMode.QUIZ && sentences.isEmpty() && quiz.isNotEmpty())
        copy(sentences = quiz.map { Sentence(id = it.id, speaker = "A", jp = it.promptJp, kr = it.promptKr) })
    else this

/** 클립 전체 → 모든 문장의 스텝을 하나로 펼침 → 끊김 없이 연속 재생. */
fun Clip.toSteps(): List<PlaybackStep> {
    if (mode == ClipMode.QUIZ) return quizSteps()
    if (sentences.isEmpty()) return emptyList()
    // 대화는 (A질문 + B답변)을 한 세트로 묶어 주고받기, 그 외는 문장별 드릴.
    val body = if (mode == ClipMode.DIALOGUE) dialogueSteps() else drillSteps()
    // 클립 끝 안내: 마지막 문장 뒤 일본어 → 한국어로 "듣기가 끝났습니다"
    val last = sentences.lastIndex
    return body + listOf(
        PlaybackStep(last, StepKind.JP, OUTRO_JP, Locale.JAPANESE, pauseAfterMs = 400),
        PlaybackStep(last, StepKind.KR, OUTRO_KR, Locale.KOREAN),
    )
}

/** 문장별 드릴 평탄화(DRILL/LISTENING). */
private fun Clip.drillSteps(): List<PlaybackStep> {
    val base = effectivePattern
    return sentences.flatMapIndexed { i, s ->
        val p = s.patternOverride ?: base
        val steps = s.toSteps(i, p)
        if (p.pauseBetweenSentencesMs > 0 && steps.isNotEmpty()) {
            steps.dropLast(1) + steps.last().copy(pauseAfterMs = steps.last().pauseAfterMs + p.pauseBetweenSentencesMs)
        } else steps
    }
}

/**
 * 대화 평탄화(DIALOGUE) — (A질문 + B답변)을 한 세트로.
 * [A일본어, B일본어] ×jpRepeat → [A한국어, B한국어] → [A일본어, B일본어] ×jpRepeatAfterKr → 단어(A,B).
 */
private fun Clip.dialogueSteps(): List<PlaybackStep> {
    val p = effectivePattern
    val turnGap = 700L   // A→B 사이 자연스러운 턴 간격
    val out = ArrayList<PlaybackStep>()

    fun exchange(a: Sentence, aIdx: Int, b: Sentence?, bIdx: Int, kind: StepKind, locale: Locale, endPause: Long, text: (Sentence) -> String) {
        out += PlaybackStep(aIdx, kind, text(a), locale, pauseAfterMs = if (b != null) turnGap else endPause)
        if (b != null) out += PlaybackStep(bIdx, kind, text(b), locale, pauseAfterMs = endPause)
    }

    var i = 0
    while (i < sentences.size) {
        val aIdx = i; val a = sentences[i]
        val bIdx = i + 1; val b = sentences.getOrNull(bIdx)

        val rounds = maxOf(1, p.jpRepeat)
        for (r in 0 until rounds) {
            val end = if (r == rounds - 1) p.pauseAfterJpMs else p.pauseBetweenRepeatsMs
            exchange(a, aIdx, b, bIdx, StepKind.JP, Locale.JAPANESE, end) { it.jp }
        }
        if (p.readKr) exchange(a, aIdx, b, bIdx, StepKind.KR, Locale.KOREAN, p.pauseAfterKrMs) { it.kr }
        repeat(maxOf(0, p.jpRepeatAfterKr)) {
            exchange(a, aIdx, b, bIdx, StepKind.JP, Locale.JAPANESE, p.pauseBetweenRepeatsMs) { it.jp }
        }
        if (p.readWords) {
            a.words.forEach { out += PlaybackStep(aIdx, StepKind.WORD_JP, it.jp, Locale.JAPANESE); out += PlaybackStep(aIdx, StepKind.WORD_KR, it.kr, Locale.KOREAN) }
            b?.words?.forEach { out += PlaybackStep(bIdx, StepKind.WORD_JP, it.jp, Locale.JAPANESE); out += PlaybackStep(bIdx, StepKind.WORD_KR, it.kr, Locale.KOREAN) }
        }
        if (out.isNotEmpty() && p.pauseBetweenSentencesMs > 0) {
            out[out.lastIndex] = out.last().copy(pauseAfterMs = out.last().pauseAfterMs + p.pauseBetweenSentencesMs)
        }
        i += 2
    }
    return out
}

/**
 * 즉시응답(QUIZ) 평탄화 — 문항마다 "시험 → 정답 → 해설(복습)".
 *
 * [문제 N] → (1단계·전부 일본어) 질문A → 1번/응답B → 2번/응답B → 3번/응답B → (생각 틈) → "정답 N번"
 *          → (2단계·일+한) 질문A→해석 → 1번 응답B→해석 → 2번… → 3번…(정답엔 "(정답)")
 * 음성: 질문=화자A, 응답=화자B, 번호·정답·해석=내레이터(한국어).
 */
private fun Clip.quizSteps(): List<PlaybackStep> {
    val JA = Locale.JAPANESE; val KO = Locale.KOREAN
    val out = ArrayList<PlaybackStep>()
    fun bumpLastPause(extra: Long) {
        if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(pauseAfterMs = out.last().pauseAfterMs + extra)
    }
    quiz.forEachIndexed { i, q ->
        out += PlaybackStep(i, StepKind.JP, "問題${i + 1}。", JA, pauseAfterMs = 1800)
        // 1단계: 시험(전부 일본어)
        out += PlaybackStep(i, StepKind.JP, q.promptJp, JA, pauseAfterMs = 1200, speaker = "A")
        q.options.forEachIndexed { k, o ->
            out += PlaybackStep(i, StepKind.JP, "${k + 1}", JA, pauseAfterMs = 250)
            out += PlaybackStep(i, StepKind.JP, o.jp, JA, pauseAfterMs = 1200, speaker = "B")
        }
        bumpLastPause(1500)   // 응답3 뒤 = 생각할 틈(총 2.7초)
        out += PlaybackStep(i, StepKind.JP, "正解は${q.answer}番です。", JA, pauseAfterMs = 900)
        // 2단계: 복습(반복) — 딩동 신호 후 일본어 + 한국어 해석
        out += PlaybackStep(i, StepKind.CHIME, "", JA, pauseAfterMs = 500)
        out += PlaybackStep(i, StepKind.JP, q.promptJp, JA, pauseAfterMs = 1000, speaker = "A")
        out += PlaybackStep(i, StepKind.KR, q.promptKr, KO, pauseAfterMs = 900)
        q.options.forEachIndexed { k, o ->
            out += PlaybackStep(i, StepKind.JP, "${k + 1}", JA, pauseAfterMs = 200)
            out += PlaybackStep(i, StepKind.JP, o.jp, JA, pauseAfterMs = 1000, speaker = "B")
            val kr = if (q.answer == k + 1) "${o.kr} (정답)" else o.kr
            out += PlaybackStep(i, StepKind.KR, kr, KO, pauseAfterMs = 800)
        }
        bumpLastPause(1800)   // 문항 간 간격
    }
    val last = (quiz.size - 1).coerceAtLeast(0)
    out += PlaybackStep(last, StepKind.JP, OUTRO_JP, JA, pauseAfterMs = 400)
    out += PlaybackStep(last, StepKind.KR, OUTRO_KR, KO)
    return out
}

private const val OUTRO_JP = "聞き取りが終わりました。"
private const val OUTRO_KR = "듣기가 끝났습니다."
