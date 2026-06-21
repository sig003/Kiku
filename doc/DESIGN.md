# kiku 설계 제안서

> 대상: JLPT N4 청해 훈련 안드로이드 앱 (`com.bradlab.kiku`)
> 전제: [CLAUDE.md](./CLAUDE.md)의 컨셉/로드맵/비즈니스 모델을 따른다. 이 문서는 그 위에 얹는 **기술 설계**다.

---

## 0. 한 줄 요약

화면(클립 목록 / 플레이어)은 단순하다. 이 앱의 진짜 엔지니어링 포인트는 **TTS 재생 시퀀서 단 하나**다.
"일본어 2회 → 정지 → 한국어 → 단어 → 다음 문장" 흐름이 전부 비동기라서, **여기 한 군데만 제대로 설계하면 나머지 기능(건너뛰기·다시듣기·속도·쉐도잉)은 전부 파생으로 떨어진다.**

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

CLAUDE.md의 문장 흐름:

```
일본어 음성(2회) → 3초 정지 → 한국어 해석 → 중요 단어(일→한) → 다음 문장
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
enum class StepKind { JP, KR, WORD }

data class PlaybackStep(
    val sentenceIndex: Int,   // 어느 문장에 속하는지 (점프/하이라이트용)
    val kind: StepKind,
    val text: String,
    val locale: Locale,
    val pauseAfterMs: Long = 0,
)

// 한 문장 → [JP, JP(+정지), KR, WORD, WORD, ...]
fun Sentence.toSteps(index: Int): List<PlaybackStep> = buildList {
    add(PlaybackStep(index, StepKind.JP, jp, Locale.JAPANESE))
    add(PlaybackStep(index, StepKind.JP, jp, Locale.JAPANESE, pauseAfterMs = pause)) // 2회차 뒤 정지
    add(PlaybackStep(index, StepKind.KR, kr, Locale.KOREAN))                          // 옵션: 표시만 할 수도
    words.forEach { add(PlaybackStep(index, StepKind.WORD, it, Locale.JAPANESE)) }
}
```

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
| 일시정지 | `playJob.cancel()` + `tts.stop()` |
| 다음 문장 ▶ | `currentStepIndex = 다음 sentenceIndex의 첫 스텝` |
| 다시듣기 | `currentStepIndex = 현재 sentenceIndex의 첫 스텝` |
| 이전 문장 ◀ | `currentStepIndex = 이전 sentenceIndex의 첫 스텝` |
| 속도 조절 | `speed` 값만 변경 → 다음 스텝부터 반영 |
| **쉐도잉 모드** | `pauseAfterMs`를 문장 길이만큼 키운 변형일 뿐 — 별도 엔진 불필요 |

> **포인트:** 건너뛰기·다시듣기·속도·쉐도잉이 전부 "인덱스 점프 + 파라미터 변경"으로 환원된다. 재생 엔진을 새로 만들 일이 없다.

---

## 3. 패키지 구조 (MVP)

```
com.bradlab.kiku
├── data
│   ├── Sentence.kt / Clip.kt        // @Serializable 모델
│   └── ClipRepository.kt            // assets/clips/*.json 로드
├── player
│   ├── PlaybackStep.kt
│   ├── TtsSequencer.kt              // TextToSpeech 래퍼 + 시퀀서 루프
│   └── PlayerViewModel.kt           // StateFlow<PlayerUiState>
└── ui
    ├── cliplist/ClipListScreen.kt
    ├── player/PlayerScreen.kt
    └── theme/ (기존)
```

### 설계 결정

| 항목 | 선택 | 이유 |
|---|---|---|
| 데이터 저장 | `assets/clips/*.json` + `kotlinx.serialization` | 서버·DB 없음(CLAUDE.md 안티패턴 회피) |
| ★ 저장 / 진행도 | **DataStore** (문장 id Set) | Room은 과함. 검증 단계엔 키-값이면 충분 |
| DI | 없음 (ViewModel이 직접 생성) | Hilt 도입 가치 없음. 서버 붙일 때 재검토 |
| 네비게이션 | 화면 2개 → 단순 상태 전환 or Navigation-Compose 최소 사용 | 라우팅 복잡도 없음 |
| 상태 관리 | ViewModel + `StateFlow<PlayerUiState>` | 표준 단방향 흐름 |

---

## 4. 데이터 모델

CLAUDE.md의 JSON 구조를 그대로 따른다.

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
    val words: List<String> = emptyList(),
    val pause: Long = 3000,
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
      "words": ["昨日", "会社", "休む"], "pause": 3000 }
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

---

## 6. 조정된 로드맵 (셋업 완료 기준)

| 단계 | 할 일 | 검증 목표 |
|---|---|---|
| **지금 (반나절)** | 버튼 1개 + `speakAndAwait`로 일본어 한 문장 재생 | 기기에 일본어 음성 있나 / 기술 가능성 |
| **1주** | `TtsSequencer` + `PlayerScreen` — 한 클립 풀 흐름 (재생/정지/건너뛰기/다시듣기) | 핵심 UX 동작 |
| **2주** | GPT로 N4 100문장 → JSON 3카테고리, ClipList 연결, ★ 저장 / 속도 조절 | **내가 매일 쓰나** |
| **3주** | 다듬기 + AdMob + 블로그 홍보 | 남도 쓰나 |

---

## 7. 지금 결정해야 할 갈림길

### 7.1 한국어 해석 — 읽어줄까 vs 표시만 할까
- **읽어주기**: 기기에 한국어 TTS 음성 필요 + 매 문장 언어 전환 레이턴시
- **표시만(추천)**: 청해 훈련 본질상 음성은 **일본어만**, 한국어는 화면 토글이 더 깔끔
- 위 시퀀서는 둘 다 지원하나 **기본값은 일본어 음성만** 권장

### 7.2 TTS 음성 미설치 기기 대응 (필수)
- 일본어 음성 데이터 없는 갤럭시가 꽤 있음
- 첫 실행 시 `isLanguageAvailable(Locale.JAPANESE)` 체크 → 없으면 음성 설치 인텐트(`ACTION_INSTALL_TTS_DATA`) 안내
- **빠지면 "소리 안 나요" 리뷰 폭탄.** MVP에 반드시 포함

---

## 8. 리스크 / 메모

| 리스크 | 대응 |
|---|---|
| TTS 음성 품질·발음이 기기마다 다름 | CLAUDE.md 방식 3(ElevenLabs mp3)으로 후기 교체 가능. MVP는 검증용 |
| 일본어 음성 미설치 | 7.2 대응 |
| 한국어 음성 미설치 | 7.1에서 "표시만" 선택 시 회피 |
| onDone이 일부 엔진에서 누락 | `onError`도 resume 처리(2.4에 반영), 타임아웃 가드 고려 |
| 백그라운드 재생 | MVP 범위 밖. 필요 시 추후 MediaSession/Foreground Service |

---

## 9. 다음 액션

1. **반나절 검증 코드**: 버튼 → `speakAndAwait`로 일본어 한 문장. "기술적으로 가능한가"에 대한 답을 코드로 확정.
2. 통과하면 → `TtsSequencer` + `PlayerScreen` 골격 작성.
3. 병행: GPT로 N4 첫 클립(10~20문장) JSON 생성해 실데이터로 테스트.
