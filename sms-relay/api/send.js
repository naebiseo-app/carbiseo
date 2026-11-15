// 문자 중계(Vercel 서울 icn1): Workers는 국외 IP라 ClawOps가 403 -> 이 함수가 대신 보낸다.
// 맡은 일: 서버(server/src/index.ts relaySend)가 보낸 {to, body}를 비밀 헤더로 인증하고 ClawOps 문자 API로 넘긴다.
// 맡지 않는 일: 하루 상한·금칙어 검사(서버가 /car/send에서 한다). 이 함수는 형식(번호 10~11자리, 내용 1~1000자)만 확인한다.
// 응답: ClawOps 응답을 상태 코드째 그대로 돌려주되, 본문 속 수신번호는 끝 4자리만 남기고 가린다.
// 환경 변수: RELAY_SECRET, CLAWOPS_ACCOUNT_ID, CLAWOPS_API_KEY, CLAWOPS_NUMBER(README '실행 방법 4. 문자 중계').
// 먼저 읽을 곳: 아래 module.exports 핸들러.

// 한글 등 ASCII 밖은 2, 그 밖은 1로 세어 90 이하면 sms, 넘으면 lms
const msgType = (b) => ([...b].reduce((n, c) => n + (c.charCodeAt(0) < 128 ? 1 : 2), 0) <= 90 ? "sms" : "lms");

module.exports = async (req, res) => {
  if (req.method !== "POST") return res.status(405).json({ error: "method_not_allowed" });
  if (!process.env.RELAY_SECRET || req.headers["x-relay-secret"] !== process.env.RELAY_SECRET)
    return res.status(401).json({ error: "unauthorized" });
  const { to, body } = req.body || {};
  if (typeof to !== "string" || !/^\d{10,11}$/.test(String(to)) || typeof body !== "string" || body.length < 1 || body.length > 1000)
    return res.status(400).json({ error: "bad_request" });
  let r;
  try {
    r = await fetch(`https://api.claw-ops.com/v1/accounts/${process.env.CLAWOPS_ACCOUNT_ID}/messages`, {
      method: "POST",
      headers: { Authorization: `Bearer ${process.env.CLAWOPS_API_KEY}`, "content-type": "application/json" },
      body: JSON.stringify({ From: process.env.CLAWOPS_NUMBER, To: to, Body: body, Type: msgType(body) }),
    });
  } catch {
    return res.status(502).json({ error: "relay_failed" });
  }
  const masked = to.replace(/\d(?=\d{4})/g, "*");
  const text = (await r.text()).split(to).join(masked);
  res.status(r.status).setHeader("content-type", r.headers.get("content-type") || "application/json").send(text);
};
