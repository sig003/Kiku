# 2026-07-03 — 실사용 피드백 6가지 수정 (랜덤·위치저장·이어폰·물결·속도)

> 작업 일지. **안드로이드 개발이 처음인 사람**도 따라올 수 있게, 무엇을/왜/어떻게 했는지와 개념 설명을 함께 남긴다.
> 실기기(갤럭시 S24+)로 직접 들으며 나온 피드백을 반영한 세션.

---

## 0. 한눈에 보기

어제(7/2) 실제 UI+콘텐츠까지 붙인 뒤, **직접 들어보며** 나온 불편 6가지를 고쳤다:

1. 전체 랜덤이 30문장만 나옴 → **100문장**
2. 클립마다 **랜덤 재생**도 필요 → 카드에 🔀 버튼
3. 앱 닫으면 재생 위치를 잊음 → **위치 저장·복원**
4. 이어폰 버튼으로 정지가 안 됨 → **미디어 버튼 처리**
5. `～합니다`를 "물결 합니다"로 읽음 → **언어별 물결 처리**
6. 일본어 3회 읽기가 빠름 → **기본 속도 0.9**

전부 실사용에서 나온 것이라, 앱을 "쓸 만하게" 다듬는 작업이다.

---

## 1. 랜덤 100문장 (전체 랜덤)

**원인:** `AssetClipRepository.randomClip()`이 `take(30)`으로 30개만 뽑았다.
**수정:** 기본 개수를 **100**으로. 전체 문장(300)을 섞은 뒤 100개.

```kotlin
suspend fun randomClip(count: Int = 100): Clip { ... shuffled().take(count) ... }
```

---

## 2. 클립별 랜덤 재생

**원인:** "🔀 전체 랜덤" 하나뿐, 개별 클립은 순서대로만.
**수정:**
- 각 클립 카드에 **🔀 랜덤 재생** 버튼 추가. 카드 본체 탭 = 순서대로, 🔀 = 그 클립만 섞어서.
- 화면 전환에 **shuffle 여부**를 실어 보냄: `ClipListScreen(onOpen: (Int, Boolean))` → `PlayerScreen(clipId, shuffle)` → `service.open(clipId, shuffle)`.
- 서비스가 `shuffle=true`면 그 클립 문장을 `shuffled()`해서 적재.

> **개념 — 왜 화면이 shuffle을 서비스로 넘기나:** 재생은 서비스가 소유(§어제 문서 §5)하므로, 화면은 "이 클립을 섞어서 열어줘"라는 **명령만** 보낸다. 실제 섞기·재생은 서비스가 한다(단방향).

---

## 3. 재생 위치 저장·복원

**원인:** 진행 위치를 저장하지 않아, 서비스가 종료되면 문장 1로 초기화.
**수정:** `SharedPreferences`에 **`클립ID → 문장번호`**를 저장하고, 그 클립을 다시 열면 복원.

```kotlin
// 저장: 문장 바뀔 때 (순차·실제 클립만)
private fun saveProgress(st: PlayerUiState) {
    if (currentShuffled || st.clipId < 1) return
    if (st.finished) progress.edit().remove("pos_${st.clipId}").apply()   // 완주 → 위치 정리(다음엔 처음부터)
    else if (st.sentenceIndex != lastSavedSentence) {
        progress.edit().putInt("pos_${st.clipId}", st.sentenceIndex).apply()
        lastSavedSentence = st.sentenceIndex
    }
}
// 복원: open() 에서 저장된 위치로 load
val start = if (!currentShuffled && clip.id >= 1) progress.getInt("pos_${clip.id}", 0) else 0
sequencer.load(clip, start)
```

- 시퀀서 `load(clip, startSentence)`로 확장 — 지정 문장부터 시작.
- **랜덤/셔플은 매번 순서가 달라 저장 제외.**
- **부하 걱정 없음:** DRILL 한 문장은 약 15~25초라 저장은 그 간격에 정수 하나. `apply()`는 백그라운드 비동기.

> **개념 — SharedPreferences:** 키-값 몇 개를 파일에 저장하는 안드로이드 기본 저장소. 값이 적을 때 가볍게 쓴다. (진행도가 커지면 설계상 DataStore로 통합 예정, §10.4)

