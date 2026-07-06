# kiku 설계 제안서

> 대상: JLPT N4 청해 훈련 안드로이드 앱 (`com.bradlab.kiku`)
> 전제: [CLAUDE.md](./CLAUDE.md)의 컨셉/로드맵/비즈니스 모델을 따른다. 이 문서는 그 위에 얹는 **기술 설계**다.

---

## 0. 한 줄 요약

이 앱은 **화면을 보지 않고 듣기만 하는 앱**이다(라디오/팟캐스트처럼). 화면은 추후 구성. 따라서 진짜 엔지니어링 포인트는 둘이다:

1. **TTS 재생 시퀀서** — `일본어 3회 → 한국어 해석 → 일본어 단어 → 한국어 뜻 → 다음 문장` 흐름(이게 1단위)이 전부 비동기라서, 여기만 제대로 설계하면 연속 재생·문장 이동·다시듣기·속도·쉐도잉이 전부 파생으로 떨어진다.
2. **백그라운드 재생** — 화면이 꺼져도, 앱이 백그라운드여도 소리가 계속 나야 한다. 시퀀서를 **Foreground Service(MediaSession)** 안에서 돌린다. 이게 빠지면 앱의 존재 이유가 사라진다.

> 한 클립이 100문장(=100단위)이어도 **전체를 끊김 없이 연속 재생**하고, 그 와중에 사용자는 **문장 단위로 자유롭게 앞/뒤 이동**할 수 있어야 한다. 아래 평탄화 구조가 이 둘을 동시에 공짜로 해결한다.
>
> 듣기 전용이므로 **한국어도 음성으로 읽어준다**(해석·단어 뜻). 일본어+한국어 두 TTS 음성이 모두 필요하다(§7).

---

## 1. 현재 상태 진단

| 항목 | 상태 |
|---|---|
| 프로젝트 | `com.bradlab.kiku`, Jetpack Compose, Compose BOM |
| SDK | minSdk 26, compileSdk 36, targetSdk 36 |
| 코드 | `MainActivity` Hello World만 존재 |
| 결론 | **환경 셋업(1주차) 사실상 완료.** 바로 데이터·재생 단계로 진입 가능 |

CLAUDE.md 1주차의 "TTS로 한 문장 읽어보기(가능성 검증)"만 코드로 찍으면 1주차는 끝이다.

---

## 2. 핵심 설계 — 재생 시퀀서

### 2.1 왜 어려운가

문장 1단위 흐름:

```
일본어 음성(3회) → (3초 정지) → 한국어 해석 → 일본어 단어 → 한국어 뜻 → … → 다음 문장
                                            └── 단어마다 일→한 한 쌍 ──┘
```

`TextToSpeech.speak()`는 **비동기 큐**에 넣고 즉시 리턴한다. 그래서:

- `Thread.sleep(3000)`으로 정지를 구현하면 → UI 스레드가 멈추고, 일시정지·건너뛰기 버튼이 동작하지 않는다.
- `onDone` 콜백 안에서 다음 `speak()`를 호출하는 **콜백 체이닝**은 → 흐름이 콜백 지옥으로 흩어지고, 중간 점프(다시듣기/건너뛰기) 제어가 지저분해진다.

### 2.2 채택 방식 — 코루틴 시퀀서

`speak()`를 `suspend` 함수로 감싸 `onDone`에서 resume한다. 그러면 재생 로직이 **그냥 순차 코드**가 된다.

- **일시정지** = 코루틴 job `cancel()` + `tts.stop()`
- **건너뛰기 / 다시듣기** = 스텝 인덱스 점프
- **정지(3초)** = `delay()`
- **속도 조절** = 다음 스텝 직전에 `setSpeechRate()` 재적용

### 2.3 데이터 → 재생 스텝 평탄화

문장을 그대로 다루지 않고, **JP/KR/WORD를 동일한 `PlaybackStep`으로 평탄화**한다. 이게 모든 변형 기능을 단순화하는 핵심 아이디어다.

