# 내비서 코드 구조

이 문서와 코드 주석의 `F6`·`H7`·`O4` 같은 표기는 개발 중 쓴 설계 항목 번호다(같은 번호는 같은 기능을 가리킨다). `11-06` 같은 날짜는 그 동작을 실측한 날이다.

## car-app (Android, Kotlin + Jetpack Compose)
경로: `car-app/app/src/main/java/ztw/carbiseo/`

| 파일 | 역할 |
|---|---|
| `RoomStateMachine.kt` | 순수 Kotlin 방 판정기. 기어·주행 제한·목적지·시각으로 PARK / DRIVE_WORK / DRIVE_REST를 정하고 디바운스·유예를 건다. 업무 시간 판정(`offDuty`)도 여기. |
| `CarAdapters.kt` | Pleos Vehicle·NaviHelper·AAOS(UxRestrictions, 속도) 신호를 받아 `RoomStateMachine.update`로 넘긴다. SDK 콜백은 전부 메인 스레드로 모은다. 집·회사 즐겨찾기, 최근 목적지도 읽는다. |
| `RoomService.kt` | 포그라운드 서비스. 판정기·어댑터·스피커를 묶고, 200ms 틱(경고·음성 보류·휴식/피로/졸음 확인/집 문턱 안내·지각 예측·예약 질문·먼저 말 걸기·출발 때 한 번 묻기·상태 하트비트)과 5초 폴링(inbox, 일정·착신 설정은 30초마다)을 돌린다. 음성 질문·대답(지각·회신·예약·휴식·근처 찾기·출발 "응/아니", [말하기]), 경로 요청·경유지 추가(휴게소·충전소·주유소·근처 가게), 내비 위 카드 띄우기, 재현 통화 재생도 여기. '먼저 말 걸기'를 끄면 출발·예약 질문도 하지 않는다. 디버그 빌드에서만 시연용 브로드캐스트를 등록한다. |
| `Server.kt` | 서버 HTTP 호출(블로킹, 백그라운드 스레드 전용): `/car/state`, `/car/inbox`(+ `/car/inbox/:id` 완료·내일 아침에), `/car/send`, `/car/mail`, `/car/reply`, `/car/settings`, `/car/family-eta`, `/car/schedule`, `/car/config`, `/car/chat`(말동무), `/car/nearby`, `/car/parking`, `/car/parked`, `/car/reserve`(예약·주차 문의 전화). |
| `Inbox.kt` | inbox 행 모델과 순수 함수: 새 전화 → 낭독 문장, 퇴근 뒤 받은 전화 넘기기(H7), 할 일 → 문자 초안, 서버에서 지워진 카드 정리. |
| `Drive.kt` | 주행 보조 순수 함수: 집 문턱(`homeSoon`), 휴식 타이머(`restDue`), 앞쪽 휴게소(`nearestRestStop`, `assets/reststops.tsv` 졸음쉼터 253곳·휴게소 92곳), 피로 모드 판정(`fatigued`)·졸음 확인 문장, 지각 예측(`lateBy`), 예약 대상(`reservable`)·도착 시각(`arriveAt`, 5분 단위 올림), 먼저 말 걸기(`offerDue`·`mealOffer`·`chargeOffer`), 출발 때 묻기(`departOffer`, 에너지 20% 이하면 충전소·주유소 경유 문구), 주행 제한 글자 수(`UxLimits`), [말하기] 의도(`carIntent`), 짧은 정차 판정, 트립 시간 차이(`TripDelta`), 동승자 감지 엣지(`SeatEdge`), 시각 시뮬용 `ShiftClock`. |
| `Alert.kt` | 과속·주의구간 판정(`alertFor`, 해제 히스테리시스 `AlertLatch`)과 조용히 할 때 음성을 모아 두는 `SpeechHold`. |
| `Speaker.kt` | Pleos TextToSpeech(ON_DEVICE, ko_KR)로 읽고, 실패하면 Android TTS로 대체. 다 읽은 뒤 할 일(`then`)을 받아 음성 대답 듣기로 잇는다. |
| `Notif.kt` | 폰 알림 순수 함수: 광고 판정, 방별로 지금 알릴 알림(`notifNow`), 묶음 문장, 음성 의도 키워드(`voiceCmd`), 제안→낭독→답장 되읽기→확인 대화(`NotifTalk`). |
| `Voice.kt` | 한 마디 듣기. Pleos SpeechToText(ON_DEVICE) → 안 되면 Android SpeechRecognizer → 둘 다 없으면 화면 버튼만. 10초 안에 못 들으면 '못 들음'. |
| `Screens.kt` | Compose 화면. 운전 화면(링), 정차 화면의 카드([완료]·[내일 아침에])·일정·문자 보내기 시트·[말하기], 휴식 버튼, 첫 실행 3단계, 설정(호칭·응대 방식 2택·휴식 기준·업무 끝 시각·바로 연결 번호·안내 멘트·배경). 상태는 `RoomService` companion에서 읽는다. |
| `Replay.kt` | 재현 통화 대본 해석과 진행(순수 함수): 상대 대사·도움 카드(추천 답변·사실 정정·빈 시간)를 시각에 맞춰 내보내고, 정차면 카드·운전 중이면 귓속말 음성. |
| `Background.kt` | 사용자 배경 이미지 정책(정차 중에만 움직임)과 이름 검증. |
| `DriveStrip.kt` | 내비 위 작은 카드(방 상태·급한 건 수·[말하기]). 운전 방이고 앱이 앞에 없고 다른 앱 위 표시 권한이 있을 때만 띄운다. 끌어 옮기면 화면 안으로 묶고(`clampCard`) 위치를 기억한다. |
| `Gleo.kt` | Gleo 함수 이름(`assets/schema.json`) → 음성 의도(`gleoIntent`)와 Gleo Action 수신(`GleoReceiver`). 답은 앱이 직접 읽고 Gleo에는 결과만 알린다. |
| `Wake.kt` | 이름 호출. 기기 안 sherpa-onnx 스트리밍 인식으로 "내비서"("네비서" 포함)를 찾고 뒤 문장을 넘긴다(`wakeSplit`). 이름이 없는 문장은 버리고 내보내지 않는다. |
| `MainActivity.kt` | 화면 진입점(서비스 시작 + `App()`)과 부팅·업데이트 뒤 서비스를 띄우는 `BootReceiver`. |
| `Log.kt` | 로그 감싸개. 디버그 빌드에서만 logcat에 쓰고 release에서는 아무것도 남기지 않는다. |

