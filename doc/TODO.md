# kiku TODO

> DESIGN.md 기반 다음 작업 목록. 검증(TTS 재생 / 백그라운드 재생)은 실기기(갤럭시 S24+)에서 통과 완료 — 아래는 검증용 임시 코드를 실제 구조로 확장하는 작업들.
> 상태 표기: `[ ]` 예정 · `[~]` 진행 중 · `[x]` 완료

## 구현

- [x] **1. 일본어 목소리 청취 비교** — *완료(2026-06-26, 갤럭시 S24+)*
  검증 화면에 목소리별 재생 버튼 + 피치(0.8/1.0/1.2) 추가해 귀로 비교.
  **결론:** 피치 0.8/1.2 부자연 → **1.0 고정**. 일본어 **남·여 voice 둘 다 존재** → **대화 배역 구분을 내장 TTS로 커버 가능**, ElevenLabs는 MVP 밖. 화자→voice 매핑은 성별/역할 조회 방식(하드코딩 X). → DESIGN §7.3, §2.7 반영.
- [x] **2. TtsSequencer 구현** — DESIGN.md §2.3~2.5 — *완료(2026-06-26, 화면 구동 검증)*
  `Playback.kt`(모델+스텝 평탄화+샘플) + `TtsSequencer.kt`(코루틴 루프 + StateFlow + 재생/일시정지/이전·다음·다시듣기/속도). `speakAndAwait` 재사용.
  DRILL 흐름: **JP×3 → KR → JP×1 → 단어**, 정지값 실측 확정(회차1.5s·해석3s·KR뒤0.8s·문장사이2s), 클립 끝 종료안내(일→한). 실기기에서 연속재생·문장이동·속도 동작 확인.
  *남은 것:* 화자→voice 매핑(§2.7), 쉐도잉, 백그라운드 이식은 TODO 4에서.
- [x] **3. 데이터 모델 + assets JSON 로더** — §4, §2.7 — *구조 완료(2026-06-26)*
  모델에 `@Serializable` + kotlinx.serialization 의존성/플러그인 추가. `ClipRepository` interface + `AssetClipRepository`(assets/clips/*.json 파싱·캐시). 화면이 JSON 첫 클립 로드(폴백=샘플). `n4_office.json`(4문장) 샘플로 검증.
  덤: 문장 내 쉼표(、，,)에서 끊어 읽고 짧은 무음 삽입(speakAndAwait).
  *남은 것:* N4 실문장 10~20개로 채우기(콘텐츠 — TODO 8과 맞물림), 여러 클립 목록은 TODO 5.
- [x] **4. PlaybackService 본구현** — §2.6 — *완료(2026-07-02, 갤럭시 S24+ 검증)*
  `PlaybackService`가 TtsSequencer+TTS 소유, startForeground(mediaPlayback)+미디어 알림. MediaSession(잠금화면/이어폰 버튼 재생·정지·이전·다음) + AudioFocus(전화·타앱 소리 시 일시정지, AUDIO_BECOMING_NOISY 정지). UI는 서비스 바인딩해 StateFlow 구독+명령. 화면 꺼도/앱 나가도 재생 유지 확인.
  *남은 것:* AudioFocus GAIN 자동 재개는 생략(수동 재생), 화자→voice 매핑(§2.7)은 콘텐츠 붙일 때.
- [ ] **5. UI 화면 — ClipList / Player** — §5
  `TtsCheckScreen`을 실제 화면으로 교체. 카테고리별 클립 목록 + 재생 화면(현재 문장 하이라이트, 한/단어 토글, 진행바, 이전/다시듣기/다음, 속도, ★저장). NavHost로 cliplist ↔ player/{clipId}.

## 문서

- [ ] **6. 작업 일지 작성** (doc/process, 마일스톤마다) — *반복*
  세션/마일스톤 후 `doc/process/YYYY-MM-DD-*.md`. 초보도 따라올 수 있게 무엇을/왜/어떻게 + 개념 설명, 관련 커밋 명시, 파일명 영문.
- [ ] **7. DESIGN.md 갱신** (구현하며 결정·변경 반영) — *반복*
  코드 스니펫과 실제 구현 일치 유지, §7 갈림길 등 확정 결정 반영, 검증용 코드가 본구현으로 대체되면 문서도 갱신, 로드맵(§9) 진척 표시.
- [ ] **8. 콘텐츠 제작 가이드 문서화** (N4 문장 생성 규칙)
  GPT 프롬프트·규칙 + JSON 스키마(Clip/Sentence/Word, ClipMode/pattern), 난이도·카테고리·화자 표기 컨벤션. 콘텐츠를 반복 생산할 템플릿 확보. (3번과 맞물림)

## 참고
- 순서 가이드: 1(빠른 실험) → 2 · 3(병행) → 4 → 5. 6·7은 상시.
- 푸시는 개인 PAT 필요(회사 계정이 키체인에 있음). DESIGN.md/작업일지 참고.
