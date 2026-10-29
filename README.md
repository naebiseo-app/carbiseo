# 차량비서 (가칭) — 차 한 대, 세 개의 방

> 기어·내비 목적지·시각으로 지금 차가 **주차 중 사무실 / 업무 운전 / 퇴근길** 중 어느 방인지 판단하고, 운전 중 걸려 온 전화는 AI 비서가 그 방에 맞는 말로 대신 받아 요약해 두는 Pleos Connect 앱.

## 무엇을 하나
| 방 | 판정 근거 (Pleos SDK) | 차 화면 | 걸려 온 전화 |
|---|---|---|---|
| 주차 중 사무실 (PARK) | Vehicle Gear = P, UxRestrictions 해제 | 받아 둔 전화 목록, 보내기 전 확인 문자 | "잠시 자리를 비우셨습니다" |
| 업무 운전 (DRIVE_WORK) | Gear = D, NaviHelper 목적지가 집이 아님, 업무 시간 | 상태 한 줄만, 새 전화는 음성(TTS)으로 | "운전 중이라 정차 후 연락드리겠습니다" |
| 퇴근길 (DRIVE_REST) | 목적지가 집이거나 업무 종료 후 | 도착 예정·가족 전화만 | "오늘 업무는 마치셨습니다" |

- AI 비서는 첫 마디에 "○○님의 AI 비서 △△입니다. 통화 내용은 요약해서 전해 드립니다"라고 밝힌다(이름은 사용자 설정).
- 약속 확정·금액·위치는 말하지 않는다.

## 구성
```
car-app/   Android(Kotlin, Compose) Pleos Connect 앱 — 방 판정(RoomStateMachine), 포그라운드 서비스, 서버 연동, 음성 알림
server/    Cloudflare Workers + D1 — 차 방 상태 저장, 통화 직전 대본 제공(ClawOps callContextUrl), 부재중 목록
spike/     SDK 동작 확인용 실험 앱(심사 대상 아님)
apk/       제출용 release APK
```

## 실행 방법
1. Pleos Connect SDK 에뮬레이터(API 34)를 설치하고 실행한다.
2. APK 설치: `adb install apk/<팀명>_<앱이름>_release.apk`
3. 앱을 열면 상단 알림에 "차량비서 실행 중 — 방: ○○"이 뜬다. 내비를 앞에 띄워도 방 판정은 계속된다(포그라운드 서비스).
4. 방 바꿔 보기: Vehicle Emulator(Virtual Console)에서 기어를 P ↔ D로 바꾸고, 내비에서 목적지를 집/다른 곳으로 설정한다.
5. 로그 확인: `adb logcat -s ROOM` — 방 변경, 서버 업로드, 부재중 수신, TTS 출력이 찍힌다.

### 직접 빌드할 때
```
cd car-app
./gradlew --max-workers=2 :app:testDebugUnitTest :app:assembleRelease
```
- 저장소 루트에 `.env`를 두고 `CAR_TOKEN=`을 넣는다(`.env.example` 참고). 없으면 서버 연동만 꺼진 채로 빌드된다.

## 테스트 환경
| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect SDK Emulator, Android API 34 |
| 앱 | minSdk 28 / targetSdk 34, applicationId `ztw.carbiseo` |
| Pleos SDK | Vehicle 2.3.2, NaviHelper 3.0.5, TextToSpeech 3.3.1 |
| 권한 | CAR_DRIVE, NAVI_ROUTE, NAVI_ROUTE_SEARCH, POST_NOTIFICATIONS, INTERNET |
| 단위 테스트 | `car-app` 방 판정·부재중 알림 로직, `server` 대본 선택(`npm test`) |

## APK 위치
`apk/<팀명>_<앱이름>_release.apk` (release 빌드)
