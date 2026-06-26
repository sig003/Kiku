# 2026-06-25 — 앱 아이콘 + 스플래시 화면 적용

> 작업 일지. **안드로이드 개발이 처음인 사람**도 따라올 수 있게, 무엇을/왜/어떻게 했는지와 개념 설명을 함께 남긴다.
> 관련 커밋: `9563634 feat: 키쿠 앱 아이콘 + 스플래시 화면 적용`

---

## 0. 한눈에 보기

이번에 한 일을 한 문장으로: **"디자이너가 준 그림 1장(`foreground-1024.png`)을, 안드로이드가 '이게 앱 아이콘이자 스플래시야'라고 인식하도록 등록하고, 실제 폰에서 떠 있는 걸 눈으로 확인했다."**

결과물:
- 홈화면/앱서랍 아이콘 = 키쿠(어두운 배경 + 흰 聞 + 골드 KIKU)
- 앱 켤 때 스플래시 = 같은 디자인이 잠깐 떴다가 사라짐

---

## 1. 먼저 알아야 할 안드로이드 기본 개념

처음이면 이 단어들부터 알고 가면 아래가 술술 읽힌다.

### 1.1 코드와 "리소스"는 분리돼 있다
안드로이드 앱은 **로직(코드, `.kt` 파일)** 과 **리소스(그림·색·글자·화면 모양 같은 자원)** 를 따로 둔다.
리소스는 전부 한 폴더 밑에 있다:

```
app/src/main/
├── java/com/bradlab/kiku/   ← 코드 (.kt)
└── res/                      ← 리소스 (전부 여기)
    ├── mipmap-*/   앱 아이콘 전용 그림
    ├── drawable/   일반 그림/벡터
    ├── values/     색(colors.xml), 글자(strings.xml), 테마(themes.xml)
    └── ...
```

코드에서 리소스를 쓸 땐 `@`로 가리킨다. 예: `@color/kiku_bg`(색), `@mipmap/ic_launcher_foreground`(아이콘 그림), `@style/Theme.Kiku`(테마). **"@종류/이름"** 형식이라고 외우면 된다.

### 1.2 폴더 이름의 `-mdpi`, `-xxxhdpi`는 "화면 해상도별 버전"
안드로이드 폰은 화면 밀도(같은 1인치에 픽셀이 몇 개 박혔나)가 기기마다 다르다. 그래서 **같은 그림을 여러 크기로 넣어두면 OS가 그 폰에 맞는 걸 골라 쓴다.** 이 "크기 등급"을 density bucket이라 부른다:

| 폴더 | 배율 | 같은 108dp 아이콘의 실제 픽셀 |
|---|---|---|
| `mdpi`  | 1x   | 108px |
| `hdpi`  | 1.5x | 162px |
| `xhdpi` | 2x   | 216px |
| `xxhdpi`| 3x   | 324px |
| `xxxhdpi`| 4x  | 432px |

> `dp`(density-independent pixel)는 "밀도와 무관한 가상 단위"다. 디자인은 dp로 하고, OS가 폰 밀도에 맞춰 실제 px로 환산한다. 108dp 캔버스가 4x 폰에선 432px가 되는 식.

### 1.3 "테마(theme)"란
앱/화면의 **기본 외형 설정 묶음**(배경색, 상태바 색, 글꼴, 창 모양 등)이다. `res/values/themes.xml`에 정의하고, `AndroidManifest.xml`에서 "이 앱/화면은 이 테마를 쓴다"고 지정한다. 스플래시도 사실은 **특별한 테마**일 뿐이다(뒤에서 설명).

### 1.4 `AndroidManifest.xml` = 앱의 신분증/설계도
앱 이름, 아이콘, 어떤 화면(Activity)이 있고 어느 게 시작점인지, 어떤 권한을 쓰는지 등 **앱의 메타 정보**를 OS에 신고하는 파일. OS는 이걸 보고 앱을 설치·실행한다.

### 1.5 Gradle = 빌드 도구
소스/리소스/라이브러리를 모아 실제 설치 파일(APK)로 만들어 주는 도구. 설정은 `build.gradle.kts`(앱별 설정)와 `libs.versions.toml`(라이브러리 버전 목록)에 적는다. "라이브러리 추가 = 이 두 파일에 한 줄씩 적는 것"이라고 보면 된다.

