# kiku TODO

> DESIGN.md 기반 다음 작업 목록. 검증(TTS 재생 / 백그라운드 재생)은 실기기(갤럭시 S24+)에서 통과 완료 — 아래는 검증용 임시 코드를 실제 구조로 확장하는 작업들.
> 상태 표기: `[ ]` 예정 · `[~]` 진행 중 · `[x]` 완료

## 구현

- [ ] **1. 일본어 목소리 4종 청취 비교**
  검증 화면 재생 버튼에 voice 순환(htm/jab/jac/jad) + pitch 실험. 남/여·음색 차이를 귀로 비교해 MVP에 내장 TTS 4종으로 충분한지(대화 모드 배역 분리) 판단.
- [ ] **2. TtsSequencer 구현** — DESIGN.md §2.3~2.5
  문장을 `PlaybackStep`(JP/KR/WORD_JP/WORD_KR)으로 평탄화, 클립 전체를 하나의 steps 리스트로 펼쳐 연속 재생. 연속재생/문장이동/다시듣기/속도/쉐도잉을 인덱스 점프+파라미터 변경으로. `speakAndAwait` 재사용.
- [ ] **3. 데이터 모델 + assets JSON 로더** — §4, §2.7
  `Clip`/`Sentence`/`Word`(@Serializable) + `ClipMode`/`PlaybackPattern`. `ClipRepository` interface + `AssetClipRepository`. kotlinx.serialization 의존성 추가. N4 첫 클립(10~20문장) JSON으로 실데이터 테스트.
- [ ] **4. PlaybackService 본구현** — §2.6
  검증용 서비스에 `TtsSequencer` 얹기 + MediaSession(알림/잠금화면/이어폰 → 재생·정지·이전/다음) + AudioFocus(전화·타앱 소리 시 일시정지·복귀, AUDIO_BECOMING_NOISY). UI는 서비스 바인딩해 StateFlow 구독.
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