```kotlin
// JP: 일본어 문장 / KR: 한국어 해석 / WORD_JP: 일본어 단어 / WORD_KR: 단어 한국어 뜻
enum class StepKind { JP, KR, WORD_JP, WORD_KR }

data class PlaybackStep(
    val sentenceIndex: Int,   // 어느 문장에 속하는지 (점프/하이라이트용)
    val kind: StepKind,
    val text: String,
    val locale: Locale,
    val pauseAfterMs: Long = 0,
)

// 한 문장 → 스텝 펼침. 펼치는 방식은 PlaybackPattern(§2.7)이 결정한다.
// DRILL 흐름: JP×3 → (해석) → KR → JP×1(뜻 알고 다시) → 단어(일→한)
fun Sentence.toSteps(index: Int, p: PlaybackPattern): List<PlaybackStep> = buildList {
    repeat(maxOf(0, p.jpRepeat - 1)) {                                     // 회차 사이 정지
        add(PlaybackStep(index, StepKind.JP, jp, Locale.JAPANESE, pauseAfterMs = p.pauseBetweenRepeatsMs))
    }
    if (p.jpRepeat >= 1) add(PlaybackStep(index, StepKind.JP, jp, Locale.JAPANESE, pauseAfterMs = p.pauseAfterJpMs)) // 마지막 회차 뒤 해석 시간
    if (p.readKr) add(PlaybackStep(index, StepKind.KR, kr, Locale.KOREAN, pauseAfterMs = p.pauseAfterKrMs))
    repeat(maxOf(0, p.jpRepeatAfterKr)) {                                  // 뜻 들은 뒤 다시 듣는 일본어
        add(PlaybackStep(index, StepKind.JP, jp, Locale.JAPANESE, pauseAfterMs = p.pauseBetweenRepeatsMs))
    }
    if (p.readWords) words.forEach { w ->                                  // 단어마다 일→한 한 쌍
        add(PlaybackStep(index, StepKind.WORD_JP, w.jp, Locale.JAPANESE))
        add(PlaybackStep(index, StepKind.WORD_KR, w.kr, Locale.KOREAN))
    }
}

// 클립 전체 → 모든 문장의 스텝을 하나로 펼침 → 100문장도 끊김 없이 연속 재생.
// 문장 사이 정지(pauseBetweenSentencesMs)는 각 문장 마지막 스텝에 얹고,
// 맨 끝에 클립 종료 안내(일본어 → 한국어 "듣기가 끝났습니다")를 덧붙인다.
fun Clip.toSteps(): List<PlaybackStep> =
    sentences.flatMapIndexed { i, s -> s.toSteps(i, s.patternOverride ?: effectivePattern) } + outroSteps()
```

> 클립의 모든 문장을 **하나의 평탄한 `steps` 리스트**로 펼치는 게 핵심. 시퀀서 루프는 이 리스트를 처음부터 끝까지 순회하므로 **연속 재생은 기본 동작**이고, **문장 이동은 인덱스 점프**(§2.5 표)로 환원된다.
> 한 문장을 *어떻게* 펼치냐(반복 횟수·한국어 읽기·단어 읽기·정지)는 전부 `PlaybackPattern`(§2.7)이 쥐고 있어, **콘텐츠 타입(문장 드릴/대화/청해)이 달라져도 시퀀서·백그라운드 재생은 한 줄도 안 바뀐다.**

### 2.4 speak를 suspend로

```kotlin
suspend fun TextToSpeech.speakAndAwait(step: PlaybackStep) =
    suspendCancellableCoroutine { cont ->
        val id = step.hashCode().toString()
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(u: String?) {}
            override fun onDone(u: String?)  { if (cont.isActive) cont.resume(Unit) }
            override fun onError(u: String?) { if (cont.isActive) cont.resume(Unit) }
        })
        language = step.locale
        speak(step.text, TextToSpeech.QUEUE_FLUSH, null, id)
        cont.invokeOnCancellation { stop() }
    }
```

### 2.5 시퀀서 루프

```kotlin
private fun play() = viewModelScope.launch {
    var i = currentStepIndex
    while (i < steps.size) {
        val step = steps[i]
        _state.update { it.copy(playingSentence = step.sentenceIndex, kind = step.kind) }
        tts.setSpeechRate(speed)            // 매 스텝 속도 반영
        tts.speakAndAwait(step)             // onDone까지 대기
        if (step.pauseAfterMs > 0) delay(step.pauseAfterMs)
        i++; currentStepIndex = i
    }
}
```

| 동작 | 구현 |
|---|---|
| **연속 재생(기본)** | 루프가 `steps`(클립 전체 평탄화) 끝까지 진행 → 100문장이 끊김 없이 이어짐 |
| 일시정지 | `playJob.cancel()` + `tts.stop()` |
| 다음 문장 ▶ | `currentStepIndex = 다음 sentenceIndex의 첫 스텝` (재생 중이면 그 지점부터 계속 이어감) |
| 다시듣기 | `currentStepIndex = 현재 sentenceIndex의 첫 스텝` |
| 이전 문장 ◀ | `currentStepIndex = 이전 sentenceIndex의 첫 스텝` |
| 속도 조절 | `speed` 값만 변경 → 다음 스텝부터 반영 |
| **쉐도잉 모드** | `pauseAfterMs`를 문장 길이만큼 키운 변형일 뿐 — 별도 엔진 불필요 |