---

## 2. 받은 에셋과 브랜드 색

`art/master/01-original/` 에 가공 전 원본을 보관했다.

| 파일 | 내용 | 용도 |
|---|---|---|
| `foreground-1024.png` | 1024×1024, **투명 배경** + 흰 聞 + 골드 KIKU | 아이콘 **전경** / 스플래시 아이콘 |
| `preview-circle.png` | 원형으로 잘랐을 때 미리보기 | 검수용(빌드에는 안 씀) |
| `preview-rounded.png` | 둥근사각으로 잘랐을 때 미리보기 | 검수용(빌드에는 안 씀) |

**브랜드 팔레트(확정):**

| 용도 | 값 |
|---|---|
| 배경(거의 검정) | `#0F1115` |
| 골드 액센트 | `#F2C14E` |
| 심볼 흰색 | `#FFFFFF` |

> 색을 픽셀에서 추출했을 땐 골드가 `#F3C14E`로 나왔는데, 이는 글자 가장자리의 **안티앨리어싱**(부드럽게 섞인 경계 픽셀) 때문에 1 정도 튄 값이었다. 원본 정본은 디자이너 확인 결과 `#F2C14E`. → 정본 사용.
> 색은 안드로이드에서 `#AARRGGBB`(맨 앞 2자리가 투명도) 형식이라 `colors.xml`엔 `#FF0F1115`처럼 불투명(FF)으로 적었다.

---

## 3. 앱 아이콘 만들기 (적응형 아이콘)

### 3.1 왜 "적응형 아이콘"인가
삼성·픽셀·LG… 제조사마다 아이콘을 **다른 모양으로 잘라서** 보여준다(원, 둥근사각, 물방울…). 옛날처럼 그림 1장을 통째로 쓰면, 어떤 폰에선 모서리가 어색하게 잘린다.

**적응형 아이콘**은 그림을 **두 겹(레이어)** 으로 받는다:
- **배경 레이어**: 우리 경우 단색 `#0F1115`
- **전경 레이어**: 우리 경우 투명 위에 그려진 흰 聞 + 골드 KIKU

OS가 이 두 겹을 합친 뒤 **제조사 모양대로 잘라낸다.** 그래서 어떤 모양으로 잘려도 자연스럽다. (아까 본 `preview-circle`/`preview-rounded`가 바로 "원으로 자르면", "둥근사각으로 자르면" 모습이었다.)

### 3.2 "안전 영역(safe zone)" — 잘림 방지 규칙
적응형 아이콘은 **108dp 정사각 캔버스**에 그리는데, 바깥 가장자리(사방 각 18dp)는 **잘려나갈 수 있는 여유분**이다. 중앙 원형 영역(지름 약 66~72dp) 안에 있는 것만 **항상 보인다고 보장**된다.
→ 그래서 디자이너에게 "심볼을 가운데 두고 여백을 넉넉히" 요청했고, 미리보기에서 잘림이 없음을 확인했다.

### 3.3 실제로 한 일

1. **전경 PNG를 5개 밀도로 생성.** 원본 1024짜리를 각 density bucket의 108dp 캔버스 픽셀 크기로 줄였다. `sips`는 macOS에 내장된 이미지 변환 명령:
   ```
   sips -z 108 108  foreground-1024.png --out mipmap-mdpi/ic_launcher_foreground.png
   sips -z 162 162  ... → mipmap-hdpi
   sips -z 216 216  ... → mipmap-xhdpi
   sips -z 324 324  ... → mipmap-xxhdpi
   sips -z 432 432  ... → mipmap-xxxhdpi
   ```
   (`-z 높이 너비` = 그 크기로 리사이즈. 정사각이라 둘이 같다.)

2. **색 등록** — `res/values/colors.xml`:
   ```xml
   <color name="kiku_bg">#FF0F1115</color>
   <color name="kiku_gold">#FFF2C14E</color>
   <color name="kiku_white">#FFFFFFFF</color>
   ```

