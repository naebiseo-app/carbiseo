CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT, updated_at INTEGER);
-- inbox: 설계서 ③ 컬럼 + raw(받은 본문 그대로) + meta(JSON: content-type, 서명 검증 결과)
CREATE TABLE IF NOT EXISTS inbox (
  id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, call_id TEXT, message_id TEXT,
  from_number TEXT, from_label TEXT, room TEXT, summary TEXT, callback_at TEXT,
  urgent INTEGER DEFAULT 0, status TEXT, created_at INTEGER,
  raw TEXT, meta TEXT
);
