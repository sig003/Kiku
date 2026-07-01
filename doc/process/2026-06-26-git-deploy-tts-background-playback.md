# 2026-06-26 — Git 푸시 + 실기기 배포 + TTS/백그라운드 재생 검증

> 작업 일지. **안드로이드 개발이 처음인 사람**도 따라올 수 있게, 무엇을/왜/어떻게 했는지와 개념 설명을 함께 남긴다.
> 관련 커밋:
> - `0a878b5 fix: TtsCheck.kt 오타(잘못 삽입된 11) 제거 — 컴파일 오류 수정`
> - `9a84dcf feat: 백그라운드 재생 검증용 Foreground Service (DESIGN.md §2.6)`

---

## 0. 한눈에 보기

오늘 한 일을 한 문장으로: **"코드를 GitHub에 올리고, 폰을 USB로 연결해 앱을 직접 깔아서, 이 앱의 가장 큰 기술적 의문 두 가지(① TTS가 일/한 다 되나 ② 화면 꺼도 소리 나나)를 실제 폰에서 눈·귀로 확인했다."**

결과:
- ✅ GitHub `sig003/Kiku`에 코드 푸시 완료
- ✅ 갤럭시 S24+에 앱 설치·실행
- ✅ **리스크 1** — 일본어·한국어 TTS 둘 다 재생됨
- ✅ **리스크 2** — Foreground Service로 **화면 꺼도 재생 유지됨**

→ 이 앱은 이제 "기술적으로 되는가?"가 **완전히 yes.** 남은 건 미지의 리스크가 아니라 순수 구현.

---

## 1. Git: 코드를 GitHub에 올리기

### 1.1 개념 — Git / GitHub / remote
- **Git**: 코드 변경 이력을 저장하는 도구(내 컴퓨터 안).
- **GitHub**: 그 이력을 인터넷에 올려두는 서버(백업 + 공유).
- **remote**: 내 로컬 저장소가 바라보는 "원격 주소"의 별명. 보통 `origin`이라 부른다.
- **push** = 로컬 → GitHub 업로드, **pull** = GitHub → 로컬 내려받기.

### 1.2 한 일
처음엔 이 폴더가 git 저장소가 아니었다. 그래서:

```
git init -b main                 # 저장소 시작 (기본 브랜치 이름 main)
git config user.name  "sig003"   # 이 저장소에서 쓸 작성자 이름
git config user.email "sig03@naver.com"
git remote add origin https://github.com/sig003/Kiku.git
git add -A && git commit -m "..."  # 변경분 묶어서 한 점(commit)으로 기록
```

### 1.3 막혔던 점 ① — 회사 계정이 끼어듦 (403)
첫 푸시에서 `Permission denied to locuskorea1` 에러. 원인:

> macOS의 **키체인**(비밀번호 금고)에 이전에 쓰던 **회사 GitHub 계정**이 저장돼 있어서, git이 개인 레포에 회사 계정으로 들어가려다 거부당함.

**해결 원칙:** 전역 키체인(회사 계정)은 절대 건드리지 않는다. 대신 **이 레포에만** 개인 토큰을 쓴다.

### 1.4 개념 — PAT(Personal Access Token)
GitHub는 이제 비밀번호 대신 **토큰**(긴 임시 비밀번호 문자열)으로 인증한다.
- 발급: github.com → Settings → Developer settings → Tokens
- **Fine-grained 토큰**은 기본이 거의 "읽기 전용"이라, 푸시하려면 **Contents: Read and write** 권한을 켜야 한다.

### 1.5 막혔던 점 ② — 토큰 권한 부족 (또 403)
토큰을 줬는데도 403. 이번엔 계정은 `sig003`로 맞았지만 **토큰에 쓰기 권한이 없어서**였다.
(GitHub API로 확인했더니 레포는 보였지만 — 그건 "내 계정 역할"이고 "토큰 권한"은 별개였다.)
→ 토큰 설정에서 **Contents: Read and write**로 바꾸니 통과.