3. **아이콘 정의 XML 교체** — `res/mipmap-anydpi/ic_launcher.xml` (그리고 둥근 버전 `ic_launcher_round.xml`):
   ```xml
   <adaptive-icon ...>
       <background android:drawable="@color/kiku_bg" />          <!-- 배경 = 색 -->
       <foreground android:drawable="@mipmap/ic_launcher_foreground" /> <!-- 전경 = 키쿠 -->
       <monochrome android:drawable="@mipmap/ic_launcher_foreground" /> <!-- 테마 아이콘용 -->
   </adaptive-icon>
   ```
   - `anydpi` 폴더 = "밀도와 무관한 정의". 실제 그림은 위에서 만든 밀도별 PNG를 OS가 골라 끼운다.
   - `background`에 색(`@color/...`)을 바로 넣을 수 있다(그림 대신 단색).
   - `monochrome`은 안드로이드 13+ "테마 아이콘"(배경색에 맞춰 한 색으로 칠해지는 아이콘)용. 일단 같은 전경을 지정해 둠.

4. **기본(초록 로봇) 그림 삭제.** 스캐폴드에 들어있던 `drawable/ic_launcher_foreground.xml`, `ic_launcher_background.xml`(안드로이드 기본 로봇)을 지웠다. 이제 아무도 안 가리키므로 안전하게 제거.

> **레거시 아이콘은 왜 안 건드렸나?** `mipmap-*dpi/ic_launcher.webp` 같은 옛날식 통짜 아이콘은 **안드로이드 8.0 미만**에서만 쓰인다. 우리 앱은 minSdk가 26(=8.0)이라 **모든 대상 기기가 적응형 아이콘을 지원**한다. 즉 레거시 아이콘은 절대 안 보이므로 그대로 둬도 무해하다.

---

## 4. 스플래시 화면 만들기

### 4.1 스플래시의 정체 — "화면"이 아니라 "테마 트릭"
초보자가 흔히 오해하는 부분: 스플래시는 별도 화면(Activity)을 만드는 게 **아니다.**
안드로이드 12부터 OS에 **공식 스플래시 기능**이 들어왔다. 우리는 `core-splashscreen` 라이브러리로 옛 버전(우리 minSdk 26 포함)에서도 같게 동작시킨다.

원리: 앱이 켜질 때 OS는 첫 화면이 준비될 때까지 **"런치 테마"의 배경**을 잠깐 보여준다. 이 짧은 순간에 OS가 **배경색 + 가운데 아이콘**을 그리도록 테마에 지정해두면, 그게 곧 스플래시다. 첫 화면이 준비되면 자동으로 사라진다.
→ **인위적인 "몇 초 멈춤"이 없다.** 앱 초기화에 걸리는 그 찰나만 보였다 사라진다(그래서 빠른 폰에선 정말 순식간).

### 4.2 실제로 한 일

1. **라이브러리 추가** (Gradle 두 파일):
   - `gradle/libs.versions.toml`:
     ```toml
     coreSplashscreen = "1.0.1"                      # [versions]
     androidx-core-splashscreen = { group = "androidx.core", name = "core-splashscreen", version.ref = "coreSplashscreen" }  # [libraries]
     ```
   - `app/build.gradle.kts`:
     ```kotlin
     implementation(libs.androidx.core.splashscreen)
     ```

2. **스플래시 테마 정의** — `res/values/themes.xml`:
   ```xml
   <style name="Theme.Kiku.Splash" parent="Theme.SplashScreen">
       <item name="windowSplashScreenBackground">@color/kiku_bg</item>              <!-- 배경색 -->
       <item name="windowSplashScreenAnimatedIcon">@mipmap/ic_launcher_foreground</item> <!-- 가운데 아이콘 -->
       <item name="postSplashScreenTheme">@style/Theme.Kiku</item>                  <!-- 끝나면 전환할 본 테마 -->
   </style>
   ```
   - `parent="Theme.SplashScreen"` 은 라이브러리가 제공하는 부모 테마. 이걸 상속해야 위 항목들을 쓸 수 있다.
   - `postSplashScreenTheme` = 스플래시가 끝난 뒤 갈아입을 평상복 테마(우리 본 테마 `Theme.Kiku`).

