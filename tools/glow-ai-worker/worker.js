/**
 * Glow Studio — AI proxy (Cloudflare Worker)
 * ------------------------------------------------------------
 * Holds your API key server-side so the browser app never sees it.
 * Supports Google Gemini (image editing) and OpenAI (gpt-image-1).
 *
 * DEPLOY (summary — full steps in README.md):
 *   1. npm i -g wrangler && wrangler login
 *   2. In this folder: wrangler deploy
 *   3. Set your key as a secret (NOT in code):
 *        wrangler secret put GEMINI_API_KEY      # or OPENAI_API_KEY
 *   4. (optional) set vars in wrangler.toml: PROVIDER, GEMINI_MODEL, ALLOW_ORIGIN
 *   5. Copy the deployed URL into Glow Studio → tab "AI" → ตั้งค่า
 *
 * Request  (POST, JSON): { op, image, prompt?, mask? }
 *   op     : "edit" | "remove_bg" | "enhance" | "remove_object"
 *   image  : data URL or bare base64 (PNG/JPG)
 *   mask   : (remove_object) data URL; painted area = region to remove
 *   prompt : (edit) free-text instruction
 * Response (JSON): { image: "data:image/png;base64,..." }  or  { error }
 */

const PROMPTS = {
  remove_bg: "Remove the background completely and make it transparent. Keep the main foreground subject intact with clean, accurate edges. Return only the subject on a transparent background.",
  enhance: "Enhance this photo to high quality: increase sharpness and real detail, denoise, improve lighting and color, keep it natural and photorealistic. Do not change the content, identity, or composition.",
  remove_object: "Remove the content in the masked (painted) region and realistically fill it in with a plausible background that matches the surroundings. Keep everything else unchanged.",
};

export default {
  async fetch(request, env) {
    const origin = env.ALLOW_ORIGIN || "*";
    const cors = {
      "Access-Control-Allow-Origin": origin,
      "Access-Control-Allow-Methods": "POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type",
      "Access-Control-Max-Age": "86400",
    };
    if (request.method === "OPTIONS") return new Response(null, { headers: cors });
    if (request.method !== "POST")
      return json({ error: "Use POST" }, 405, cors);

    let body;
    try { body = await request.json(); }
    catch { return json({ error: "Invalid JSON body" }, 400, cors); }

    const { op = "edit", image, mask } = body;
    let prompt = body.prompt;
    if (!image) return json({ error: "Missing 'image'" }, 400, cors);
    if (!prompt && PROMPTS[op]) prompt = PROMPTS[op];
    if (!prompt) prompt = "Edit this image as requested.";

    const provider = (env.PROVIDER || (env.OPENAI_API_KEY ? "openai" : "gemini")).toLowerCase();

    try {
      let out;
      if (provider === "openai") out = await callOpenAI(env, op, image, mask, prompt);
      else out = await callGemini(env, op, image, mask, prompt);
      return json({ image: out, provider }, 200, cors);
    } catch (e) {
      return json({ error: String(e && e.message || e) }, 502, cors);
    }
  },
};

/* ---------------- helpers ---------------- */
function json(obj, status, cors) {
  return new Response(JSON.stringify(obj), {
    status, headers: { "Content-Type": "application/json", ...cors },
  });
}
function splitDataURL(d) {
  const m = /^data:([^;]+);base64,(.*)$/s.exec(d);
  if (m) return { mime: m[1], b64: m[2] };
  return { mime: "image/png", b64: d }; // assume bare base64
}

/* ---------------- Gemini ----------------
 * Uses generateContent with an inline image + text; returns the first
 * inlineData image part. Model is configurable because Google's image
 * model names change — set GEMINI_MODEL in wrangler.toml if needed. */
async function callGemini(env, op, image, mask, prompt) {
  const key = env.GEMINI_API_KEY;
  if (!key) throw new Error("GEMINI_API_KEY not set (wrangler secret put GEMINI_API_KEY)");
  const model = env.GEMINI_MODEL || "gemini-2.5-flash-image";
  const img = splitDataURL(image);
  const parts = [{ text: prompt }, { inline_data: { mime_type: img.mime, data: img.b64 } }];
  if (mask) {
    const m = splitDataURL(mask);
    parts.push({ text: "The following image is the mask; the white/painted area marks the region to edit." });
    parts.push({ inline_data: { mime_type: m.mime, data: m.b64 } });
  }
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${key}`;
  const r = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ contents: [{ parts }] }),
  });
  const data = await r.json();
  if (!r.ok) throw new Error(data?.error?.message || `Gemini HTTP ${r.status}`);
  const outParts = data?.candidates?.[0]?.content?.parts || [];
  const imgPart = outParts.find(p => p.inline_data || p.inlineData);
  const inl = imgPart && (imgPart.inline_data || imgPart.inlineData);
  if (!inl) throw new Error("Gemini returned no image (check model name / quota)");
  return `data:${inl.mime_type || inl.mimeType || "image/png"};base64,${inl.data}`;
}

/* ---------------- OpenAI ----------------
 * Uses the images/edits endpoint (gpt-image-1) with multipart form. */
async function callOpenAI(env, op, image, mask, prompt) {
  const key = env.OPENAI_API_KEY;
  if (!key) throw new Error("OPENAI_API_KEY not set (wrangler secret put OPENAI_API_KEY)");
  const model = env.OPENAI_MODEL || "gpt-image-1";
  const img = splitDataURL(image);
  const form = new FormData();
  form.append("model", model);
  form.append("prompt", prompt);
  form.append("image", b64ToBlob(img.b64, img.mime), "image.png");
  if (mask) {
    const m = splitDataURL(mask);
    form.append("mask", b64ToBlob(m.b64, m.mime), "mask.png");
  }
  const r = await fetch("https://api.openai.com/v1/images/edits", {
    method: "POST",
    headers: { Authorization: `Bearer ${key}` },
    body: form,
  });
  const data = await r.json();
  if (!r.ok) throw new Error(data?.error?.message || `OpenAI HTTP ${r.status}`);
  const b64 = data?.data?.[0]?.b64_json;
  if (!b64) throw new Error("OpenAI returned no image");
  return `data:image/png;base64,${b64}`;
}

function b64ToBlob(b64, mime) {
  const bin = atob(b64);
  const arr = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
  return new Blob([arr], { type: mime || "image/png" });
}
