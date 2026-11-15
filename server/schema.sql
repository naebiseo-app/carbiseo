-- D1 스키마(최초 1회 적용, README '실행 방법 3. 서버'). 테이블은 둘뿐이다.
--
-- kv: 키 하나에 JSON 한 값. 쓰는 키(server/src): car_context·settings·schedule·contacts·parked·google·stt_dict·
--   family_sent_day·chat_count·owner_as_guest·keep_recordings·demo_offset. 보관 정책(7일·30일)은 kv에 적용되지 않는다.
CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT, updated_at INTEGER);
-- inbox: 차 화면의 카드와 서버가 다루는 모든 기록. kind로 종류를 나눈다:
--   call(통화 카드)·ring(받은 전화 알림)·event(웹훅 원문)·task(주인 통화의 할 일)·sent(보낸 문자·메일·가족 문자)·
--   reserve(AI 예약·주차 문의 전화)·notif(폰 알림)·reply(차 음성 답장 대기열)·hint·replay(재현 통화)
-- 설계서 ③ 컬럼 + raw(받은 본문 그대로, 7일 뒤 NULL) + meta(JSON: content-type, 서명 검증 결과 등). 행은 30일 뒤 삭제된다(retention).
CREATE TABLE IF NOT EXISTS inbox (
  id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, call_id TEXT, message_id TEXT,
  from_number TEXT, from_label TEXT, room TEXT, summary TEXT, callback_at TEXT,
  urgent INTEGER DEFAULT 0, status TEXT, created_at INTEGER,
  raw TEXT, meta TEXT
);
