/**
 * SmartTube-VOT auth relay (Cloudflare Worker).
 *
 * TV:  POST /api/start        -> {code, expiresAt}
 * TV:  GET  /api/poll/<code>  -> {status:"pending"} | {status:"ok", token}   (token is single-read)
 * Phone: GET /t/<code>        -> sign-in page (Yandex OAuth button and/or manual paste)
 * Phone: GET /cb#access_token -> JS parses the OAuth fragment, POSTs it to /api/submit
 * Phone: POST /api/submit     -> {code, token}
 *
 * Codes: 6 chars, unambiguous alphabet, TTL 10 min, token single-read then deleted.
 * Set var OAUTH_CLIENT_ID (+ OAUTH_REDIRECT_URI optional) in wrangler.toml to enable
 * the one-tap Yandex button; manual token paste works without it.
 */

const CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const CODE_LEN = 6;
const TTL_SECONDS = 600;

const PAGE_STYLE = `body{font-family:system-ui,sans-serif;background:#111;color:#eee;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0}
.card{background:#1c1c1e;border-radius:16px;padding:32px;max-width:420px;width:92%;box-sizing:border-box;text-align:center}
h1{font-size:22px;margin:0 0 12px}p{color:#aaa;font-size:15px;line-height:1.5}
.code{font-size:40px;font-weight:700;letter-spacing:6px;margin:18px 0;color:#fff}
button{background:#fc3f1d;color:#fff;border:0;border-radius:12px;padding:14px 20px;font-size:16px;width:100%;margin-top:12px}
textarea{width:100%;box-sizing:border-box;background:#2c2c2e;color:#eee;border:1px solid #444;border-radius:10px;padding:12px;font-size:14px;min-height:90px;margin-top:16px}
.step{margin:18px 0 6px;text-align:left}
.hint{color:#8e8e93;font-size:13px;text-align:left;margin:6px 0 0}
details{margin-top:10px}summary{color:#aaa;cursor:pointer}
.ok{color:#4cd964}.err{color:#ff453a}a{color:#fc3f1d}`;

function json(data, status = 200) {
    return new Response(JSON.stringify(data), {
        status,
        headers: {"content-type": "application/json"},
    });
}

function page(body) {
    return new Response(`<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>SmartTube VOT</title>
<style>${PAGE_STYLE}</style></head><body><div class="card">${body}</div></body></html>`, {
        headers: {"content-type": "text/html; charset=utf-8"},
    });
}

function genCode() {
    let code = "";
    for (let i = 0; i < CODE_LEN; i++) {
        code += CODE_ALPHABET[Math.floor(Math.random() * CODE_ALPHABET.length)];
    }
    return code;
}

async function handleStart(env) {
    const code = genCode();
    await env.AUTH.put("c:" + code, "pending", {expirationTtl: TTL_SECONDS});
    return json({code, expiresAt: Date.now() + TTL_SECONDS * 1000});
}

async function handlePoll(env, code) {
    if (!code || !/^[A-Z0-9]{4,10}$/.test(code)) {
        return json({status: "error"}, 400);
    }
    const value = await env.AUTH.get("c:" + code);
    if (value === null) {
        return json({status: "expired"});
    }
    if (value === "pending") {
        return json({status: "pending"});
    }
    await env.AUTH.delete("c:" + code); // single-read
    return json({status: "ok", token: value});
}

async function handleSubmit(request, env) {
    let body;
    try {
        body = await request.json();
    } catch (e) {
        return json({status: "error"}, 400);
    }
    const code = String(body.code || "").toUpperCase().trim();
    const token = String(body.token || "").trim();
    if (!/^[A-Z0-9]{4,10}$/.test(code) || token.length < 20) {
        return json({status: "error"}, 400);
    }
    const current = await env.AUTH.get("c:" + code);
    if (current === null) {
        return json({status: "expired"}, 410);
    }
    await env.AUTH.put("c:" + code, token, {expirationTtl: TTL_SECONDS});
    return json({status: "ok"});
}