3. **런치 테마 변경** — `AndroidManifest.xml`: application/activity의 `android:theme`를 `@style/Theme.Kiku.Splash`로. (앱이 켜질 때 이 테마가 먼저 보이고, 코드에서 본 테마로 갈아입힘.)

4. **코드 한 줄** — `MainActivity.kt`:
   ```kotlin
   override fun onCreate(savedInstanceState: Bundle?) {
       installSplashScreen()        // super.onCreate 전에! (스플래시→본 테마 전환을 라이브러리가 처리)
       super.onCreate(savedInstanceState)
       ...
   }
   ```
   `installSplashScreen()`가 하는 일: 스플래시를 설치하고, 첫 화면 준비가 끝나면 자동으로 `postSplashScreenTheme`(본 테마)로 갈아입혀 스플래시를 걷어낸다.

---

## 5. 막판에 터진 빌드 오류와 해결 (compileSdk 36 → 37)

처음 `./gradlew :app:assembleDebug`를 돌리니 빌드가 **실패**했다. 그런데 원인은 **우리가 만진 아이콘/스플래시가 아니었다.**

에러 요지: 스캐폴드에 원래 있던 라이브러리(`androidx.core` 1.19.0, `androidx.lifecycle` 2.11.0)가 **"compileSdk 37 이상에서 컴파일하라"** 고 요구하는데, 프로젝트는 36이었다. 즉 **이 프로젝트는 그동안 한 번도 빌드된 적이 없어** 잠복해 있던 문제.

> **세 가지 SDK 버전 구분**(헷갈리기 쉬움):
> - `compileSdk` = **컴파일할 때** 참고하는 API 버전(최신 기능을 쓸 수 있게). ← 이것만 올리면 됨
> - `targetSdk` = 앱이 **어느 버전 동작 방식에 맞췄는지**(런타임 행동에 영향)
> - `minSdk` = **설치 가능한 최소** 안드로이드 버전
> compileSdk만 올리는 건 안전하다(런타임 행동·설치 범위는 그대로). 에러 메시지도 그렇게 안내했다.

해결 — `app/build.gradle.kts`:
```kotlin
compileSdk { version = release(37) }   // 36 → 37
```
→ 다시 빌드: **BUILD SUCCESSFUL**.

---

## 6. 변경된 파일 정리

| 파일 | 변경 | 분류 |
|---|---|---|
| `gradle/libs.versions.toml` | splashscreen 버전·라이브러리 등록 | 스플래시 |
| `app/build.gradle.kts` | splashscreen 의존성, compileSdk 37 | 스플래시/빌드 |
| `app/src/main/AndroidManifest.xml` | 런치 테마 → Splash | 스플래시 |
| `.../MainActivity.kt` | `installSplashScreen()` + import | 스플래시 |
| `res/values/themes.xml` | `Theme.Kiku.Splash` 정의 | 스플래시 |
| `res/values/colors.xml` | 브랜드 색 3개 | 아이콘/스플래시 |
| `res/mipmap-anydpi/ic_launcher.xml`, `ic_launcher_round.xml` | 전경/배경 재지정 | 아이콘 |
| `res/mipmap-*/ic_launcher_foreground.png` (5개) | 전경 PNG 신규 | 아이콘 |
| `res/drawable/ic_launcher_*.xml` (2개) | 기본 로봇 삭제 | 아이콘 |
| `art/master/01-original/*` | 원본 에셋 보관 | 자산 |

---

## 7. 빌드 & 실제 폰에서 확인하기

코드만 맞다고 끝이 아니라, **진짜 폰에 띄워봐야** 확인이다. 폰을 케이블 없이 **Wi-Fi로 연결**(무선 디버깅)했다.

### 7.1 `adb`가 뭐지
`adb`(Android Debug Bridge) = PC에서 안드로이드 기기를 제어하는 명령줄 도구(앱 설치, 실행, 화면 캡처 등). Android SDK 안에 들어있다(`~/Library/Android/sdk/platform-tools/adb`).

### 7.2 무선 디버깅 연결 (처음 한 번은 "페어링" 필요)
폰: 설정 → 개발자 옵션 → **무선 디버깅 ON**.

