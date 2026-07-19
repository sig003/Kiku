package com.bradlab.kiku

import kotlinx.serialization.SerialName
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
    val listening: List<ListeningItem> = emptyList(), // LISTENING 모드(실전 청해) 전용 문항
    val hook: Hook? = null,                       // CULTURE(일본 한입) 도입 훅
    val content: CultureContent? = null,          // CULTURE 본문(나레이션·단어·문법·표현·드릴)
)

// ── 일본 한입(CULTURE) 모델 — 로드맵 ⑩ ─────────────────────────────
// 일본 문화/상식을 "왜?"로 짧게 → 일본어 나레이션(중간 리콜) → 한국어 해설 → 단어·문법·표현 → 실전 대화.

/** 도입 훅. type=WHY/REALLY/HOW/TOP3…(필터·추천용). kr=호기심 훅(길어도 됨), jp=일본어 한 줄(선택). */
@Serializable
data class Hook(val type: String = "WHY", val kr: String = "", val jp: String = "")

/** 나레이션 중간 능동 복습 — 질문(일본어) → 틈 → 답(한국어). */
@Serializable
data class Recall(val jp: String, val kr: String)

/** 나레이션 한 줄 — 일본어가 주, 한국어(kr)는 자막용(음성 off). recall 있으면 그 뒤 리콜. */
@Serializable
data class Narration(val speaker: String = "N", val jp: String, val kr: String, val recall: Recall? = null)

/** 단어 — reading(후리가나)은 화면용, 음성은 jp/kr만. */
@Serializable
data class CultureWord(val jp: String, val reading: String = "", val kr: String)

/** 문법 한 항목 — 제목·뜻 + 예문(jp/kr). */
@Serializable
data class GrammarNote(val title: String, val meaning: String, val example: Line)

/** 오늘 바로 써먹는 표현 한 문장. */
@Serializable
data class Phrase(val jp: String, val kr: String)

/** 끝에 이어 듣는 실전 대화 참조 — 기존 대화 클립을 clipId로 가리킴(콘텐츠 중복 안 만듦). */
@Serializable
data class DrillRef(val level: String = "N4", val mode: String = "DIALOGUE", val clipId: Int = -1, val title: String = "")

/** CULTURE 본문. */
@Serializable
data class CultureContent(
    val narration: List<Narration> = emptyList(),
    @SerialName("summary_kr") val summaryKr: String = "",
    val words: List<CultureWord> = emptyList(),
    val grammar: List<GrammarNote> = emptyList(),
    val phrase: Phrase? = null,
    val drill: DrillRef? = null,
)

/**
 * 실전 청해 문항 — JLPT 청해 課題理解/ポイント理解 유형(問題1·2).
 * [상황][본문 대화][문제]를 듣고, [options] 중 알맞은 것([answer], 1-based)을 고른다.
 */
@Serializable
data class ListeningItem(
    val id: Int,
    val setupJp: String, val setupKr: String,       // 상황 안내
    val passage: List<Line>,                        // 본문(대화/모놀로그)
    val questionJp: String, val questionKr: String, // 문제
    val options: List<Word>,                        // 선택지(보통 4개)
    val answer: Int,                                // 정답 번호(1~4)
)

/** 청해 본문 한 줄 — 화자(A/B)별 목소리. */
@Serializable
data class Line(val speaker: String? = null, val jp: String, val kr: String)

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
enum class ClipMode { DRILL, DIALOGUE, LISTENING, QUIZ, CULTURE }

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
    ClipMode.CULTURE   -> PlaybackPattern()   // 미사용 — cultureSteps()가 직접 스텝을 구성
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
    // QUIZ 전용 화면 표시 텍스트. null=화면 그대로 유지, ""=그 부분 비움, 값=그 텍스트로 표시.
    // (DRILL/대화는 화면을 문장 단위로 그리므로 사용하지 않음.)
    val showJp: String? = null,
    val showKr: String? = null,
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
fun Clip.normalized(): Clip = when {
    mode == ClipMode.QUIZ && sentences.isEmpty() && quiz.isNotEmpty() ->
        copy(sentences = quiz.map { Sentence(id = it.id, speaker = "A", jp = it.promptJp, kr = it.promptKr) })
    mode == ClipMode.LISTENING && sentences.isEmpty() && listening.isNotEmpty() ->
        copy(sentences = listening.map { Sentence(id = it.id, jp = it.setupJp, kr = it.setupKr) })
    mode == ClipMode.CULTURE && sentences.isEmpty() && content != null ->
        copy(sentences = content.narration.mapIndexed { i, n -> Sentence(id = i + 1, jp = n.jp, kr = n.kr) })
    else -> this
}

