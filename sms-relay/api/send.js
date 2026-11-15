// Workers는 국외 IP라 ClawOps가 403 -> 서울(icn1) 함수가 대신 보낸다.
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