```bash
adb mdns services
# → 같은 Wi-Fi의 기기를 자동 검색. 10.160.120.58:37311 (_adb-tls-connect) 발견

adb pair 10.160.120.58:33467 159314
# → 페어링(짝짓기). 폰의 "페어링 코드로 기기 페어링" 팝업에 뜨는
#   [IP:포트]와 [6자리 코드]를 입력. ★ 페어링 포트(33467)와 연결 포트(37311)는 다름!

adb connect 10.160.120.58:37311
# → 실제 연결

adb devices -l
# → SM-S926N (갤럭시 S24+) 가 'device'로 보이면 성공
```

> **페어링 vs 연결** 헷갈림 주의: 페어링은 "이 PC를 신뢰" 등록(처음 1회, 별도 포트+코드). 연결은 등록된 뒤 실제 통신(다른 포트). 코드는 팝업 열려있는 동안만 유효.

### 7.3 설치 & 실행
```bash
APK=app/build/outputs/apk/debug/app-debug.apk
adb -s 10.160.120.58:37311 install -r "$APK"                                  # Success
adb -s 10.160.120.58:37311 shell am start -n com.bradlab.kiku/.MainActivity   # 앱 실행
```
- `-s 기기주소` = 기기가 여러 개 잡힐 때 어느 기기인지 지정.
- `install -r` = 기존에 깔려 있어도 덮어 재설치.
- `am start -n 패키지/.액티비티` = 특정 화면을 강제로 실행.

### 7.4 스플래시 순간 잡기 (화면 캡처)
스플래시는 순식간이라 그냥 캡처하면 본 화면만 잡힌다. **앱을 강제 종료 후 재실행하자마자 연속 캡처**해서 잡았다:
```bash
adb -s ... shell am force-stop com.bradlab.kiku    # 완전 종료
adb -s ... shell am start -n .../.MainActivity      # 재실행
adb -s ... exec-out screencap -p > splash1.png      # 바로 캡처(스플래시가 잡힘)
adb -s ... exec-out screencap -p > splash2.png      # 조금 뒤(본 화면)
```

### 7.5 결과
- ✅ **스플래시**: 어두운 배경 + 흰 聞 + 골드 KIKU가 가운데 표시 → 본 화면으로 전환되는 것까지 스크린샷으로 확인.
- ✅ **본 화면**: 기존 "Hello Android!"(화면은 아직 미구현).
- ✅ **런처 아이콘**: 같은 전경/배경으로 적용.

> 스플래시 아이콘이 살짝 작게 보인다. 전경 PNG에 여백이 넉넉해서인데(잘림 방지엔 안전), 더 크게 원하면 스플래시 전용으로 꽉 찬 아이콘을 하나 더 만들면 된다(추후 선택 사항).

---

## 8. 자주 막히는 지점 (트러블슈팅)

| 증상 | 원인/해결 |
|---|---|
| `Operation not permitted`로 `~/Downloads` 접근 불가 | macOS 개인정보 보호(TCC). 해당 앱에 "전체 디스크 접근" 권한을 주거나, Finder로 프로젝트 폴더에 복사 |
| 빌드 시 "compile against version 37 or later" | 의존성이 요구하는 `compileSdk`가 더 높음 → `compileSdk`만 올림(§5) |
| `adb connect` 실패 | 아직 **페어링 안 됨** → `adb pair`(다른 포트+6자리 코드) 먼저 |
| 스플래시가 캡처에 안 잡힘 | 너무 빨라서 정상. force-stop 후 재실행하며 즉시 연속 캡처(§7.4) |
| 아이콘이 초록 로봇 그대로 | 기기/런처가 아이콘을 캐시함 → 앱 재설치 또는 홈 런처 재시작 |

---

## 9. 결과물 & 다음

- 커밋 `9563634` (Claude 공동기여자 라인 제외) → `origin/main` 푸시 완료.
- **다음 작업 후보(§DESIGN 9 ①):** TTS로 일본어 한 문장 재생 + **화면 끈 채 백그라운드 재생** 가능성 확인. 이 앱 최대 기술 리스크라 가장 먼저 검증.