테스트: `car-app/app/src/test/java/ztw/carbiseo/` — 판정기·inbox·주행·경고·도착 예정·배경·폰 알림·재현 통화·내비 위 카드 위치 순수 함수(176개).

## server (Cloudflare Workers + D1)
| 파일 | 역할 |
|---|---|
| `src/index.ts` | 라우터. 차 API(`/car/*`, Bearer `CAR_TOKEN`), 폰 API(`/phone/notify`, `/phone/replies`, `/phone/contacts`, `/phone/parked`, `/phone/parked/done`, `/phone/google` — 차와 같은 토큰), 통화 엔진 API(`/clawops/call-context/<비밀 경로>`, `/clawops/status/<비밀 경로>` 통화 종료 → 받아쓰기 요청, `/clawops/events` 서명 검증, `/mcp/<비밀 경로>` 통화 엔진 에이전트의 MCP 연결용 응답 — 도구는 없다), 관리 페이지(`/admin/*`, Basic 인증, 다른 출처의 POST 거절), 문자 중계 호출(`relaySend`), 매일 보관 정책(`scheduled`). 통화 요약 때 연락처·일정 이름을 참고 이름 목록으로 붙이고 결과를 다시 맞춘다(`namesFrom`·`fixNames`). 하루(KST) 상한: 문자 20건(데모 번호 제외)·말동무 200건·예약·주차 문의 전화 5건. |
| `src/guard.ts` | AI 문장 가드: 요약·할 일은 120자·금칙어·번호 검사 후 실패하면 고정 문구, 사용자가 확인한 본문은 금칙어만 막는다. |
| `src/script.ts` | 응대 대본 생성. 방·도착 예정·동승자·다음 일정·응대 방식(AI / 한 마디 안내)으로 시스템 프롬프트를 만들고(첫 마디에 AI·기록 고지, 이름·용건 되읽고 "맞으실까요?" 한 번, 회신 날짜 약속 금지, 운전 시간은 상대에게 말하지 않음), 차주 통화 대본(`ownerScript`, 운전 2시간 넘으면 짧게), 예약 전화(`reserveScript`)·주차 문의 전화(`parkingScript`, 자리·주차비) 대본, 급함 판정(`isUrgent`), 오래된 차 상태 무시(`freshCtx`, 10분). |
| `src/admin.ts` | 데모 관리 페이지와 순수 함수: 가짜 수신, 시험 알림 폼·보낼 답장 목록, 데모 메일함, 데모 일정, 재현 통화 대본(`replayScript`), 빈 시간 계산(`freeSlots`). |
| `src/calendar.ts` | 구글 캘린더 연결(인증 코드 → 토큰, 끊기·폐기), 5분 넘게 지났으면 다시 가져오기(`syncIfStale`), 일정 장소 이름 → 카카오 좌표(`placeCoord`). |
| `src/parking.ts` | 공영주차장 빈자리: `parking_lots.json`에서 1km 안 최대 3곳(`nearbyLots`) + 공공데이터 실시간 값 합치기. 위치는 저장·로그하지 않는다. |
| `src/sttdict.ts` | 통화 서비스 받아쓰기 낱말 사전 동기화: 연락처·일정 이름과 업무 낱말(최대 80개)을 올린다(`hotwords`, `syncDict`). |
| `src/notif.ts` | 폰 알림·답장 입력 검증(`parseNotify`, `parseReply`)과 답장 대기열 꺼내기 SQL. 알림 본문은 로그에 남기지 않는다. |
| `schema.sql` | D1 테이블 `kv`(car_context·settings·schedule)와 `inbox`(call·ring·task·sent·hint 등). |
| `test/*.test.ts` | `node --test`로 도는 단위 테스트. |