---

## 4. 이어폰 버튼으로 정지 (유튜브는 되는데)

**원인:** MediaSession은 있었지만 **이어폰 하드웨어 버튼(재생/정지 훅) 이벤트를 직접 처리하지 않음.** 일부 기기는 `onPlay/onPause` 콜백으로 안 오고 `onMediaButtonEvent`로 온다.
**수정:** MediaSession 콜백에 **`onMediaButtonEvent`** 추가 — 키코드를 받아 재생/정지/다음/이전/정지로 연결.

```kotlin
override fun onMediaButtonEvent(intent: Intent): Boolean {
    val ev = /* Intent.EXTRA_KEY_EVENT 에서 KeyEvent 추출 */
    when (ev.keyCode) {
        KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_HEADSETHOOK -> playPause()
        KEYCODE_MEDIA_NEXT -> next(); KEYCODE_MEDIA_PREVIOUS -> prev(); ...
    }
}
```

> **주의:** 이어폰 종류(유선/블루투스/버즈)마다 보내는 키코드·동작이 달라, 기기별로 더 손봐야 할 수 있음. 안 되면 `adb logcat`으로 키코드 확인 후 조정.

---

## 5. 물결표(～) 읽기 — "무엇무엇"

**원인:** 단어 데이터의 문법 자리표시 물결표(`～てください`, `～해 주세요`)를 TTS가 글자 그대로 **"물결"** 로 읽음.
**결정 과정:** 처음엔 언어별 관용 자리표시어(한국어 "무엇무엇" / 일본어 "なになに")로 치환하려 했으나, **"なになに"는 N4 입문자에게 오히려 헷갈린다**는 피드백 → **옵션 B** 채택.
**수정(옵션 B):** 낭독 직전에 언어별로 치환.
- **한국어** `～해 주세요` → **"무엇무엇 해 주세요"** (모국어라 "패턴"이 자연스럽게 전달)
- **일본어** `～てください` → **"てください"** (물결만 제거, 문법형만 또렷이)
- **화면 단어 칩엔 `～` 그대로** — 자리표시라는 뉘앙스는 눈으로.

```kotlin
private fun vocalize(text: String, locale: Locale): String {
    val placeholder = if (locale.language == Locale.KOREAN.language) "무엇무엇 " else ""
    return text.replace("～", placeholder).replace("~", placeholder)
}
```

> 시퀀서가 스텝의 언어를 알고 있어, **읽기 직전에** 언어에 맞게 변환한다(화면 표시는 안 건드림).

---

## 6. 기본 속도 0.9

**원인:** 기본 속도 1.0이라 일본어 3회 반복이 조금 빠르게 느껴짐(청해 입문).
**수정:** 기본 속도를 **0.9**로, 속도 버튼에 0.9 추가 → `0.8 / 0.9 / 1.0 / 1.2`.

---

## 7. 변경 파일

| 파일 | 내용 |
|---|---|
| `ClipRepository.kt` | randomClip 100개 |
| `TtsSequencer.kt` | 기본 속도 0.9, `load(clip, startSentence)`, 물결 `vocalize` |
| `PlaybackService.kt` | `open(clipId, shuffle)`, 위치 저장/복원(SharedPreferences)+완주 초기화, `onMediaButtonEvent` |
| `ClipListScreen.kt` | 카드 🔀 랜덤 재생 버튼, `onOpen(Int, Boolean)` |
| `PlayerScreen.kt` | shuffle 인자 전달, 속도 0.9 옵션 |
| `MainActivity.kt` | 화면 전환에 shuffle 상태 추가 |

---

## 8. 결과 & 남은 확인

- 6가지 모두 반영·빌드·배포. 컴파일/실행 정상.
- **실기기 확인 필요:** 특히 **4번(이어폰 버튼)** 은 기기·이어폰마다 갈릴 수 있어 실사용 확인 후 조정 가능.
- 다음: 실사용하며 어색한 문장 다듬기, ★저장(오답노트), 대화/청해 모드(TODO 9·10), N3(TODO 11).