/**
 * 셔플 — 모드별 "블록" 단위로 섞는다(맥락 유지).
 * 대화=A/B 짝, 퀴즈·실전청해=문항, 그 외(단문)=문장 낱개.
 */
fun Clip.blockShuffled(): Clip = when {
    mode == ClipMode.QUIZ && quiz.isNotEmpty() ->
        copy(quiz = quiz.shuffled(), sentences = emptyList()).normalized()
    mode == ClipMode.LISTENING && listening.isNotEmpty() ->
        copy(listening = listening.shuffled(), sentences = emptyList()).normalized()
    mode == ClipMode.DIALOGUE ->
        copy(sentences = sentences.chunked(2).shuffled().flatten())
    mode == ClipMode.CULTURE -> this   // 한입은 훅→나레이션→해설 순서가 스토리 → 셔플 안 함
    else ->
        copy(sentences = sentences.shuffled())
}

/** 클립 전체 → 모든 문장의 스텝을 하나로 펼침 → 끊김 없이 연속 재생. */
fun Clip.toSteps(): List<PlaybackStep> {
    if (mode == ClipMode.CULTURE) return cultureSteps()
    if (mode == ClipMode.QUIZ) return quizSteps()
    if (mode == ClipMode.LISTENING && listening.isNotEmpty()) return listeningSteps()
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
        // 1단계: 시험(전부 일본어). 화면엔 질문만 — 예문/정답은 귀로만(showJp/Kr 안 건드림).
        out += PlaybackStep(i, StepKind.JP, "問題${i + 1}。", JA, pauseAfterMs = 1800, showJp = q.promptJp, showKr = "")
        out += PlaybackStep(i, StepKind.JP, q.promptJp, JA, pauseAfterMs = 1200, speaker = "A")
        q.options.forEachIndexed { k, o ->
            out += PlaybackStep(i, StepKind.JP, "${k + 1}", JA, pauseAfterMs = 250)
            out += PlaybackStep(i, StepKind.JP, o.jp, JA, pauseAfterMs = 1200, speaker = "B")
        }
        bumpLastPause(1500)   // 응답3 뒤 = 생각할 틈(총 2.7초)
        out += PlaybackStep(i, StepKind.JP, "正解は${q.answer}番です。", JA, pauseAfterMs = 900)
        // 2단계: 복습(반복) — 딩동 신호 후, 화면에 질문·예문·해석을 순차로 노출.
        out += PlaybackStep(i, StepKind.CHIME, "", JA, pauseAfterMs = 500)
        out += PlaybackStep(i, StepKind.JP, q.promptJp, JA, pauseAfterMs = 1000, speaker = "A", showJp = q.promptJp, showKr = "")
        out += PlaybackStep(i, StepKind.KR, q.promptKr, KO, pauseAfterMs = 900, showKr = q.promptKr)
        q.options.forEachIndexed { k, o ->
            out += PlaybackStep(i, StepKind.JP, "${k + 1}", JA, pauseAfterMs = 200)
            out += PlaybackStep(i, StepKind.JP, o.jp, JA, pauseAfterMs = 1000, speaker = "B", showJp = "${k + 1}. ${o.jp}", showKr = "")
            val kr = if (q.answer == k + 1) "${o.kr} (정답)" else o.kr
            out += PlaybackStep(i, StepKind.KR, kr, KO, pauseAfterMs = 800, showKr = kr)
        }
        bumpLastPause(1800)   // 문항 간 간격
    }
    val last = (quiz.size - 1).coerceAtLeast(0)
    out += PlaybackStep(last, StepKind.JP, OUTRO_JP, JA, pauseAfterMs = 400)
    out += PlaybackStep(last, StepKind.KR, OUTRO_KR, KO)
    return out
}

/**
 * 실전 청해(LISTENING) 평탄화 — 문항마다 "시험(전부 일본어) → 정답 → 해설(한국어)".
 *
 * [問題N] 상황 → 본문대화(화자별) → 문제 → 선택지1~4 → (생각) → 딩동 → "正解はN番です"
 *        → (해설) 상황해석 → 본문 줄마다 일→한 → 문제해석 → 선택지 한국어(정답 표시)
 * 음성: 본문 화자=A/B, 그 외(상황·문제·선택지·정답)=내레이터(슬롯0).
 */