> **포인트:** 연속 재생·문장 이동·다시듣기·속도·쉐도잉이 전부 "인덱스 점프 + 파라미터 변경"으로 환원된다. 재생 엔진을 새로 만들 일이 없다.
>
> **연속 재생 ↔ 문장 이동의 관계:** 둘은 별개 모드가 아니다. 재생은 항상 연속으로 흐르고, 사용자의 ◀/▶ 입력은 그저 **흐르는 위치(`currentStepIndex`)를 다른 문장 첫 스텝으로 옮기는 점프**일 뿐이다. 점프 후에도 루프는 멈추지 않고 거기서부터 계속 이어 재생한다. 구현 시 점프는 `currentStepIndex`만 갱신하고 진행 중인 스텝을 끊어주면(`tts.stop()` → 루프가 다음 반복에서 새 인덱스를 읽음) 된다.

### 2.6 백그라운드 재생 (화면 꺼져도 소리)

이 앱은 듣기 전용이라 **화면을 끄거나 다른 앱으로 나가도 재생이 멈추면 안 된다.** 그러려면 시퀀서가 Activity 수명에 묶이면 안 되고, **Foreground Service**가 소유해야 한다.

```
PlaybackService (Foreground, type=mediaPlayback)
 ├── TextToSpeech 인스턴스
 ├── TtsSequencer (코루틴 루프 + currentStepIndex + state)
 ├── MediaSession        // 알림/잠금화면/이어폰 버튼 → 재생·일시정지·이전/다음 문장
 └── AudioFocus 관리     // 전화/다른 앱 소리 오면 일시정지, 끝나면 복귀
```

| 요구 | 구현 |
|---|---|
| 화면 꺼짐/백그라운드에서 재생 유지 | `startForeground()` + 미디어 알림. 프로세스가 안 죽음 |
| `targetSdk 36` 권한 | `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, manifest `foregroundServiceType="mediaPlayback"` |
| 알림/잠금화면 컨트롤, 이어폰 버튼 | `MediaSession` + `MediaStyle` 알림 → 재생/정지, **이전/다음(=문장 이동)** 매핑 |
| 전화 오거나 다른 앱이 소리 냄 | `AudioManager` 포커스 요청 — `LOSS_TRANSIENT` 시 일시정지, 회복 시 재개 |
| 이어폰 뺐을 때 | `AudioManager.ACTION_AUDIO_BECOMING_NOISY` → 일시정지(소리 새지 않게) |

> **검증 순서 주의:** §9의 "반나절 검증"에서 일본어 한 문장 재생을 확인한 직후, **두 번째로 백그라운드 재생부터** 찔러보는 게 좋다. TTS가 Foreground Service 안에서 화면 꺼진 채 정상 동작하는지가 이 앱의 진짜 기술 리스크이기 때문이다(일부 기기/엔진에서 백그라운드 TTS 제약이 있을 수 있음).

### 2.7 콘텐츠 타입 — 문장 드릴 / 대화 / 청해

콘텐츠는 한 종류가 아니다. 관리자(=나)가 클립마다 형태를 고를 수 있어야 한다.

| 타입(`ClipMode`) | 화자 | 한 문장 펼침(기본 패턴) | 성격 |
|---|---|---|---|
| **DRILL** 문장 드릴 (현재) | 없음 | JP×3 → 3초 → KR → JP×1 → 단어(일↔한) | 한 문장 깊게 반복 |
| **DIALOGUE** 대화(한 문장씩) | A/B 등 | JP×2 → KR → 짧은 정지, 단어 생략 | 턴 주고받으며 학습 |
| **LISTENING** 청해(멀티턴) | 여럿 | JP×1, KR·단어 생략, 자연스럽게 쭉 | 실전처럼 흐름 듣기 |

> **정지(pause) 값(DRILL 실측 확정, 2026-06-26):** JP 회차 사이 1.5초 · JP 3회 뒤(해석) 3초 · KR 뒤 0.8초 · 문장 사이 2초. 클립 끝엔 종료 안내(일→한). 값은 전부 `PlaybackPattern`이라 추후 사용자 설정/문장별 오버라이드 가능.

**핵심:** 이 셋은 **새 엔진이 아니라 재생 패턴 프리셋**일 뿐이다. 평탄화·시퀀서·백그라운드는 그대로고, 달라지는 건 ① 문장에 화자가 붙느냐, ② 한 문장을 어떻게 펼치냐(`PlaybackPattern`)뿐이다.

> **구현 상태(2026-07-06):** DRILL·DIALOGUE·N3 완료. **LISTENING(실전 청해 멀티턴)만 예정**(TODO 10).
> - **DIALOGUE**: (A질문+B답변)을 **한 세트로** 평탄화(`Clip.dialogueSteps()`) — `[A일,B일]×3 → [A한,B한] → [A일,B일] → 단어`. 콘텐츠는 `speaker`("A"/"B") 지정 + `mode: DIALOGUE`. 목록 아트에 2인 배지.
> - **화자 목소리**: 성별 다른 목소리 2개를 골라 화자에 매핑. **짝마다 남/여 순서 무작위**(`pairVoiceSwap`). **일본어 문장 + 한국어 해석은 같은 슬롯(성별 일치)**, **단어만 고정 목소리(나레이터)**. voice 성별은 API로 못 얻어 구글 온디바이스 남성 코드(ko: koc/kod, ja: jac/jad)로 `[여,남]` 정렬(§7.3). 기기 편차 있으면 mp3(§10.3).

> **화자→voice 매핑(DIALOGUE):** 문장의 `speaker`에 따라 다른 TTS voice로 읽어 화자를 구분한다. 내장 TTS에 남·여 일본어 voice가 있어 성별 기준 구분이 가능함을 실기기로 확인(§7.3). 특정 voice 이름 하드코딩은 기기 편차로 깨지므로, **성별/역할로 후보 voice를 조회해 매핑하고 없으면 기본 voice로 폴백.** 구체 구현은 TtsSequencer(TODO 2)에서.

```kotlin
enum class ClipMode { DRILL, DIALOGUE, LISTENING }  // 프리셋 → PlaybackPattern으로 변환