function phonePage(code, origin, clientId) {
    // Debug-type OAuth apps redirect back to oauth.yandex.ru/verification_code
    // (custom redirect_uri is rejected), so the Yandex button opens a new tab where
    // the token is shown; the user copies it, comes back, and step 2 reads the
    // clipboard (with a manual fallback that is always in the DOM).
    const step1 = clientId
        ? `<div class="step"><b>1. Войдите через Яндекс</b></div>
<a href="https://oauth.yandex.ru/authorize?response_type=token&client_id=${encodeURIComponent(clientId)}" target="_blank" rel="noopener"><button>Открыть Яндекс и войти</button></a>
<p class="hint">Откроется новая вкладка. Нажмите «Скопировать» рядом с токеном — он нужен для шага 2.</p>`
        : `<div class="step"><b>1. Получите токен</b></div>
<a href="https://oauth.yandex.ru/" target="_blank" rel="noopener"><button>Открыть oauth.yandex.ru</button></a>
<p class="hint">Войдите и скопируйте OAuth-токен со страницы.</p>`;
    return page(`
<h1>Вход для SmartTube</h1>
<p>Код с телевизора: <span class="code">${code}</span></p>
${step1}
<div class="step"><b>2. Отправьте токен на телевизор</b></div>
<button id="paste">Вставить и отправить</button>
<details id="manual"><summary>Вставить код вручную</summary>
<textarea id="token" placeholder="Вставьте скопированный токен"></textarea>
<button id="submit">Отправить</button>
</details>
<p id="msg"></p>
<script>
const CODE = ${JSON.stringify(code)};
const msg = document.getElementById("msg");
const manual = document.getElementById("manual");

async function send(token) {
    if (!token || token.length < 20) {
        msg.className = "err"; msg.textContent = "Похоже, скопирован не токен. Вставьте вручную ниже.";
        manual.open = true;
        return;
    }
    msg.className = ""; msg.textContent = "Отправляю...";
    try {
        const r = await fetch("/api/submit", {method: "POST",
            headers: {"content-type": "application/json"},
            body: JSON.stringify({code: CODE, token})});
        if (r.ok) { msg.className = "ok"; msg.textContent = "Готово! Возвращайтесь к телевизору."; }
        else { msg.className = "err"; msg.textContent = "Код истёк — начните заново на телевизоре."; }
    } catch (e) { msg.className = "err"; msg.textContent = "Сеть недоступна."; }
}

document.getElementById("paste").onclick = async () => {
    msg.className = ""; msg.textContent = "Читаю буфер обмена...";
    try {
        const text = await navigator.clipboard.readText();
        await send(text.trim());
    } catch (e) {
        msg.textContent = "Не получилось прочитать буфер — вставьте вручную.";
        manual.open = true;
        document.getElementById("token").focus();
    }
};

document.getElementById("submit").onclick = () => send(document.getElementById("token").value.trim());

if (!navigator.clipboard || !navigator.clipboard.readText) {
    manual.open = true;
}

// OAuth implicit callback (future app types) lands here with #access_token
if (location.hash.includes("access_token")) {
    const p = new URLSearchParams(location.hash.slice(1));
    history.replaceState(null, "", location.pathname);
    send(p.get("access_token"));
}
</script>`);
}

function cbPage(code) {
    return page(`
<h1>Передаю токен…</h1>
<p id="msg">Почти готово</p>
<script>
const p = new URLSearchParams(location.hash.slice(1));
const token = p.get("access_token");
const code = p.get("state") || ${JSON.stringify(code)};
if (!token) { document.getElementById("msg").textContent = "Токен не найден в ссылке."; }
else fetch("/api/submit", {method: "POST", headers: {"content-type": "application/json"},
    body: JSON.stringify({code, token})})
    .then(r => { const m = document.getElementById("msg");
        m.className = r.ok ? "ok" : "err";
        m.textContent = r.ok ? "Готово! Смотрите телевизор." : "Код истёк — начните заново на ТВ."; });
</script>`);
}

export default {
    async fetch(request, env) {
        const url = new URL(request.url);
        const path = url.pathname;

        if (path === "/api/start" && request.method === "POST") {
            return handleStart(env);
        }
        if (path.startsWith("/api/poll/") && request.method === "GET") {
            return handlePoll(env, path.slice("/api/poll/".length).toUpperCase());
        }
        if (path === "/api/submit" && request.method === "POST") {
            return handleSubmit(request, env);
        }
        if (path.startsWith("/t/") && request.method === "GET") {
            const code = path.slice(3).toUpperCase();
            if (!/^[A-Z0-9]{4,10}$/.test(code)) {
                return page("<h1>Неверный код</h1>"), new Response("bad code", {status: 400});
            }
            return phonePage(code, url.origin, env.OAUTH_CLIENT_ID || "");
        }
        // Short URL form: https://host/AB12CD (same as /t/AB12CD)
        if (/^\/[A-Za-z0-9]{4,10}$/.test(path) && request.method === "GET") {
            const code = path.slice(1).toUpperCase();
            return phonePage(code, url.origin, env.OAUTH_CLIENT_ID || "");
        }
        if (path === "/cb" && request.method === "GET") {
            return cbPage("");
        }
        return new Response("SmartTube VOT auth relay", {status: 404});
    },
};
