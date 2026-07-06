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
    val sentences: List<Sentence>,
)

// ── 재생 패턴 프리셋 (§2.7) ───────────────────────────────────────

@Serializable
enum class ClipMode { DRILL, DIALOGUE, LISTENING }

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
}

/** 클립이 실제로 쓰는 패턴: 커스텀 pattern이 있으면 그것, 없으면 mode 프리셋. */
val Clip.effectivePattern: PlaybackPattern get() = pattern ?: mode.toPattern()

// ── 스텝 평탄화 (§2.3) ────────────────────────────────────────────

// JP: 일본어 문장 / KR: 한국어 해석 / WORD_JP: 일본어 단어 / WORD_KR: 단어 한국어 뜻
enum class StepKind { JP, KR, WORD_JP, WORD_KR }

data class PlaybackStep(
    val sentenceIndex: Int,   // 어느 문장에 속하는지 (점프/하이라이트용)
    val kind: StepKind,
    val text: String,
    val locale: Locale,
    val pauseAfterMs: Long = 0,
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

/** 클립 전체 → 모든 문장의 스텝을 하나로 펼침 → 끊김 없이 연속 재생. */
fun Clip.toSteps(): List<PlaybackStep> {
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

private const val OUTRO_JP = "聞き取りが終わりました。"
private const val OUTRO_KR = "듣기가 끝났습니다."

// ── 검증용 샘플 클립 (TODO 3에서 JSON으로 대체) ──────────────────────

fun sampleDrillClip(): Clip = Clip(
    id = 1,
    category = "N4 회사생활",
    title = "회의·전화·이메일",
    mode = ClipMode.DRILL,
    sentences = listOf(
        Sentence(
            id = 1, jp = "昨日は会社を休みました。", kr = "어제는 회사를 쉬었습니다.",
            words = listOf(Word("昨日", "어제"), Word("会社", "회사"), Word("休む", "쉬다")),
        ),
        Sentence(
            id = 2, jp = "今日はいい天気ですね。", kr = "오늘은 날씨가 좋네요.",
            words = listOf(Word("今日", "오늘"), Word("天気", "날씨")),
        ),
        Sentence(
            id = 3, jp = "電車に乗って学校へ行きます。", kr = "전철을 타고 학교에 갑니다.",
            words = listOf(Word("電車", "전철"), Word("学校", "학교")),
        ),
    ),
)
