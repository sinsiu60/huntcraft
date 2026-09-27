# HuntCraft

마인크래프트 소울류 보스전 모드 — 태도 한 자루로, 오작동한 거대 수호 골렘 **프루니스**를 쓰러뜨리는 1:1 전투.

- 60Hz 판정 (본에 붙은 캡슐 · OBB, 스윕 판정), 구르기 무적 · 가드 · 패링 · 치명 일격 · 강인도 · 자세 게이지
- 락온 · 숄더 카메라 · 소울류 HUD (체력 · 스태미나 · 보스 체력바) · 체크포인트 (모닥불) · 안개 문
- 보스 프루니스: 첫 등장 연출 (전원이 꺼진 채 앉아 있다가 깨어남), 근거리 · 원거리 · 잡기 · 밀어내기 패턴, 2페이즈 (분노) · 처치 연출
- 근접 사각지대 대응: 근접 보조 판정 · 근접 압박 게이지 · 밀어내기 패턴 4종 · 재배치 패턴 4종 (백스텝 점프 · 사이드 스텝 · 관통 돌진 · 도약 후 내려찍기)
  - 재배치: 밀어내기와 번갈아 나옴, 도약 경로의 블록 · 전장 경계를 미리 검사 (막히면 다른 방향 · 다른 패턴), 후속 공격은 확률 분기 (같은 연계 연속 금지)
- 사운드 레이어 (예고 → 휘두름 → 쾅 → 여운), 경고음으로 공격 종류 구분 (일반 / 밀어내기 / 가드 불가 / 재배치), 더킹
- 모든 수치는 설정 파일 (`config/huntcraft/*.json`) 과 데이터 (`huntcraft/defs/**`) 로 조정

## 필요한 것

| | 버전 |
|---|---|
| Minecraft | 26.2 |
| Java | 25 이상 |
| Fabric Loader | 0.19.5 이상 |
| Fabric API | 0.160.0+26.2 |
| GeckoLib | 5.5.5 이상 (Fabric 26.2) |
| PlayerAnimationLib | 1.2.6+mc.26.2 (권장) |

## 설치

1. Fabric Loader 설치 후 `mods` 폴더에 Fabric API · GeckoLib · (권장) PlayerAnimationLib 과 `huntcraft-<버전>.jar` 를 넣습니다.
2. 월드에 들어가 관리자 권한으로 `/huntcraft arena build` → 전장 (부서진 마을) 이 만들어집니다.
3. `/huntcraft arena enter` 로 체크포인트에 이동, 안개 문으로 들어가 보스에게 다가가면 전투가 시작됩니다.

## 조작 (기본값 — 설정 › 조작에서 변경)

| 키 | 동작 |
|---|---|
| 왼쪽 클릭 | 공격 (길게 = 차지) |
| 오른쪽 클릭 | 가드 |
| F | 패링 |
| Space | 구르기 (방향키와 함께) |
| C | 점프 |
| Q / 휠 클릭 | 락온 |
| R | 아이템 사용 (회복 물약) · ↑ ↓ 아이템 선택 |
| E | 상호작용 (모닥불 · 안개 문) |
| F7 / F8 / F9 | 판정 도형 · 디버그 정보 · 설정 다시 읽기 |

## 명령 (관리자)

- `/huntcraft arena build | enter | boss | info | reset` — 전장 만들기 · 체크포인트로 이동 · 보스 소환 · 정보 · 필드 보스 초기화
- `/huntcraft monster spawn | act | hp | ai | clear` — 몬스터 시험 · 튜닝
- `/huntcraft coverage [track | select | angles | inner | waves | rage | toggle <패턴> | off]` — 보스 사각지대 커버리지 맵 (디버그)

## 보스 음악

배포판 jar 에는 보스 음악이 들어 있지 않습니다 (저작권). 곡을 가지고 있다면 리소스팩으로 넣으면 재생됩니다:

```
<리소스팩>/assets/huntcraft/sounds/music/prunis_intro.ogg    (깨어날 때 한 번)
<리소스팩>/assets/huntcraft/sounds/music/prunis_phase1.ogg   (1페이즈 반복)
<리소스팩>/assets/huntcraft/sounds/music/prunis_phase2.ogg   (2페이즈 반복)
<리소스팩>/assets/huntcraft/sounds/music/prunis_ending.ogg   (처치 — 천천히 사라짐)
```

음량 · 페이드는 `config/huntcraft/client.json` 의 `music` 에서 조정합니다. 파일이 없으면 음악 없이 그대로 동작합니다.
효과음도 같은 방식으로 `assets/huntcraft/sounds/...` 에 파일을 넣고 `sounds.json` 을 덮어쓰면 바꿀 수 있습니다 (지금은 바닐라 소리로 자리만 채움).

## 빌드

```bash
./gradlew build            # build/libs/huntcraft-<버전>.jar
./gradlew build -PnoMusic  # 음악 파일을 뺀 배포판
./gradlew runClientGameTest -Ponly=<시험 이름>   # 클라이언트 게임 시험 (예: Phase8ClientTest)
```

## 라이선스

All Rights Reserved.
