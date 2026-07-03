# KIKU — 랜덤(셔플) 버튼 아이콘

"전체 랜덤" 기능용 아이콘. 교차 화살표 = 무작위. 다크+골드 브랜드에 맞췄어요.

## 파일
| 파일 | 용도 |
|---|---|
| `shuffle-gold.svg` | ⭐ 벡터 (골드 stroke). Android `ImageVector`/드로어블로 쓰기 좋음 |
| `shuffle-ink.svg` | 벡터 (잉크 stroke) — 골드 배경 버튼 위에 얹을 때 |
| `shuffle-gold-96/192.png` | 골드 글리프, 투명 배경 |
| `shuffle-ink-192.png` | 잉크 글리프, 투명 (골드 FAB 안에 얹기용) |
| `fab-gold-192.png` | ⭐ 골드 원형 버튼 (메인 랜덤 버튼) |
| `surface-192.png` | 다크 서피스 원형 버튼 (보조) |
| `preview.html` | 전체 미리보기 |

## 색
- 골드 `#F3C14E` · 잉크 `#0F1115` · 다크 서피스 `#191E28` · 뮤트(비활성) `#9BA3B0`
- stroke width는 2 기준, 작아질수록 2.1~2.3으로 약간 두껍게.

## 권장 사용
- **메인 "전체 랜덤"** → `fab-gold-192.png` 또는 골드 원 + `shuffle-ink.svg`
- 히어로 CTA 필 버튼 → 텍스트 앞에 `shuffle-ink` (골드 배경)
- 리스트/툴바 보조 → `surface-192.png` 또는 골드 글리프
- 안드로이드는 SVG를 Vector Drawable로 임포트해 `tint`만 바꿔 쓰는 걸 추천(전 밀도 대응).