### 1.6 최종 푸시 방법 (앞으로도 이렇게)
```
git remote set-url origin "https://<TOKEN>@github.com/sig003/Kiku.git"
git push -u origin main
git remote set-url origin "https://github.com/sig003/Kiku.git"   # 끝나면 토큰 빼서 흔적 제거
```
- 토큰을 remote 주소에 **잠깐만** 넣었다가 푸시 후 제거 → `.git/config`에 평문으로 안 남음.
- **pull은 인증 불필요**(공개 레포라 읽기는 자유) → 그냥 `git pull`.

### 1.7 .gitignore — 올리면 안 되는 것 거르기
**개념:** `.gitignore`에 적은 파일/폴더는 git이 무시한다(추적 안 함).
기본 안드로이드 무시 목록에 더해 보강한 것:
- `*.jks`, `*.keystore` — **앱 서명 키. 유출되면 배포 권한이 위험**해지는 가장 중요한 항목
- `secrets.properties`, `.env`, `*.secret` — 나중에 붙일 ElevenLabs/OpenAI API 키
- `google-services.json`, `**/build/`, `.idea/`(공유할 설정만 예외) 등

---

## 2. 실기기 배포: 폰에 직접 깔기

### 2.1 개념 — adb / installDebug
- **adb**(Android Debug Bridge): PC ↔ 안드로이드 폰을 연결해 명령을 주고받는 도구. SDK 안에 들어있다(`~/Library/Android/sdk/platform-tools/adb`).
- **에뮬레이터 vs 실기기**: 가짜 폰(에뮬레이터) 대신 진짜 폰에 깔면 실제 음성·성능을 그대로 확인할 수 있다. TTS 음성은 기기마다 다르므로 **실기기 검증이 중요**하다.
- `./gradlew installDebug` = 디버그용 앱(APK)을 빌드해서 연결된 폰에 바로 설치.