@Serializable
data class PlaybackPattern(            // ← "마음대로 설정"의 실체
    val jpRepeat: Int = 3,
    val pauseBetweenRepeatsMs: Long = 1500,  // JP 회차 사이(곱씹을 시간)
    val pauseAfterJpMs: Long = 3000,         // 마지막 JP 회차 뒤(해석할 시간)
    val readKr: Boolean = true,
    val pauseAfterKrMs: Long = 800,          // 한국어 뒤
    val jpRepeatAfterKr: Int = 1,            // 뜻 들은 뒤 다시 듣는 일본어 횟수
    val readWords: Boolean = true,
    val pauseBetweenSentencesMs: Long = 0,   // 문장(챕터) 사이
)

// 프리셋 → 기본 패턴
fun ClipMode.toPattern(): PlaybackPattern = when (this) {
    ClipMode.DRILL     -> PlaybackPattern(jpRepeat = 3, pauseAfterJpMs = 3000, jpRepeatAfterKr = 1, pauseBetweenSentencesMs = 2000, readKr = true,  readWords = true)
    ClipMode.DIALOGUE  -> PlaybackPattern(jpRepeat = 2, pauseAfterJpMs = 1500, jpRepeatAfterKr = 0, pauseBetweenSentencesMs = 1000, readKr = true,  readWords = false)
    ClipMode.LISTENING -> PlaybackPattern(jpRepeat = 1, pauseAfterJpMs = 0,    jpRepeatAfterKr = 0, pauseBetweenSentencesMs = 800,  readKr = false, readWords = false)
}

// 클립이 실제로 쓰는 패턴: 커스텀 pattern이 있으면 그것, 없으면 mode 프리셋
val Clip.effectivePattern: PlaybackPattern get() = pattern ?: mode.toPattern()
```

**자유도(결정):** 프리셋 3개 + 미세조정. 평소엔 `mode` 하나만 고르고, 비틀고 싶을 때만 클립의 `pattern`(전체) 또는 문장의 `patternOverride`(그 문장만)로 덮어쓴다. 위 `Clip.toSteps`(§2.3)가 이 우선순위(`patternOverride` → `pattern` → `mode`)를 적용한다.

**관리 방식(결정):** **JSON 직접 작성**(손 또는 GPT). 인앱 관리자 화면은 MVP 범위 밖 → 추후(§10).

```
com.bradlab.kiku
├── data
│   ├── Sentence.kt / Clip.kt / Word.kt   // @Serializable 모델
│   └── ClipRepository.kt                  // interface — MVP는 AssetClipRepository (추후 Remote로 교체)
├── player
│   ├── PlaybackStep.kt
│   ├── TtsSequencer.kt                    // TextToSpeech 래퍼 + 시퀀서 루프(상태 보유)
│   ├── PlaybackService.kt                 // ★ Foreground Service + MediaSession (백그라운드 재생)
│   └── PlayerViewModel.kt                 // 서비스에 바인딩 → StateFlow<PlayerUiState> 중계
└── ui
    ├── MainActivity.kt                    // installSplashScreen() + NavHost
    ├── nav/KikuNavHost.kt                 // cliplist ↔ player/{clipId}
    ├── cliplist/ClipListScreen.kt
    ├── player/PlayerScreen.kt             // 골격 먼저, 풍부한 UI는 소리 다음
    └── theme/ (기존)
