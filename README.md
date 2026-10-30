# 내비서 (내 비서 + 내비게이션) — 차 한 대, 세 개의 방

> 기어·내비 목적지·시각으로 지금 차가 **주차 중 사무실 / 업무 운전 / 퇴근길** 중 어느 방인지 판단하고, 운전 중 걸려 온 전화는 AI 비서가 그 방에 맞는 말로 대신 받아 요약해 두는 Pleos Connect 앱.

## 무엇을 하나
| 방 | 판정 근거 (Pleos SDK) | 차 화면 | 걸려 온 전화 |
|---|---|---|---|
| 주차 중 사무실 (PARK) | Vehicle Gear = P, UxRestrictions 해제 | 받아 둔 전화 목록, 보내기 전 확인 문자 | "잠시 자리를 비우셨습니다" |
| 업무 운전 (DRIVE_WORK) | Gear = D, NaviHelper 목적지가 집이 아님, 업무 시간 | 상태 한 줄만, 새 전화는 음성(TTS)으로 | "운전 중이라 정차 후 연락드리겠습니다" |
| 퇴근길 (DRIVE_REST) | 목적지가 집이거나 업무 종료 후 | 도착 예정·가족 전화만 | "오늘 업무는 마치셨습니다" |

- AI 비서는 첫 마디에 "○○님의 AI 비서 △△입니다. 통화 내용은 요약해서 전해 드립니다"라고 밝힌다(이름은 사용자 설정).
- 약속 확정·금액·위치는 말하지 않는다.

### 방 밖의 기능
| 기능 | 언제 | 쓰는 SDK |
|---|---|---|
| 다음 일정 출발 | 정차 화면 일정 카드의 "출발" → 그 장소로 경로 | NaviHelper `requestRoute` |
| 휴식 제안 | 연속 운전이 설정값(1·60·120분) 이상이면 음성 1회 + "가까운 휴게소 들르기" 버튼 → 앞쪽 휴게소를 첫 경유지로 | NaviHelper `addWaypoint`, TextToSpeech |
| 집 문턱 | 퇴근길·목적지 집·도착 5분 전 "오늘 일은 여기까지예요" 1회 | NaviHelper `remainTime`, TextToSpeech |
| 오늘 안전 요약 | 정차 화면 "오늘 AI가 대신 받은 전화 N통" | — |
| 동승자 모드 | 켜면 부재중 낭독·집 문턱 음성을 끔(휴식 제안은 유지) | — |

- 휴게소 좌표는 시연용 경부선 4곳 목록이다(NaviHelper에 장소 검색이 없음).
- 부팅 직후 내비를 한 번도 열지 않았으면 첫 경로 요청이 무시될 수 있다. 앱은 4초 뒤 1회 다시 요청하고, 그래도 안 되면 음성으로 알린다. 시연 전에 내비를 한 번 열어 두면 확실하다.

## 개인정보 보관
- 음성은 서버에 저장하지 않는다. 통화 서비스가 보낸 원문 기록은 7일 뒤 비우고, 요약 카드는 30일 뒤 삭제한다(`server` 예약 작업, 매일 03시).
- 데모 관리 페이지(`/admin`)는 비밀번호로 막혀 있고, 가짜 수신·데모 일정만 넣는다.

## 구성
```
car-app/   Android(Kotlin, Compose) Pleos Connect 앱 — 방 판정(RoomStateMachine), 포그라운드 서비스, 서버 연동, 음성 알림
server/    Cloudflare Workers + D1 — 차 방 상태 저장, 통화 직전 대본 제공(ClawOps callContextUrl), 부재중 목록
spike/     SDK 동작 확인용 실험 앱(심사 대상 아님)
apk/       제출용 release APK
```

## 실행 방법
1. Pleos Connect SDK 에뮬레이터(API 34)를 설치하고 실행한다.
2. APK 설치: `adb install -r -g apk/<팀명>_<앱이름>_release.apk`
3. 차 화면 하단 앱 목록(⊞)에서 **내비서**를 눌러 연다. (`adb shell am start`로 열면 앱 창이 아닌 위치에 놓일 수 있다. 명령으로 열 땐 `adb shell monkey -p ztw.carbiseo -c android.intent.category.LAUNCHER 1`.)
   앱을 열면 상단 알림에 "내비서 실행 중 — 방: ○○"이 뜬다. 내비를 앞에 띄워도 방 판정은 계속된다(포그라운드 서비스).
4. 방 바꿔 보기: `adb forward tcp:8088 tcp:8088` 후 브라우저로 `http://127.0.0.1:8088/`(Virtual Console)을 열어 둔 채(창을 닫으면 차량 신호가 꺼진다) 기어를 P ↔ D로 바꾸고, 내비에서 목적지를 집/다른 곳으로 설정한다.
5. 로그 확인: `adb logcat -s ROOM` — 방 변경, 서버 업로드, 부재중 수신, TTS 출력이 찍힌다.
6. 차 상태 반응 시연(디버그 빌드): `adb shell am broadcast -a ztw.carbiseo.ALERT --es v OVERSPEED` (CAUTION·NONE도 가능, NONE은 강제 해제) — 화면 테두리·상단 문구가 뜨고, 경고 중 새 부재중 음성은 해제 후 1건만 읽는다.

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
| 단위 테스트 | `car-app` 48개(방 판정·부재중·주행 기능), `server` 9개(대본·서명·데모·보관 정책, `npm test`) |

## APK 위치
`apk/<팀명>_<앱이름>_release.apk` (release 빌드)