### 2.2 한 일 & 막혔던 점
1. **기기 인식 안 됨** → 폰에서 **개발자 옵션 → USB 디버깅 ON**, 연결 시 뜨는 *"USB 디버깅 허용?"* 팝업 **허용**. 그제서야 `adb devices`에 `SM-S926N`(갤럭시 S24+)이 잡힘.
2. **빌드 실패 (`TtsCheck.kt:106`)** → pull로 받아온 파일에 실수로 들어간 `11` 문자 때문. 제거하니 컴파일 통과. (커밋 `0a878b5`)
3. **설치 실패 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`** → 폰에 **다른 서명 키로 깔린 같은 앱**이 이미 있었음(다른 PC/안드로이드 스튜디오에서 설치). `adb uninstall com.bradlab.kiku`로 지우고 재설치하니 성공.

> **개념 — 앱 서명:** 안드로이드는 앱마다 "서명"(개발자 도장)을 확인한다. 같은 패키지명이라도 도장이 다르면 덮어쓰기를 거부한다(보안). 그래서 기존 걸 지워야 했다.

---

## 3. 리스크 1 검증 — TTS가 일/한 다 되나 (DESIGN.md §9)

### 3.1 개념 — TTS(TextToSpeech)
안드로이드에 내장된 **글자 → 음성** 변환 엔진. 문자열을 주면 읽어준다.
```kotlin
tts.language = Locale.JAPANESE
tts.speak("昨日は会社を休みました。", TextToSpeech.QUEUE_FLUSH, null, "id")
```
- 비용 0원, 인터넷 불필요. 단점은 음성 품질이 기기마다 다름.
- **핵심 주의:** 폰에 해당 언어 음성 데이터가 설치돼 있어야 한다. `isLanguageAvailable()`로 확인 가능(§7.2).

### 3.2 검증 화면 — `TtsCheckScreen` (`app/.../TtsCheck.kt`)
일회용 검증 화면. 버튼으로 일본어/한국어/연속 재생을 눌러보고, 상단에 "일본어 있음/없음, 한국어 있음/없음"을 표시한다.
또 `speak()`를 **코루틴 `suspend` 함수로 감싸**(`speakAndAwait`) "한 문장 다 읽으면 다음 줄로" 식의 순차 코드를 가능하게 했다(DESIGN.md §2.4의 토대).

### 3.3 결과
**갤럭시 S24+에서 일본어·한국어 둘 다 잘 나옴.** → 리스크 1 통과.

---

## 4. 리스크 2 검증 — 화면 꺼도 소리 나나 (DESIGN.md §2.6)

### 4.1 왜 이게 리스크인가
이 앱은 **듣기 전용**(라디오/팟캐스트처럼)이라, 화면 끄거나 다른 앱 켜도 소리가 계속 나야 한다.
그런데 안드로이드는 배터리를 위해 **백그라운드 앱을 수시로 재운다.** 그냥 두면 화면 끄는 순간 소리가 끊긴다.

### 4.2 개념 — Foreground Service
- **Service**: 화면(Activity) 없이 뒤에서 도는 작업 단위.
- **Foreground Service**: "지금 사용자가 인지하는 중요한 작업 중"이라고 OS에 알리는 서비스. **반드시 알림(notification)을 띄워야** 하고, 대신 OS가 안 죽인다. 음악 앱이 알림에 재생 컨트롤 띄우는 게 이것.
- `foregroundServiceType="mediaPlayback"`: "나 미디어 재생 중이야"라고 종류를 명시(요즘 안드로이드 필수).

### 4.3 한 일 — `PlaybackService.kt` (신규, 커밋 `9a84dcf`)
검증용 **최소** 서비스. (본 구현의 시퀀서·MediaSession·AudioFocus는 아직 X)
- 서비스가 TTS를 **소유**하고(화면이 아니라), 코루틴 루프로 일/한 샘플 문장을 무한 반복 재생.
- `startForeground()` + 알림(재생 상태/정지 버튼) 표시.
- 매니페스트에 권한 3개 추가: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`(알림 표시용, 안드로이드 13+는 사용자 허용 필요).
- **새 의존성(라이브러리) 0개** — 전부 안드로이드 기본 API로 해결.

> **왜 시퀀서를 서비스가 갖나:** 재생 상태와 루프를 화면(Activity)이 들고 있으면, 화면이 죽을 때 재생도 죽는다. 그래서 **서비스가 소유**해야 화면과 무관하게 소리가 이어진다(DESIGN.md §2.6 핵심).

### 4.4 결과
시작 버튼 → 화면 끄기 → **소리 계속 나옴 확인.** → 리스크 2 통과.

---

## 5. 오늘의 결론 & 다음 단계

### 결론
- 이 앱의 두 가지 진짜 기술 리스크가 **실기기에서 모두 해소**됐다.
- 이제부터는 "되는지 모르는 것"이 아니라 "만들면 되는 것"만 남았다.

### 지금 코드 상태
- `TtsCheck.kt` / `PlaybackService.kt`는 **검증용(임시)**. 본 구현이 아니다.
- 다음은 이 둘을 DESIGN.md의 실제 구조로 확장:
  - `TtsSequencer` — 문장을 `PlaybackStep`으로 평탄화해 연속재생/문장이동/속도/쉐도잉 지원(§2.3~2.5)
  - 데이터 — `assets/clips/*.json` + 모델(`Clip`/`Sentence`/`Word`)(§4)
  - `PlaybackService`에 MediaSession(잠금화면/이어폰 버튼) + AudioFocus(전화 오면 멈춤) 얹기(§2.6)
  - UI — ClipList / Player 화면(§5)

### 미정/메모
- 본 구현 푸시 시 **토큰 다시 필요**(1.6 방식).
