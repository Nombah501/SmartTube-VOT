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
    const oauthButton = clientId
        ? `<a href="https://oauth.yandex.ru/authorize?response_type=token&client_id=${encodeURIComponent(clientId)}&redirect_uri=${encodeURIComponent(origin + "/cb")}&force_confirm=yes&state=${encodeURIComponent(code)}"><button>Войти через Яндекс</button></a>
<p style="margin-top:8px">после входа токен уйдёт на ТВ автоматически</p>
<p>— или —</p>`
        : `<p>1. Откройте <a href="https://oauth.yandex.ru/" target="_blank">oauth.yandex.ru</a> и войдите<br>2. Скопируйте OAuth-токен со страницы<br>3. Вставьте его ниже</p>`;
    return page(`
<h1>Вход для SmartTube</h1>
<p>Код с телевизора:</p><div class="code">${code}</div>
${oauthButton}
<textarea id="token" placeholder="OAuth-токен (необязательно, если вошли кнопкой)"></textarea>
<button id="submit">Отправить на телевизор</button>
<p id="msg"></p>
<script>
const CODE = ${JSON.stringify(code)};
document.getElementById("submit").onclick = async () => {
    const msg = document.getElementById("msg");
    const token = document.getElementById("token").value.trim();
    msg.className = ""; msg.textContent = "Отправляю...";
    const r = await fetch("/api/submit", {method: "POST",
        headers: {"content-type": "application/json"},
        body: JSON.stringify({code: CODE, token})});
    if (r.ok) { msg.className = "ok"; msg.textContent = "Готово! Смотрите телевизор."; }
    else { msg.className = "err"; msg.textContent = "Код не найден или истёк."; }
};
// OAuth implicit callback lands on /t/<code>#access_token=...
if (location.hash.includes("access_token")) {
    const p = new URLSearchParams(location.hash.slice(1));
    fetch("/api/submit", {method: "POST", headers: {"content-type": "application/json"},
        body: JSON.stringify({code: CODE, token: p.get("access_token")})})
        .then(r => { const msg = document.getElementById("msg");
            msg.className = r.ok ? "ok" : "err";
            msg.textContent = r.ok ? "Готово! Смотрите телевизор." : "Не удалось передать токен."; });
    history.replaceState(null, "", location.pathname);
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