```

> 스플래시는 별도 화면 파일이 아니라 `themes.xml` + `installSplashScreen()`(공식 API)으로 처리한다(§5.0).

> 핵심: **재생 상태와 시퀀서 루프는 UI가 아니라 `PlaybackService`가 소유**한다. Activity/화면이 죽어도 서비스가 살아 소리를 계속 낸다. UI는 서비스에 붙어 상태를 구경하고 명령만 보낸다(없어도 재생은 돈다).

### 설계 결정

| 항목 | 선택 | 이유 |
|---|---|---|
| 데이터 저장 (MVP) | **A. 번들** `assets/clips/*.json` + `kotlinx.serialization` | 서버·DB 없음. 검증 단계엔 재빌드=재배포라 부담 0 |
| 데이터 로더 | `ClipRepository` **interface**(`AssetClipRepository` 구현) | 나중에 원격(§10)으로 갈 때 구현체만 교체 — 호출부 안 바뀜 |
| **백그라운드 재생** | **Foreground Service(type `mediaPlayback`) + MediaSession** | 화면 꺼져도 소리 유지. 알림/잠금화면/이어폰 버튼 컨트롤은 덤 |
| 시퀀서 위치 | **Service 내부**(viewModelScope 아님) | 화면·Activity 수명과 분리해야 백그라운드 생존 |
| ★ 저장 / 진행도 | **DataStore** (문장 id Set), **로컬 익명 저장** | Room은 과함. 계정 전제로 짜지 않는다 → 나중에 계정 붙일 때 "로컬 익명 데이터를 계정으로 병합"만 하면 됨(§10.4). 검증 단계엔 키-값이면 충분 |
| DI | 없음 (직접 생성) | Hilt 도입 가치 없음. 서버 붙일 때 재검토 |
| 상태 관리 | `StateFlow<PlayerUiState>` (서비스 소유) | 표준 단방향 흐름, UI는 구독만 |

---

## 4. 데이터 모델

CLAUDE.md의 JSON 구조를 그대로 따른다.

듣기 전용이라 단어도 음성으로 읽어주므로, `words`는 **일본어+한국어 뜻 쌍**이어야 한다.

```kotlin
@Serializable
data class Clip(
    val id: Int,
    val level: String = "N4",                   // 난이도 "N5"/"N4"/"N3" — 목록 배지·필터용 (추후, TODO 11)
    val category: String,                       // "회사생활", "여행" ...
    val title: String,
    val mode: ClipMode = ClipMode.DRILL,        // 콘텐츠 타입 프리셋 (§2.7)
    val pattern: PlaybackPattern? = null,       // 클립 전체 커스텀 (있으면 mode 덮어씀)
    val sentences: List<Sentence>,
)

@Serializable
data class Sentence(
    val id: Int,
    val speaker: String? = null,                // 대화·청해의 화자("A"/"B"/"점원"…). null=일반 문장
    val jp: String,
    val kr: String,
    val words: List<Word> = emptyList(),
    val patternOverride: PlaybackPattern? = null, // 이 문장만 다르게 (최우선)
)

@Serializable
data class Word(
    val jp: String,   // 会社
    val kr: String,   // 회사
)
```

> `mode`/`pattern`/`patternOverride`/`speaker`는 전부 기본값이 있어, **MVP 첫 데이터(문장 드릴)는 예전처럼 `mode`도 생략하고 문장만 적으면 된다.** 대화/청해를 만들 때만 `mode`와 `speaker`를 채운다.

```json
// assets/clips/n4_office.json  — 문장 드릴(mode 생략 = DRILL)
{
  "id": 1,
  "category": "N4 회사생활",
  "title": "회의·전화·이메일",
  "sentences": [
    { "id": 1, "jp": "昨日は会社を休みました。", "kr": "어제는 회사를 쉬었습니다.",
      "words": [
        { "jp": "昨日", "kr": "어제" },
        { "jp": "会社", "kr": "회사" },
        { "jp": "休む", "kr": "쉬다" }
      ] }
  ]
}
```

```json
// assets/clips/n4_shop_dialogue.json  — 대화(화자 A/B, 한 문장씩)
{
  "id": 2,
  "category": "N4 생활회화",
  "title": "편의점에서",
  "mode": "DIALOGUE",
  "sentences": [
    { "id": 1, "speaker": "店員", "jp": "いらっしゃいませ。", "kr": "어서 오세요." },
    { "id": 2, "speaker": "客",   "jp": "これ、ください。",   "kr": "이거 주세요." }
  ]
}
```

> 청해(`"mode": "LISTENING"`)는 동일 구조에 문장 수만 많고 화자가 여럿인 형태. 별도 필드 없음.

---

## 5. 화면 설계

듣기 전용이라 화면은 **최소 셸**만 둔다(소리 파이프라인이 먼저, 화면은 그 위에 얇게). 셸은 세 개: **스플래시 → 클립 리스트 → 플레이어**.

### 5.0 화면 흐름 & 스플래시

```
콜드스타트
  → SplashScreen (키쿠 아이콘)        ← OS가 그림. 별도 Composable/Activity 아님
  → MainActivity (단일 Activity)
       → ClipListScreen (시작 지점)
            └─(클립 탭)→ PlayerScreen(clipId)
```

| 항목 | 선택 | 이유 |
|---|---|---|
| **스플래시** | AndroidX **`core-splashscreen`**(공식 SplashScreen API) | minSdk 26 OK(lib 23+). 콜드스타트에 키쿠 아이콘+배경색을 OS가 자연스럽게 표시 → **이중 스플래시·깜빡임 없음**, 인위적 `delay` 불필요 |
| 네비게이션 | Navigation-Compose, 목적지 2개(`cliplist`, `player/{clipId}`) | 라우팅 복잡도 거의 없음. 단일 Activity |
| 스플래시 표시 시간 | 앱 초기화 동안만(기본) | "키쿠 한 번 보여주고 곧장 리스트". 길게 잡지 않음(원하면 짧게 연장 가능) |

> 스플래시는 `themes.xml`의 `windowSplashScreenAnimatedIcon`(키쿠 아이콘) + `windowSplashScreenBackground`(브랜드 배경색)로 설정하고, `MainActivity.onCreate`에서 `installSplashScreen()` 호출. 클립 리스트가 그릴 준비가 되면 자동으로 사라진다.

#### 브랜드 에셋 / 팔레트 (확정)

심볼은 **聞**(きく, "듣다") 흰색 + 골드 **KIKU** 워드마크, 어두운 배경.

| 용도 | 값 |
|---|---|
| 배경 `windowSplashScreenBackground` / adaptive icon 배경 | `#0F1115` |
| 액센트 골드 (KIKU, 재생 하이라이트 등 UI 포인트) | `#F2C14E` |
| 심볼 흰색 | `#FFFFFF` |

- 원본: `art/master/01-original/foreground-1024.png` (1024², 투명 배경, 흰 聞 + 골드 KIKU) → adaptive icon **전경** 레이어
- adaptive icon **배경** = 단색 `#0F1115` (이미지 불필요)
- `preview-circle.png` / `preview-rounded.png` = 마스크 검수용(빌드 미사용). 안전영역 내 — 잘림 없음 확인
- 적용: Android Studio `New > Image Asset`(Adaptive Icon)로 전경=PNG, 배경=색상 지정 → mipmap 자동 생성. 스플래시 아이콘도 동일 전경 사용.

### 5.1 ClipListScreen
- 카테고리별(N4 회사생활 / 여행 / 생활회화) 클립 카드 목록
- 카드: 제목 + 문장 수 + 타입 배지(드릴/대화/청해) + (선택) 진행도

### 5.2 PlayerScreen
```
┌──────────────────────────────┐
│  N4 회사생활 — 회의·전화·이메일   │
│                              │
│   昨日は会社を休みました。        │  ← 현재 문장 크게, 재생 중 하이라이트
│   (어제는 회사를 쉬었습니다.)     │  ← 한국어 토글
│   [昨日] [会社] [休む]          │  ← 단어 칩
│                              │
│   ▬▬▬▬▬▬▬▬░░░░  3/10          │  ← 진행 바
│                              │
│   ◀ 이전   ↻ 다시듣기   다음 ▶   │
│        ▶/⏸      ★ 저장        │
│   속도: 0.7 0.8 [1.0] 1.2     │
└──────────────────────────────┘
```

- `playingSentence` 인덱스로 현재 줄 하이라이트 → 유튜브 대비 **시각 동기화**가 차별점
- 한국어/단어는 토글 (보기 전에 스스로 해석할 시간 확보)

> **우선순위:** 셸(스플래시→리스트→플레이어 골격)은 MVP에 넣되, 플레이어의 **풍부한 UI(줄 하이라이트·토글·진행 바)는 소리 파이프라인이 동작한 뒤** 얹는다. 듣기 전용이라 1차 조작 수단은 여전히 **미디어 알림/잠금화면/이어폰 버튼**(재생·정지·이전/다음 문장)이고, 화면은 그 위의 보조 수단이다.

---

## 6. 조정된 로드맵 (셋업 완료 기준)

> 듣기 전용이라 **화면은 뒤로 미룬다.** 소리(시퀀서 + 백그라운드 재생)부터 끝낸다.

| 단계 | 할 일 | 검증 목표 |
|---|---|---|
| **지금 (반나절)** | ① 버튼 1개로 일본어 한 문장 재생 → ② **화면 끈 채 백그라운드 재생** 되는지 | 일·한 음성 있나 / 백그라운드 TTS 기술 가능성 |
| **1주** | `TtsSequencer` + `PlaybackService`(Foreground/MediaSession) — 한 클립 풀 흐름(연속 재생/정지/문장 이동/다시듣기), 화면 없이 알림 컨트롤로 조작 | 화면 꺼도 핵심 UX 동작 |
| **2주** | GPT로 N4 100문장(일·한·단어 일↔한) → JSON 3카테고리, ★ 저장 / 속도 조절 | **내가 매일 쓰나** |
| **3주** | (필요 최소)화면 구성 + 다듬기 + AdMob + 블로그 홍보 | 남도 쓰나 |

---

## 7. 지금 결정해야 할 갈림길

### 7.1 한국어 해석 — 읽어준다 (결정됨)
- **듣기 전용 앱**이므로 화면을 안 본다 → 한국어 해석·단어 뜻을 **음성으로 읽어줘야** 한다(표시만 옵션 폐기).
- 따라서 **일본어 + 한국어 두 TTS 음성이 모두 필수**. 매 문장 일↔한 언어 전환 레이턴시는 감수.
- 언어 전환 레이턴시가 거슬리면: ① 같은 언어 스텝을 연속 배치(이미 그렇게 평탄화됨), ② 추후 ElevenLabs mp3로 교체(§8) 시 자연 해소.

### 7.2 TTS 음성 미설치 기기 대응 (필수)
- 일본어 **또는 한국어** 음성 데이터 없는 기기가 꽤 있음(특히 일본어).
- 첫 실행 시 `isLanguageAvailable(Locale.JAPANESE)`, `isLanguageAvailable(Locale.KOREAN)` **둘 다** 체크 → 없으면 음성 설치 인텐트(`ACTION_INSTALL_TTS_DATA`) 안내.
- **빠지면 "소리 안 나요" 리뷰 폭탄.** MVP에 반드시 포함.

### 7.3 목소리 / 피치 — 내장 TTS로 대화 화자 구분 가능 (검증됨, 2026-06-26)

갤럭시 S24+ 실기기에서 일본어 목소리를 목소리별로 재생해 귀로 비교한 결과(TODO 1):

- **피치 조절은 폐기.** `0.8`/`1.2`는 부자연스럽게 들림 → **피치 1.0 고정.** 피치로 성별/배역을 흉내내지 않는다.
- **일본어 남·여 voice가 둘 다 존재.** → **대화(DIALOGUE) 모드의 화자 구분을 내장 TTS만으로 커버 가능.** (예: A=남성 voice, B=여성 voice)
- 따라서 **ElevenLabs mp3(§10.3)는 MVP 범위 밖.** 품질/자연스러움 불만이 실제로 생기면 그때 검토.

**결정:** 화자→voice 매핑을 둔다(§2.7). 배역이 성별로 갈리면 남/여 voice, 동성 배역이 늘면 음색 다른 동성 voice를 배정. 매핑의 구체 위치·기본값은 `TtsSequencer` 구현(TODO 2) 때 확정.

> **주의(기기 편차):** voice 구성은 **기기·TTS 엔진마다 다르다.** 특정 남성 voice 이름을 하드코딩하면 다른 기기에서 깨진다 → **성별/역할 기준으로 후보 voice를 조회해 매핑**하고, 없으면 기본 voice로 폴백한다.

---

## 8. 리스크 / 메모

| 리스크 | 대응 |
|---|---|
| **백그라운드 TTS 제약** (이 앱 최대 리스크) | 일부 기기/엔진에서 화면 꺼진 채 TTS가 멎을 수 있음 → §2.6 Foreground Service. **검증 2순위로 즉시 확인**(§9) |
| TTS 음성 품질·발음이 기기마다 다름 | CLAUDE.md 방식 3(ElevenLabs mp3)으로 후기 교체 가능. MVP는 검증용 |
| 일본어/한국어 음성 미설치 | 7.2 대응(둘 다 체크) |
| 일↔한 매 문장 언어 전환 레이턴시 | 같은 언어 스텝 연속 배치로 완화, 추후 mp3 교체로 해소 |
| onDone이 일부 엔진에서 누락 | `onError`도 resume 처리(2.4에 반영), 타임아웃 가드 고려 |

---

## 9. 다음 액션

1. **반나절 검증 코드 (2단계)**:
   - ① 버튼 → `speakAndAwait`로 일본어 한 문장. 일·한 음성 존재/품질 확인.
   - ② **Foreground Service에서 화면 끈 채 재생** 찔러보기. 이 앱 최대 리스크(백그라운드 TTS)를 가장 먼저 잠재운다.
2. 통과하면 → `TtsSequencer` + `PlaybackService`(MediaSession) 골격. 화면 없이 알림 컨트롤로 연속 재생/문장 이동 동작 확인.
3. 병행: GPT로 N4 첫 클립(10~20문장, 단어 일↔한 포함) JSON 생성해 실데이터로 테스트.

---

## 10. 추후 진화 방향 (MVP 이후)

MVP는 의도적으로 단순하게 간다(번들 JSON, 화면 후순위). 검증 후 다음 순서로 확장한다.

### 10.1 콘텐츠 배포 — 번들 → 원격 (가장 중요한 확장)

MVP는 **A. 번들**이라 콘텐츠를 추가하려면 앱을 새로 빌드해야 한다. 본인 검증 단계(혼자 재빌드)엔 부담 없지만, **출시 후 남들에게 콘텐츠를 자주 내보내려면 매번 스토어 심사를 거쳐야 해 치명적**이다. 그래서:

| 단계 | 방식 | 콘텐츠 추가 시 | 비고 |
|---|---|---|---|
| MVP | **A. 번들** (`assets`) | 앱 재빌드/재배포 | 검증엔 충분 |
| 출시 직전~후 | **B. 원격 JSON** | **JSON 파일만 교체** | 앱 그대로 |
| 안정화 | **C. 하이브리드** | 평소 원격, 첫 실행/오프라인은 번들 캐시 | 가장 견고 |

- **B의 "원격"은 서버가 아니다.** 정적 파일 호스트 한 곳(GitHub raw / Firebase Hosting / Cloudflare R2 / S3)에 JSON 올리고 앱이 URL을 `fetch`하는 수준 → 운영비 ≈ 0, 백엔드 코드 없음. CLAUDE.md 안티패턴(회원가입/결제/실시간 성우/서버 구축)에 안 걸림.
- **준비:** MVP에서 `ClipRepository`를 **interface**로 둔다(§3). B로 갈 때 `RemoteClipRepository` 구현체만 추가하고 호출부는 그대로. 마이그레이션 비용을 지금 0으로 묶어두는 게 핵심.

### 10.2 인앱 관리자 화면

지금은 콘텐츠를 JSON 직접 작성으로 관리한다(§2.7). 콘텐츠가 쌓이면 앱 안에서 클립/문장/패턴을 편집하는 관리자 UI를 검토. 10.1의 원격 배포와 묶여야 의미 있음(편집 → 원격 반영).

### 10.3 음성 품질 — 내장 TTS → mp3

기기별 TTS 품질 편차가 크면 CLAUDE.md 방식 3(ElevenLabs mp3 사전 생성 → 원격 스토리지)로 교체. 일↔한 언어 전환 레이턴시도 자연 해소. `PlaybackStep`에 `audioUrl`을 더해 TTS/mp3 재생을 분기하면 시퀀서는 그대로 재사용.

### 10.4 계정(로그인) — 나중에 얹되 "뺏지 않는다"

MVP엔 회원가입을 넣지 않는다(CLAUDE.md 안티패턴). 계정은 검증 후 **선택적으로 얹는다.** 핵심은 **"나중에 추가"라는 타이밍 자체는 반발 요인이 아니라는 것** — 사용자는 계정이 처음부터 있었는지 나중에 생겼는지 신경 쓰지 않는다. 반발은 오직 두 경우에만 생긴다:

1. **기존에 되던 걸 계정 없이는 못 하게 막을 때** → 막지 않는다. **듣기(핵심 기능)는 영원히 로그인 불필요.**
2. **계정 도입 시 로컬 데이터(★저장·진행도)가 날아갈 때** → 아래 원칙으로 원천 차단.

**대비 원칙 (지금 지킬 것):**

> ★저장·진행도를 **로컬 익명**으로 저장하고(§3), 나중에 계정을 붙일 때 **로컬 익명 데이터를 그 계정으로 병합**하는 경로만 열어둔다(게스트 → 계정 마이그레이션).

- 로그인 안 하는 사용자 → 하던 대로 계속 됨.
- 로그인하는 사용자 → 지금까지 모은 게 그대로 계정에 올라감(안 잃음). 사용자 눈엔 "백업 켜기 버튼이 생겼네" 정도 → 뺏긴 게 없으니 반발 없음.

**계정이 실제로 필요해지는 시점:** ★저장·진행도의 **기기 간 동기화·백업**(10.1 원격과 묶임), 또는 **유료(광고 제거) 결제**. 결제는 어차피 계정이 필요하므로 자연스럽게 로그인이 따라온다.

**수익/로그인 방침(결정):** 광고는 **로그인 없이 전원**에게 노출. 광고 제거는 **무료 로그인이 아니라 유료(구독/1회 결제)**의 혜택으로 둔다. "무료 로그인 → 광고 제거"는 유일한 초기 수익(광고)을 공짜로 버리는 데다, MVP 단계 계정은 모아도 쓸 데가 없어 비추. 계정에 **진짜 쓸모(동기화·결제)**가 생길 때 얹는다.