## phone-app (안드로이드 폰 '내비서 연결', Kotlin)
| 파일 | 역할 |
|---|---|
| `Listener.kt` | NotificationListenerService. 메시지형 알림(카카오톡·문자 앱, 메시지 분류·메시지 스타일)만 `/phone/notify`로 넘기고, 15초마다 `/phone/replies`를 받아 그 알림의 답장 버튼(RemoteInput)으로 보낸다. |
| `Relay.kt` | 메시지 알림 거르기(`toMsg`, 순수 함수)와 서버 호출. 토큰은 차 앱과 같은 `CAR_TOKEN`(빌드 때 주입). |
| `App.kt`·`Model.kt` | 폰 화면(첫 실행·홈·기록·설정)과 앱이 한 일 로컬 기록·화면 로직 순수 함수. 첫 실행은 통신사 고르기 → 착신 전환 걸기·확인 → 알림 접근 허용 순서다(건너뛸 수 있다). 연락처 올리기·지우기, 구글 캘린더 연결, 하차 카드 보기도 여기서 한다. |
| `src/debug/TestSender.kt` | 디버그 전용 시험 메신저: 답장 버튼 있는 메시지 알림을 띄우고 받은 답장을 알림으로 보여 준다. |

## sms-relay (Vercel, 서울 icn1)
| 파일 | 역할 |
|---|---|
| `api/send.js` | Workers가 보낸 `{to, body}`를 `x-relay-secret` 확인 뒤 통화 엔진 문자 API로 보낸다. 응답에서 받는 번호를 가린다. |
| `vercel.json` | 리전을 `icn1`로 고정(국외 IP 차단 회피). |

## 데이터 흐름
### 1. 차 상태 전송
1. `CarAdapters`가 SDK 콜백 → `RoomStateMachine.update` → 방이 바뀌면 `onChange`.
2. `RoomService`가 방·도착 예정 분·차량 맥락(기어·트립 분·SoC·깜빡이·동승자·경고등)을 `POST /car/state`로 올린다. 방이 바뀔 때, 도착 예정이 1분 이상 바뀔 때, 맥락이 바뀔 때(10초에 한 번까지), 그리고 5분마다 하트비트.
3. 서버는 `kv.car_context`에 저장한다. 10분 넘게 갱신이 없으면 방을 모르는 것으로 보고 중립 대본을 쓴다.