private fun Clip.listeningSteps(): List<PlaybackStep> {
    val JA = Locale.JAPANESE; val KO = Locale.KOREAN
    val out = ArrayList<PlaybackStep>()
    fun bump(extra: Long) { if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(pauseAfterMs = out.last().pauseAfterMs + extra) }
    listening.forEachIndexed { i, item ->
        // ── 1단계: 시험 (전부 일본어) ──
        out += PlaybackStep(i, StepKind.JP, "問題${i + 1}。", JA, pauseAfterMs = 2200, showJp = item.setupJp, showKr = "")
        out += PlaybackStep(i, StepKind.JP, item.setupJp, JA, pauseAfterMs = 1000, showJp = item.setupJp)
        out += PlaybackStep(i, StepKind.JP, item.questionJp, JA, pauseAfterMs = 2000, showJp = item.questionJp)   // 문제 먼저(뭘 들을지 알고 듣게 — 실전 방식)
        item.passage.forEach { line ->
            out += PlaybackStep(i, StepKind.JP, line.jp, JA, pauseAfterMs = 700, speaker = line.speaker, showJp = line.jp)
        }
        bump(800)   // 본문 끝 → 문제 다시 사이 여유(0.7→1.5초)
        out += PlaybackStep(i, StepKind.JP, item.questionJp, JA, pauseAfterMs = 1900, showJp = item.questionJp)   // 문제 다시
        item.options.forEachIndexed { k, o ->
            out += PlaybackStep(i, StepKind.JP, "${k + 1}", JA, pauseAfterMs = 250, showJp = "${k + 1}. ${o.jp}")
            out += PlaybackStep(i, StepKind.JP, o.jp, JA, pauseAfterMs = 900)
        }
        bump(2000)   // 생각할 틈
        out += PlaybackStep(i, StepKind.CHIME, "", JA, pauseAfterMs = 500)
        out += PlaybackStep(i, StepKind.JP, "正解は${item.answer}番です。", JA, pauseAfterMs = 2800, showJp = "正解: ${item.answer}番")
        // ── 2단계: 해설 — 1단계와 같은 구조(상황→문제→본문→문제→선택지), 각 항목 일본어→한국어 ──
        out += PlaybackStep(i, StepKind.JP, item.setupJp, JA, pauseAfterMs = 700, showJp = item.setupJp, showKr = "")
        out += PlaybackStep(i, StepKind.KR, item.setupKr, KO, pauseAfterMs = 900, showKr = item.setupKr)
        out += PlaybackStep(i, StepKind.JP, item.questionJp, JA, pauseAfterMs = 700, showJp = item.questionJp, showKr = "")
        out += PlaybackStep(i, StepKind.KR, item.questionKr, KO, pauseAfterMs = 1000, showKr = item.questionKr)   // 문제(앞)
        item.passage.forEach { line ->
            out += PlaybackStep(i, StepKind.JP, line.jp, JA, pauseAfterMs = 700, speaker = line.speaker, showJp = line.jp, showKr = "")
            out += PlaybackStep(i, StepKind.KR, line.kr, KO, pauseAfterMs = 700, speaker = line.speaker, showKr = line.kr)
        }
        out += PlaybackStep(i, StepKind.JP, item.questionJp, JA, pauseAfterMs = 700, showJp = item.questionJp, showKr = "")
        out += PlaybackStep(i, StepKind.KR, item.questionKr, KO, pauseAfterMs = 1000, showKr = item.questionKr)   // 문제(뒤)
        item.options.forEachIndexed { k, o ->
            val kr = if (item.answer == k + 1) "${o.kr} (정답)" else o.kr
            out += PlaybackStep(i, StepKind.JP, "${k + 1}", JA, pauseAfterMs = 200, showJp = "${k + 1}. ${o.jp}", showKr = "")
            out += PlaybackStep(i, StepKind.JP, o.jp, JA, pauseAfterMs = 700, showJp = "${k + 1}. ${o.jp}")
            out += PlaybackStep(i, StepKind.KR, kr, KO, pauseAfterMs = 600, showKr = "${k + 1}. $kr")
        }
        bump(1800)   // 문항 간 간격
    }
    val last = (listening.size - 1).coerceAtLeast(0)
    out += PlaybackStep(last, StepKind.JP, OUTRO_JP, JA, pauseAfterMs = 400)
    out += PlaybackStep(last, StepKind.KR, OUTRO_KR, KO)
    return out
}

/**
 * 일본 한입(CULTURE) 평탄화 — 로드맵 ⑩ "몰입식".
 *
 * 훅(한국어) → 나레이션(일본어 흐름, 문장별 kr은 자막만·음성 off, recall은 질문jp→틈→답kr)
 *   → 한국어 해설(summary) → 단어(일→한) → 문법(뜻kr + 예문 일→한) → 오늘의 표현(일→한)
 *   → 이어서 실전 대화 안내(핸드오프는 안내만; 자동 로드는 후속 과제).
 * 화면은 QUIZ/LISTENING처럼 showJp/showKr을 따라간다. 음성 배역은 내레이터(speaker="N"→슬롯0) 고정.
 */
