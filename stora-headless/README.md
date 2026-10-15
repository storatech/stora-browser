# stora-headless

A lightweight **pure-Java headless browser** for scraping, with an **HTTP API**.

- Loads pages and runs their JavaScript (DOM, timers, XHR, `fetch`, cookies, redirects) like a browser, so JavaScript-rendered pages can be scraped.
- You open a link, run your own JavaScript in the page, get the result as JSON, run more JavaScript, click links or navigate, and close when done.
- Proxies per session (`http://user:pass@host:port`, HTTPS and SOCKS), set by the caller on every session.
- Pure Java: built on [HtmlUnit](https://www.htmlunit.org/). No Chrome, no native libraries, no external processes. One 11 MB jar; needs Java 17+.

It lives in the `stora-headless/` folder of `stora-browser`, next to the old jBrowserDriver code (used by `stora-skynet`), which it does not touch.

## Contents

- [Quick start](#quick-start)
- [Tester page](#tester-page): a browser-like page to try URLs, scripts and clicks
- [The session flow](#the-session-flow): open → run JavaScript → run more → close
- [Clicking and navigating](#clicking-and-navigating)
- [One-shot render](#one-shot-render)
- [Proxies](#proxies)
- [Example: Amazon product and search](#example-amazon-product-and-search)
- [Example: Coupang product](#example-coupang-product)
- [Speed: choosing what to load](#speed-choosing-what-to-load)
- [Using it from stora-scrapers (TypeScript)](#using-it-from-stora-scrapers-typescript)
- [HTTP API reference](#http-api-reference)
- [Java library](#java-library)
- [Command line](#command-line)
- [What the JavaScript engine supports](#what-the-javascript-engine-supports)
- [Limits](#limits)
- [Deploying](#deploying)
- [Development](#development)

## Quick start

```bash
cd stora-headless
mvn package                                   # runs the tests, builds target/stora-headless-0.1.0-SNAPSHOT-all.jar
java -jar target/stora-headless-0.1.0-SNAPSHOT-all.jar serve --port 8090 --token "$STORA_HEADLESS_TOKEN"
```

```bash
curl -s localhost:8090/health
# {"ok":true,"version":"0.1.0-SNAPSHOT","engine":"HtmlUnit 5.6.0","sessions":0,"maxSessions":8,"freeSlots":8,"heapUsedMb":7,"heapMaxMb":1024}
```

The server binds to `127.0.0.1` by default. Put it on another interface (`--host 0.0.0.0`) only behind a firewall and with a token: anyone who can reach it can make it fetch any URL.

## Tester page

Open **http://127.0.0.1:8090/tester** (or just `/`) in a desktop browser. It works like a small browser on top of the API:

- **Address bar** with back, forward and reload. Go opens a headless session and loads the URL; the next URLs load in the same session, with its cookies.
- **Page** tab shows the HTML the headless browser produced, rendered with the site's images and CSS. Clicking a link in it loads that link through the headless session. The preview never runs the page's scripts (sandboxed iframe); what you see is the DOM after the headless browser ran them.
- **HTML** and **Text** tabs show the source and the visible text.
- **Run JavaScript in the session**: run a script and see its JSON result. Tick "waits for navigation" for scripts that click or change `location`; "refresh view" shows changes the script made.
- **Click / wait**: click a CSS selector (with navigation waiting) or wait for one to appear.
- **Options**: server token (kept in the browser's localStorage), proxy, mode (full, lean, HTML only, no JavaScript), emulated browser, wait settings, Accept-Language and user agent.
- The status line shows the HTTP status, title, final URL, time, number of requests (and how many were blocked), data size and session id.

The page itself needs no token; the API calls it makes do, so it asks for the token only if the server has one.

## The session flow

A **session** is one isolated browser: its own cookies, proxy, user agent and headers. The normal flow is:

1. `POST /sessions` with a `url`: opens the link and returns a session `id`.
2. `POST /sessions/{id}/evaluate` with a `script`: runs JavaScript in the page and returns the result.
3. Run more scripts, click, or navigate as often as needed. The page and its JavaScript state stay alive between calls.
4. `DELETE /sessions/{id}` when done. Idle sessions are closed after 5 minutes anyway.

```bash
H='Authorization: Bearer '$STORA_HEADLESS_TOKEN

# 1. open a link
ID=$(curl -s localhost:8090/sessions -H "$H" -d '{
  "url": "https://example.com/",
  "proxy": "http://USER:PASS@HOST:PORT",
  "return": []
}' | jq -r .id)

# 2. run JavaScript, get the result
curl -s localhost:8090/sessions/$ID/evaluate -H "$H" -d '{
  "script": "({title: document.title, links: document.querySelectorAll(\"a\").length})"
}'
# {"ok":true,"result":{"title":"Example Domain","links":1},"url":"https://example.com/"}

# 3. run more JavaScript in the same page
curl -s localhost:8090/sessions/$ID/evaluate -H "$H" -d '{"script": "document.querySelector(\"p\").textContent"}'
# {"ok":true,"result":"This domain is for use in documentation examples without needing permission. …","url":"https://example.com/"}

# 4. close
curl -s -X DELETE localhost:8090/sessions/$ID -H "$H"
```

**Scripts** are JavaScript *expressions*. The value is converted with `JSON.stringify`, so return plain data (strings, numbers, arrays, objects), not DOM nodes. For several statements wrap them in a function: `(function () { var x = 1; return x + 1; })()`. If the expression is a Promise (for example from `fetch`), the server waits for it:

```json
{"script": "fetch('/api/price?id=1').then(function (r) { return r.json(); })"}
```

To pass values without string concatenation, send a `function` and `args`:

```json
{"function": "function (selector) { return document.querySelector(selector).textContent; }", "args": [".price"]}
```

## Clicking and navigating

To go to another page, either **click a link** or **change the location from JavaScript**. Both wait for the next page to load, then can wait for content and run a script on the new page in the same call (`then`).

```bash
# click the first element matching a CSS selector, wait for the new page, read it
curl -s localhost:8090/sessions/$ID/click -H "$H" -d '{
  "selector": "a.product-link",
  "waitForSelector": ".price",
  "then": "({name: document.querySelector(\"h1\").textContent, price: document.querySelector(\".price\").textContent})"
}'
# {"ok":true,"navigated":true,"url":"https://shop.example/product/1","status":200,"result":{"name":"Coffee","price":"12,000"},...}

# or navigate from JavaScript (location.href, element.click(), form.submit())
curl -s localhost:8090/sessions/$ID/evaluate -H "$H" -d '{
  "script": "location.href = document.querySelector(\"a.product-link\").href",
  "waitForNavigation": true,
  "then": "document.title"
}'
```

Without `waitForNavigation`, a script that navigates returns `"result": null`, because the old page and its JavaScript are gone. You can then call `/wait` and `/evaluate` on the new page. To load a URL directly, use `POST /sessions/{id}/goto`.

## One-shot render

For a single page, `POST /render` opens a session, loads the URL, waits, runs the script, returns and closes:

```bash
curl -s localhost:8090/render -H "$H" -d '{
  "url": "https://example.com/",
  "waitForSelector": "p",
  "script": "document.querySelector(\"p\").textContent",
  "return": ["html", "text"]
}'
```

## Proxies

Set `proxy` when opening a session (or in `/render`). Every request of that session goes through it; other sessions are unaffected.

| Format | Example |
|---|---|
| HTTP proxy | `http://HOST:PORT` |
| HTTP proxy with login | `http://USER:PASS@HOST:PORT` |
| HTTPS proxy | `https://USER:PASS@HOST:PORT` |
| SOCKS proxy (no login) | `socks5://HOST:PORT` |

**Percent-encode** special characters in the user name and password, as JavaScript's `encodeURIComponent` does (`#` → `%23`, `@` → `%40`, `!` stays). stora-scrapers keeps its proxies as `host:port:user:pass` lines in `src/services/proxy/proxies.ts`. Build the URL like this:

```ts
const [host, port, user, pass] = line.split(':')
const proxy = `http://${encodeURIComponent(user)}:${encodeURIComponent(pass)}@${host}:${port}`
```

Credentials are never logged by the server, and `ProxySpec.toString()` hides them.

## Example: Amazon product and search

Amazon puts product titles and prices in the server HTML, so the fastest way is to load only the document and run the extraction script on it (see [Speed](#speed-choosing-what-to-load) and [benchmark.md](benchmark.md)).

```bash
# Product: title and price
curl -s localhost:8090/render -H "$H" -d '{
  "url": "https://www.amazon.com/dp/B08L6RT1X9",
  "proxy": "'"$PROXY"'",
  "jsWaitMs": 0,
  "blockTypes": ["script", "stylesheet", "image", "font", "media"],
  "script": "(function () { var t = document.querySelector(\"#productTitle\"), p = document.querySelector(\".a-price .a-offscreen\"); return {title: t && t.textContent.trim(), price: p && p.textContent}; })()",
  "return": []
}'
# {"ok":true,"url":"https://www.amazon.com/dp/B08L6RT1X9","status":200,"result":{"title":"Apple iPhone 12 Pro (128GB, Gold) [Locked] + Carrier Subscription","price":"$861.05"},...}

# Search: first results with title and price
curl -s localhost:8090/render -H "$H" -d '{
  "url": "https://www.amazon.com/s?k=coffee+beans",
  "jsWaitMs": 0,
  "blockTypes": ["script", "stylesheet", "image", "font", "media"],
  "script": "Array.from(document.querySelectorAll(\"div[data-component-type=s-search-result]\")).slice(0, 5).map(function (el) { var t = el.querySelector(\"h2\"), p = el.querySelector(\".a-price .a-offscreen\"); return {asin: el.getAttribute(\"data-asin\"), title: t && t.textContent.trim(), price: p && p.textContent}; })",
  "return": []
}'
```

To click through from search results to a product, open a session and use `/click` with `"selector": "div[data-component-type=s-search-result] h2 a"` and a `then` script, as in the Coupang example below.

## Example: Coupang product

Goal: open Coupang, find a product with JavaScript, click it, wait for the product page and read its information, then close. The same steps work for any shop; the selectors and the extraction script are the site-specific parts.

Runnable versions: [`examples/coupang-product.sh`](examples/coupang-product.sh) (curl + jq) and [`examples/coupang-product.ts`](examples/coupang-product.ts) (TypeScript).

```bash
H='Authorization: Bearer '$STORA_HEADLESS_TOKEN

# 1. Open the home page through a proxy, with a Korean Accept-Language
ID=$(curl -s localhost:8090/sessions -H "$H" -d '{
  "url": "https://www.coupang.com/",
  "proxy": "'"$PROXY"'",
  "acceptLanguage": "ko-KR,ko;q=0.9,en-US;q=0.8",
  "return": []
}' | jq -r .id)

# 2. Find products with JavaScript
curl -s localhost:8090/sessions/$ID/evaluate -H "$H" -d '{
  "script": "Array.from(document.querySelectorAll(\"a[href*=\\\"/vp/products/\\\"]\")).map(function (a) { return a.href.split(\"?\")[0]; })"
}'

# 3. Click the first product, wait for the product page, read it
curl -s localhost:8090/sessions/$ID/click -H "$H" -d '{
  "selector": "a[href*=\"/vp/products/\"]",
  "jsWaitMs": 3000,
  "then": "(function () { function meta(p) { var m = document.querySelector(\"meta[property=\\\"\" + p + \"\\\"]\"); return m ? m.content : null; } return { url: location.href.split(\"?\")[0], title: document.title, blocked: !!document.getElementById(\"sec-if-cpt-container\") || document.title === \"Access Denied\", name: meta(\"og:title\"), image: meta(\"og:image\"), price: (document.querySelector(\".total-price strong, .final-price-amount\") || {}).textContent || null }; })()",
  "return": ["html"]
}'

# 4. Close
curl -s -X DELETE localhost:8090/sessions/$ID -H "$H"
```

**Tested 2026-10-16** with this build and the stora-scrapers proxy list:

| Step | Result |
|---|---|
| Open `https://www.coupang.com/` directly | 200, full home page rendered (33 requests, about 2 MB, 7–14 s) |
| Open it through scraper proxies | Some proxies get the full page (about 20–45 s); others get Coupang's "Sorry! Access denied" page, i.e. Coupang blocks those proxy IPs |
| Find products with JavaScript | Works: returns `/vp/products/...` links from the home page |
| Click the product, or `location.href = ...` | Works: navigates, waits, runs the `then` script on the new page |
| Product page content | **Not available**: Coupang answers product (and search) URLs with Akamai's bot challenge (`#sec-if-cpt-container`, "Powered and protected by Akamai") or a 403 "Access Denied", with or without a proxy. The `blocked` field above reports this |

stora-headless does not try to pass bot challenges or CAPTCHAs. For Coupang product data, use pages that are served normally, or an official channel such as the Coupang Partners or Open API.

## Speed: choosing what to load

Most of the time goes into downloading and running the site's own scripts. When the data is already in the server HTML (Amazon product and search pages, most shops' SEO pages), skip them:

| Mode | Settings | Amazon search page (see [benchmark.md](benchmark.md)) |
|---|---|---|
| Full | defaults (`jsWaitMs: 1000`) | 2.1 MB, median 15.8 s (32 s through a proxy) |
| Lean | `blockTypes: ["image","font","media","stylesheet"]`, `blockUrls` for ads and trackers | 1.5 MB, median 7.3 s (14 s through a proxy) |
| HTML-only | `jsWaitMs: 0`, `blockTypes: ["script","stylesheet","image","font","media"]` | 14 requests, 0.32 MB, median 3.1 s (3.6 s through a proxy) |

In HTML-only mode the page's external scripts are skipped, but your `script`/`then` still runs on the document. Use full or lean mode when the content is rendered by the page's JavaScript, and check which one a site needs with `"javascript": false` first.

## Using it from stora-scrapers (TypeScript)

A small client with axios, which stora-scrapers already uses (also in [`examples/coupang-product.ts`](examples/coupang-product.ts)):

```ts
import axios from 'axios'

const http = axios.create({
  baseURL: process.env.STORA_HEADLESS_URL ?? 'http://127.0.0.1:8090',
  headers: { Authorization: `Bearer ${process.env.STORA_HEADLESS_TOKEN ?? ''}` },
  timeout: 120_000
})

export class HeadlessSession {
  private constructor (readonly id: string) {}

  static async open (url: string, options: Record<string, unknown> = {}): Promise<HeadlessSession> {
    const { data } = await http.post('/sessions', { url, return: [], ...options })
    return new HeadlessSession(data.id)
  }

  async evaluate<T = unknown> (script: string): Promise<T> {
    const { data } = await http.post(`/sessions/${this.id}/evaluate`, { script })
    return data.result as T
  }

  async click<T = unknown> (selector: string, then?: string, options: Record<string, unknown> = {}): Promise<{ url: string, status: number, result: T }> {
    const { data } = await http.post(`/sessions/${this.id}/click`, { selector, then, ...options })
    return data
  }

  async goto (url: string, options: Record<string, unknown> = {}): Promise<{ url: string, status: number }> {
    const { data } = await http.post(`/sessions/${this.id}/goto`, { url, return: [], ...options })
    return data
  }

  async html (): Promise<string> {
    const { data } = await http.get(`/sessions/${this.id}/content`)
    return data.html
  }

  async close (): Promise<void> {
    await http.delete(`/sessions/${this.id}`).catch(() => undefined)
  }
}
```

```ts
const session = await HeadlessSession.open('https://www.coupang.com/', { proxy, acceptLanguage: 'ko-KR,ko;q=0.9' })
try {
  const links = await session.evaluate<string[]>("Array.from(document.querySelectorAll('a[href*=\"/vp/products/\"]')).map(a => a.href)")
  const page = await session.click('a[href*="/vp/products/"]', 'document.title', { jsWaitMs: 3000 })
  console.log(links.length, page.url, page.status, page.result)
} finally {
  await session.close()
}
```

Errors come back as HTTP 4xx/5xx with `{"ok": false, "error": "...", "kind": "..."}` (see below), so axios throws and `error.response.data` has the details.

## HTTP API reference

All bodies are JSON. Unless `--token` / `STORA_HEADLESS_TOKEN` is unset, every route except `/health` needs `Authorization: Bearer <token>` (or `X-Api-Key: <token>`).

| Method and path | What it does |
|---|---|
| `GET /` or `GET /tester` | The [tester page](#tester-page) (no token needed) |
| `GET /health` | Version, engine, open sessions, free slots, heap (no token needed) |
| `POST /render` | One-shot: open, load `url`, wait, run script, return, close |
| `POST /sessions` | Open a session; with `url`, also load it (same fields as `/render`) |
| `GET /sessions` | List open sessions (id, age, idle time) |
| `POST /sessions/{id}/goto` | Load a URL in the session |
| `POST /sessions/{id}/evaluate` | Run `script` (or `function` + `args`); with `waitForNavigation`, wait for the page the script navigates to |
| `POST /sessions/{id}/click` | Click `selector`, wait for the next page (unless `waitForNavigation: false`) |
| `POST /sessions/{id}/wait` | Wait for `waitForSelector` and/or `waitForFunction` |
| `GET /sessions/{id}/content` | Current HTML (after JavaScript) |
| `GET /sessions/{id}/text` | Current visible text |
| `GET /sessions/{id}/cookies` | Session cookies |
| `POST /sessions/{id}/cookies` | Add cookies: `{"cookies": [{"name","value","domain","path","expires","secure","httpOnly"}]}` |
| `DELETE /sessions/{id}` | Close the session |

### Session options (`/render`, `POST /sessions`)

| Field | Default | Meaning |
|---|---|---|
| `proxy` | none | Proxy URL, see [Proxies](#proxies) |
| `browser` | `chrome` | Emulated browser: `chrome`, `edge`, `firefox`, `firefox_esr` (user agent, headers, JavaScript quirks) |
| `userAgent` | browser's | Override User-Agent and `navigator.userAgent` |
| `acceptLanguage` | `en-US,en;q=0.9` | Accept-Language header and `navigator.language` |
| `timezone` | JVM's | Time zone seen by scripts, e.g. `Asia/Seoul` |
| `headers` | none | Extra headers on every request, e.g. `{"Referer": "https://..."}` |
| `cookies` | none | Cookies to set before loading |
| `javascript` | `true` | Run page JavaScript |
| `css` | `false` | Load CSS (turn on if scripts need computed styles) |
| `images` | `false` | Download images |
| `blockUrls` | none | Skip requests whose URL matches (glob with `*`, else substring), e.g. `["*google-analytics*", "*doubleclick*"]` |
| `blockTypes` | none | Skip `image`, `font`, `stylesheet`, `media` or `script` files (by extension) |
| `insecureSsl` | `false` | Accept invalid certificates |
| `timeoutMs` | `30000` | Connect and read timeout per HTTP request |
| `scriptTimeoutMs` | `20000` session / `10000` per call | Maximum run time of one script |

### Page options (`/render`, `POST /sessions`, `/goto`)

| Field | Default | Meaning |
|---|---|---|
| `url` | required for `/render`, `/goto` | Page to load (http, https, data or about URL) |
| `method`, `body` | `GET` | HTTP method and request body |
| `headers` (in `/goto`) | none | Headers for this navigation only |
| `jsWaitMs` | `1000` | After load, keep running timers and async requests the page starts within this time |
| `waitForSelector` | none | Wait until a CSS selector matches |
| `waitForFunction` | none | Wait until a JavaScript expression is truthy |
| `waitTimeoutMs` | `10000` | Limit for the two waits above |
| `failOnWaitTimeout` | `true` | With `false`, a wait timeout returns the page anyway with `"waitTimedOut": true` |
| `script` or `function` + `args` | none | Run after loading and waiting; value returned in `result` |
| `return` | `["html"]` | Parts to return: any of `html`, `text`, `cookies`, `headers`; `[]` for none |

### Click and navigation options (`/click`, `/evaluate` with `waitForNavigation`)

| Field | Default | Meaning |
|---|---|---|
| `selector` (`/click`) | required | CSS selector; the first match is clicked with real mouse events |
| `waitForNavigation` | `true` for `/click` | Wait for a new document |
| `navigationTimeoutMs` | `30000` | Limit for the navigation |
| `jsWaitMs`, `waitForSelector`, `waitForFunction`, `waitTimeoutMs` | as above | Applied on the new page |
| `then` | none | Script to run on the new page; value returned in `result` |
| `return` | `[]` | Parts of the new page to return |

### Response

```json
{
  "ok": true,
  "id": "…",                      // POST /sessions only
  "url": "https://…",             // final URL after redirects or navigation
  "status": 200,                  // HTTP status of the page (403, 404… are not errors here)
  "contentType": "text/html",
  "title": "…",
  "result": {},                   // script / then / function result
  "html": "<!DOCTYPE html>…",     // if requested
  "text": "…", "cookies": [], "headers": [],
  "navigated": true,              // click / navigation calls
  "waitTimedOut": true,           // only with failOnWaitTimeout: false
  "stats": {"requests": 33, "blocked": 0, "bytes": 2113674},
  "timingMs": 7103
}
```

### Errors

| HTTP | `kind` | When |
|---|---|---|
| 400 | `BAD_REQUEST` | Missing or invalid field, bad selector, invalid proxy URL |
| 401 | `UNAUTHORIZED` | Missing or wrong token |
| 404 | `NOT_FOUND` | Unknown route or session id |
| 422 | `SCRIPT` | Your script threw, did not compile, or returned something not JSON-serializable |
| 502 | `NAVIGATION` | The page could not be loaded (DNS, connection refused, proxy failure, TLS) |
| 503 | `BROWSER` | All session slots busy for 30 s (raise `--max-sessions` or close sessions) |
| 504 | `TIMEOUT` | Page load, wait, navigation or script took too long |

## Java library

The browser can be used without the HTTP server:

```java
SessionOptions options = new SessionOptions()
    .proxy("http://USER:PASS@HOST:PORT")
    .acceptLanguage("ko-KR,ko;q=0.9")
    .blockUrl("*google-analytics*");

try (BrowserSession session = new BrowserSession(options)) {
  session.navigate("https://www.coupang.com/", 1_000);
  JsonElement links = session.evaluate(
      "Array.from(document.querySelectorAll('a[href*=\"/vp/products/\"]')).map(function (a) { return a.href; })", 10_000);

  NavigationResult product = session.click("a[href*=\"/vp/products/\"]", true, 30_000, 3_000);
  JsonElement info = session.evaluate("({title: document.title, url: location.href})", 10_000);

  String html = session.content();
}
```

Main types: `BrowserSession` (`navigate`, `evaluate`, `callFunction`, `click`, `evaluateAndWaitForNavigation`, `waitForSelector`, `waitForFunction`, `content`, `text`, `cookies`, `setCookies`, `stats`), `SessionOptions`, `ProxySpec`, `NavigationResult` and `BrowserException` with `kind()`.

A session is not meant for parallel use: its methods are synchronized and run one at a time. Use one session per concurrent job.

## Command line

```bash
java -jar stora-headless-all.jar render https://example.com/ --script 'document.title' --text
java -jar stora-headless-all.jar render https://www.coupang.com/ --proxy "$PROXY" --wait-for 'a[href*="/vp/products/"]'
java -jar stora-headless-all.jar serve --port 8090 --max-sessions 8 --session-ttl 300 --token "$TOKEN"
java -jar stora-headless-all.jar --version
```

`serve` also reads `HOST`, `PORT`, `STORA_HEADLESS_TOKEN`, `MAX_SESSIONS` and `SESSION_TTL_SECONDS` from the environment.

## What the JavaScript engine supports

The engine is HtmlUnit's pure-Java JavaScript implementation (a Rhino fork) with a full DOM. Checked on 2026-10-16:

| Supported | Not supported |
|---|---|
| `let`/`const`, arrow functions, template strings, destructuring, default parameters, spread in arrays, generators, `for…of`, `?.`, `??`, `**`, Promise, `fetch`, XHR, Map/Set, Symbol, `Array.includes`/`flat`, `Object.entries`/`fromEntries`, `URLSearchParams` | `class` declarations, `async`/`await`, spread in function calls (`f(...args)`) |

Sites that ship modern bundles with `class` or `async` code (not compiled down for older browsers) will partly fail to run. Their server-rendered HTML is still available. Write your own scripts with the supported syntax, e.g. `.then()` instead of `await`.

## Limits

- **Not a pixel browser:** no layout, rendering or screenshots. `getBoundingClientRect` and visibility checks are approximate; CSS is off by default.
- **Bot protection:** pages behind JavaScript challenges or CAPTCHAs (Akamai, Cloudflare and similar) are returned as served. stora-headless does not try to solve or bypass them.
- **CloudFront:** delivery through CloudFront works like any other site (tested with `www.stora.mn`: 200 through CloudFront). Whether the page's own JavaScript runs depends on its bundles, see the next point.
- **Modern single-page apps:** apps whose bundles use `class` or `async`/`await` do not run in this engine. Example: `www.stora.mn` serves an empty Next.js shell (`<div id="__next"></div>`) and its `_app` bundle has 132 `class` and 325 `async`/`await` uses, so nothing renders. Server-rendered HTML (and the site's JSON APIs, which you can call with `fetch` from a script) still work.
- **Frames and pop-ups:** a session always reads its main page. Iframes (for example ad frames) and pop-ups load in their own windows and never replace it; `window.open` pop-ups are blocked.
- **TLS and HTTP/2:** requests use Java's TLS and HTTP/1.1 stack, which some sites fingerprint differently from desktop browsers.
- **Memory:** depends on the mode. HTML-only sessions are light (8 parallel Amazon sessions stayed under 350 MB of heap); sessions that run a big site's scripts need about 100–150 MB each (4 parallel lean Amazon sessions: 511 MB). `/health` reports `heapUsedMb`. Size `--max-sessions` and `-Xmx` together; see [benchmark.md](benchmark.md).

## Deploying

Java 17+ is the only requirement. With PM2, like the other Stora services:

```js
// ecosystem.config.js
module.exports = {
  apps: [{
    name: 'stora-headless',
    script: 'java',
    args: '-Xmx1g -jar stora-headless-all.jar serve',
    env: { PORT: '8090', MAX_SESSIONS: '8', STORA_HEADLESS_TOKEN: '<set on the server>' },
    autorestart: true,
    max_memory_restart: '1500M'
  }]
}
```

Keep `HOST` at `127.0.0.1` when the scrapers run on the same host. Otherwise bind to the private network address and keep the token set.

## Development

```bash
mvn test          # 33 tests: local test site and test proxy, no internet needed
mvn package       # + target/stora-headless-0.1.0-SNAPSHOT-all.jar
```

| Path | Contents |
|---|---|
| `src/main/java/mn/stora/headless/BrowserSession.java` | The browser: navigation, scripts, clicks, waits, cookies |
| `SessionOptions.java`, `ProxySpec.java`, `RequestFilter.java` | Options, proxy parsing, URL and type blocking with stats |
| `server/BrowserServer.java`, `server/Requests.java` | HTTP routes and JSON mapping (JDK `HttpServer`) |
| `Main.java` | `serve` and `render` commands |
| `src/main/resources/tester.html` | The tester page (plain HTML, CSS and JavaScript, no build step) |
| `src/test/java/...` | `TestSite` (local pages: SPA, shop, product, headers echo), `TestProxy` (proxy with login), session and server tests |
| `examples/` | Coupang example in shell and TypeScript |
| `bench/amazon_benchmark.py`, `bench/results-2026-10-16.json` | Amazon product and search benchmark and its raw results, summarized in [benchmark.md](benchmark.md) |
