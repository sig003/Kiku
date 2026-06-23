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

// 한 문장(1단위) → [JP, JP, JP(+정지), KR, WORD_JP, WORD_KR, WORD_JP, WORD_KR, ...]
fun Sentence.toSteps(index: Int): List<PlaybackStep> = buildList {
    repeat(JP_REPEAT - 1) { add(PlaybackStep(index, StepKind.JP, jp, Locale.JAPANESE)) }
    add(PlaybackStep(index, StepKind.JP, jp, Locale.JAPANESE, pauseAfterMs = pause)) // 마지막 회차 뒤 정지(해석할 시간)
    add(PlaybackStep(index, StepKind.KR, kr, Locale.KOREAN))                          // 한국어 해석(음성)
    words.forEach { w ->                                                              // 단어마다 일→한 한 쌍
        add(PlaybackStep(index, StepKind.WORD_JP, w.jp, Locale.JAPANESE))
        add(PlaybackStep(index, StepKind.WORD_KR, w.kr, Locale.KOREAN))
    }
}

// 클립 전체 → 모든 문장의 스텝을 하나로 펼침 → 100문장도 끊김 없이 연속 재생
fun Clip.toSteps(): List<PlaybackStep> =
    sentences.flatMapIndexed { index, s -> s.toSteps(index) }
```

> `JP_REPEAT = 3` (일본어 3회). 회차는 상수 하나로 조정 — 데이터나 시퀀서를 건드릴 필요 없다.
> 클립의 모든 문장을 **하나의 평탄한 `steps` 리스트**로 펼치는 게 핵심. 시퀀서 루프는 이 리스트를 처음부터 끝까지 순회하므로 **연속 재생은 기본 동작**이고, **문장 이동은 인덱스 점프**(§2.5 표)로 환원된다.

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

---

## 3. 패키지 구조 (MVP)

```
com.bradlab.kiku
├── data
│   ├── Sentence.kt / Clip.kt / Word.kt   // @Serializable 모델
│   └── ClipRepository.kt                  // assets/clips/*.json 로드
├── player
│   ├── PlaybackStep.kt
│   ├── TtsSequencer.kt                    // TextToSpeech 래퍼 + 시퀀서 루프(상태 보유)
│   ├── PlaybackService.kt                 // ★ Foreground Service + MediaSession (백그라운드 재생)
│   └── PlayerViewModel.kt                 // 서비스에 바인딩 → StateFlow<PlayerUiState> 중계
└── ui
    ├── cliplist/ClipListScreen.kt         // (추후) 화면은 나중
    ├── player/PlayerScreen.kt             // (추후)
    └── theme/ (기존)
```

> 핵심: **재생 상태와 시퀀서 루프는 UI가 아니라 `PlaybackService`가 소유**한다. Activity/화면이 죽어도 서비스가 살아 소리를 계속 낸다. UI는 서비스에 붙어 상태를 구경하고 명령만 보낸다(없어도 재생은 돈다).

### 설계 결정

| 항목 | 선택 | 이유 |
|---|---|---|
| 데이터 저장 | `assets/clips/*.json` + `kotlinx.serialization` | 서버·DB 없음(CLAUDE.md 안티패턴 회피) |
| **백그라운드 재생** | **Foreground Service(type `mediaPlayback`) + MediaSession** | 화면 꺼져도 소리 유지. 알림/잠금화면/이어폰 버튼 컨트롤은 덤 |
| 시퀀서 위치 | **Service 내부**(viewModelScope 아님) | 화면·Activity 수명과 분리해야 백그라운드 생존 |
| ★ 저장 / 진행도 | **DataStore** (문장 id Set) | Room은 과함. 검증 단계엔 키-값이면 충분 |
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
    val category: String,        // "N4 회사생활", "N4 여행" ...
    val title: String,
    val sentences: List<Sentence>,
)

@Serializable
data class Sentence(
    val id: Int,
    val jp: String,
    val kr: String,
    val words: List<Word> = emptyList(),
    val pause: Long = 3000,
)

@Serializable
data class Word(
    val jp: String,   // 会社
    val kr: String,   // 회사
)
```

```json
// assets/clips/n4_office.json
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
      ],
      "pause": 3000 }
  ]
}
```

---

## 5. 화면 설계

### 5.1 ClipListScreen
- 카테고리별(N4 회사생활 / 여행 / 생활회화) 클립 카드 목록
- 카드: 제목 + 문장 수 + (선택) 진행도

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

> **화면은 추후 구성(MVP 후순위).** 이 앱은 듣기 전용이라 1차 조작 수단은 **미디어 알림/잠금화면/이어폰 버튼**(재생·정지·이전/다음 문장)이다. 위 PlayerScreen은 소리 파이프라인이 끝난 뒤 얹는다.

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