private fun Clip.cultureSteps(): List<PlaybackStep> {
    val JA = Locale.JAPANESE; val KO = Locale.KOREAN
    val c = content ?: return emptyList()
    val out = ArrayList<PlaybackStep>()
    val lastIdx = (c.narration.size - 1).coerceAtLeast(0)

    // ── 훅(한국어) — 화면엔 일본어 훅(없으면 제목) + 한국어 훅 ──
    hook?.let { h ->
        out += PlaybackStep(0, StepKind.KR, h.kr, KO, pauseAfterMs = 900, speaker = "N",
            showJp = h.jp.ifEmpty { title }, showKr = h.kr)
    }

    // ── 나레이션(일본어 흐름). 문장별 kr은 자막만(음성 off). 리콜만 소리로. ──
    c.narration.forEachIndexed { i, n ->
        out += PlaybackStep(i, StepKind.JP, n.jp, JA, pauseAfterMs = 700, speaker = "N",
            showJp = n.jp, showKr = n.kr)
        n.recall?.let { r ->
            out += PlaybackStep(i, StepKind.JP, r.jp, JA, pauseAfterMs = 1200, speaker = "N",
                showJp = r.jp, showKr = "")            // 질문 → 1초 틈
            out += PlaybackStep(i, StepKind.KR, r.kr, KO, pauseAfterMs = 700, speaker = "N",
                showKr = r.kr)                          // 답
        }
    }

    // ── 한국어 해설(요약) ──
    if (c.summaryKr.isNotEmpty()) {
        out += PlaybackStep(lastIdx, StepKind.KR, c.summaryKr, KO, pauseAfterMs = 1000, speaker = "N",
            showJp = "정리", showKr = c.summaryKr)
    }

    // ── 단어(일→한) ──
    c.words.forEach { w ->
        out += PlaybackStep(lastIdx, StepKind.WORD_JP, w.jp, JA, pauseAfterMs = 200, speaker = "N",
            showJp = w.jp, showKr = w.kr)
        out += PlaybackStep(lastIdx, StepKind.WORD_KR, w.kr, KO, pauseAfterMs = 500, speaker = "N",
            showKr = w.kr)
    }

    // ── 문법(뜻kr + 예문 일→한) ──
    c.grammar.forEach { g ->
        out += PlaybackStep(lastIdx, StepKind.KR, "${g.title}。${g.meaning}", KO, pauseAfterMs = 600, speaker = "N",
            showJp = g.title, showKr = g.meaning)
        out += PlaybackStep(lastIdx, StepKind.JP, g.example.jp, JA, pauseAfterMs = 500, speaker = "N",
            showJp = g.example.jp, showKr = g.example.kr)
        out += PlaybackStep(lastIdx, StepKind.KR, g.example.kr, KO, pauseAfterMs = 800, speaker = "N",
            showKr = g.example.kr)
    }

    // ── 오늘 바로 써먹는 표현(일→한) ──
    c.phrase?.let { p ->
        out += PlaybackStep(lastIdx, StepKind.KR, PHRASE_INTRO_KR, KO, pauseAfterMs = 500, speaker = "N",
            showJp = "오늘 바로 써먹는 표현", showKr = PHRASE_INTRO_KR)
        out += PlaybackStep(lastIdx, StepKind.JP, p.jp, JA, pauseAfterMs = 700, speaker = "N",
            showJp = p.jp, showKr = p.kr)
        out += PlaybackStep(lastIdx, StepKind.KR, p.kr, KO, pauseAfterMs = 900, speaker = "N",
            showKr = p.kr)
    }

    // ── 이어서: 실전 대화 안내(안내만; 자동 로드는 후속) ──
    c.drill?.let { d ->
        val t = d.title.ifEmpty { "실전 대화" }
        out += PlaybackStep(lastIdx, StepKind.KR, "이어서 실전 대화, ‘$t’ 를 들어보세요.", KO, pauseAfterMs = 300, speaker = "N",
            showJp = "▶ ${d.level} 대화", showKr = t)
    }
    return out
}

private const val PHRASE_INTRO_KR = "오늘 바로 써먹는 표현이에요."
private const val OUTRO_JP = "聞き取りが終わりました。"
private const val OUTRO_KR = "듣기가 끝났습니다."
