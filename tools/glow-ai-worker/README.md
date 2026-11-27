# Glow Studio — AI proxy (Cloudflare Worker)

แอป Glow Studio เรียก AI (ลบพื้นหลัง / HD / แก้รูปด้วยข้อความ) ผ่าน Worker ตัวนี้
เพื่อ **เก็บ API key ไว้ฝั่งเซิร์ฟเวอร์** เบราว์เซอร์จะไม่เห็น key เลย

รองรับ **Google Gemini** (ค่าเริ่มต้น) และ **OpenAI** (`gpt-image-1`)

---

## ติดตั้ง (ครั้งเดียว ~5 นาที)

ต้องมีบัญชี Cloudflare (ฟรี) และ Node.js

```bash
npm i -g wrangler          # ติดตั้งเครื่องมือ Cloudflare
wrangler login             # ล็อกอินบัญชี Cloudflare

cd tools/glow-ai-worker
wrangler deploy            # อัปโหลด Worker → ได้ URL กลับมา
```

### ใส่ API key (เป็น "secret" — ไม่อยู่ในโค้ด)

เลือกตามผู้ให้บริการที่จะใช้:

```bash
# ถ้าใช้ Gemini (ค่าเริ่มต้น)
wrangler secret put GEMINI_API_KEY
# วาง key ตอนมันถาม

# ถ้าใช้ OpenAI แทน
wrangler secret put OPENAI_API_KEY
```

ถ้าใช้ OpenAI ให้แก้ `PROVIDER = "openai"` ใน `wrangler.toml` แล้ว `wrangler deploy` อีกครั้ง

### เอา URL ไปใส่ในแอป

หลัง `wrangler deploy` จะได้ URL เช่น `https://glow-ai.<ชื่อคุณ>.workers.dev`
เปิด Glow Studio → แท็บ **AI** → วาง URL → กด 💾

เสร็จแล้ว! กด "ลบพื้นหลัง", "HD", หรือพิมพ์คำสั่งได้เลย

---

## ตั้งค่าเพิ่มเติม (`wrangler.toml`)

| ตัวแปร | ความหมาย |
|---|---|
| `PROVIDER` | `"gemini"` หรือ `"openai"` |
| `GEMINI_MODEL` | ชื่อโมเดล Gemini (เปลี่ยนได้ถ้า Google เปลี่ยนชื่อ) |
| `OPENAI_MODEL` | ชื่อโมเดล OpenAI (ค่าเริ่มต้น `gpt-image-1`) |
| `ALLOW_ORIGIN` | จำกัดโดเมนที่เรียกได้ เช่น `"https://annonymouse456.github.io"` (production ควรตั้ง ไม่ปล่อย `*`) |

> **หมายเหตุ:** ชื่อโมเดลรูปของ Google/OpenAI เปลี่ยนบ่อย ถ้าเรียกแล้ว error ว่าไม่พบโมเดล
> ให้แก้ `GEMINI_MODEL` / `OPENAI_MODEL` เป็นชื่อปัจจุบันจากเอกสารผู้ให้บริการ แล้ว deploy ใหม่

---

## ความปลอดภัย
- key อยู่เป็น **secret** บน Cloudflare เท่านั้น ไม่เคยถูกส่งไปเบราว์เซอร์
- ตั้ง `ALLOW_ORIGIN` เป็นโดเมนเว็บจริงของคุณ เพื่อกันคนอื่นแอบเรียก Worker (และเผาเครดิต)
- อยากกันเพิ่ม: เพิ่มการจำกัดจำนวนครั้ง (rate limit) หรือใส่รหัสลับเล็ก ๆ ใน header ได้ — บอกมาได้ เดี๋ยวเสริมให้

## ค่าใช้จ่าย
- Cloudflare Worker: ฟรีถึงวันละ 100,000 ครั้ง
- ค่า AI: จ่ายตามจริงให้ Google/OpenAI ต่อรูป (ขึ้นกับโมเดลและขนาดภาพ)