### 2. 통화 → inbox → 차 카드
1. 서비스 번호로 전화가 오면 통화 엔진이 통화 직전 `/clawops/call-context/…`를 호출한다.
2. 서버는 `car_context`·설정·일정으로 대본을 만들어 돌려주고, `ring` 행을 남긴다. 차주 번호면 업무 비서 대본. 바로 연결 번호면 통화 중에 `status='ringing'` 카드를 먼저 넣는다.
3. 통화가 끝나면 상태 콜백(`/clawops/status/…`)으로 받아쓰기를 요청하고, 받아쓰기 완료 웹훅(`/clawops/events`)이 오면 서버가 전화번호를 가린 글을 요약해 손님 통화는 차 카드로, 주인 통화는 할 일 카드와 되읽은 문자 자동 발송으로 처리한다. 요약이 저장되면 통화 엔진의 녹음을 지운다(요약 실패·`keep_recordings` 켜짐이면 남긴다). 폰 앱의 `/phone/contacts`는 문자 받는 사람의 번호를 찾는 연락처를 저장한다.
4. 차는 5초마다 `GET /car/inbox?since=`로 새 행을 받는다. 업무 운전이면 음성 한 줄, 퇴근길이면 급한 것·바로 연결만, 정차면 소리 없이 카드. 피로 모드에서도 막지 않는다(11-10 변경). 깜빡이·후진·큰 조향·경고 중이면 모아 두었다가 끝나면 한 문장.
5. 정차 화면의 [완료]·[내일 아침에]는 `POST /car/inbox/:id`로 서버 `status`에도 남는다. "내일 아침에" 카드는 다음 첫 업무 운전의 "어제 넘긴 전화 N건"에 포함된다.

### 3. 문자 보내기
1. 정차 화면에서 카드를 열고 "문자" → 초안 확인(필요하면 고치기) → 보내기. 또는 음성: 지각 예측("늦는다고 문자 보낼까요?" → 초안 낭독 → "응"), 정차 중 회신 제안("○○님께 '…'라고 문자 먼저 보내 드릴까요?" → "응"). 받는 번호를 모르거나 동승자 모드면 할 일 카드로 남긴다.
2. 차가 `POST /car/send`(`confirmed: true`)를 보낸다. 서버는 번호·본문을 검증하고 `sms-relay`에 `x-relay-secret`과 함께 넘긴다.
3. 중계가 통화 엔진 문자 API로 발송하고, 서버는 결과를 `sent` 행으로 남긴다. 차 카드에 "보냄/못 보냄"이 뜬다.

### 4. 폰 알림 읽어주기·음성 답장
1. 알림 입력: 폰 연결 앱이 메시지 알림을 `POST /phone/notify {app, sender, text, replyable, key}`로 올린다(시연은 관리 페이지 '시험 알림 보내기'가 같은 저장). 서버는 `inbox kind='notif'`로 저장한다(본문 로그 금지, 30일 보관·관리자 비우기 대상).
2. 차 판단: 5초 폴링으로 받은 알림을 `notifNow`가 거른다 — 업무 운전은 광고 외 전부, 업무 시간 밖은 즐겨찾기 보낸이만, 정차는 카드. 깜빡이·후진·큰 조향·경고·블루투스 통화 중이면 대기열에 두었다가 풀리면 묶어서 한 번 묻는다. 동승자 모드면 묻지 않고 대기열을 비운다. 세울 때는 이번에 와서 아직 안 알린 것을 묶어 묻는다(정차 요약).
3. 음성: "카톡 3건, 김민수님 외 2명. 읽어드릴까요?" → 듣기. "읽어줘"면 낭독(최대 5건), "답장, ○○라고 해 줘"면 "김민수님께 '○○' 보낼까요?" 되읽기 → "응/보내"면 `POST /car/reply`(`confirmed: true`), "나중에"·못 들음이면 카드로 남긴다. P·저속에서는 같은 대답을 큰 버튼으로도 받는다.
4. 답장 전달: 서버는 `kind='reply' status='queued'`로 쌓고, 폰 연결 앱이 `GET /phone/replies`로 가져가면 `delivered`로 바꾼다. 연결 앱은 그 알림의 답장 버튼으로 보낸다. 관리 페이지에는 '보낼 답장'으로 보인다(연결 앱이 없을 때 시연).
