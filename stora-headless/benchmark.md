# Benchmark: Amazon product and search pages

Run on 2026-10-16 with stora-headless 0.1.0-SNAPSHOT (HtmlUnit 5.6.0). Raw results: [`bench/results-2026-10-16.json`](bench/results-2026-10-16.json). Script: [`bench/amazon_benchmark.py`](bench/amazon_benchmark.py).

## Summary

- **Every page loaded and was extracted.** 36 of 36 page loads returned the page, and 34 of 36 found both a title and a price. The 2 misses were the same search ("iphone case" through a proxy), where the first result was a listing without a price (13 of 16 results there had prices). No CAPTCHA or robot check appeared in any of the 48 page loads, including the concurrency runs.
- **HTML-only mode is 4–9× faster** than running Amazon's own scripts, and returns the same titles and prices, because Amazon puts them in the server HTML:
  - Product: median 3.8 s direct and 5.1 s through a proxy, versus 14.6 s and 35.5 s in full mode.
  - Search: median 3.1 s direct and 3.6 s through a proxy, versus 15.8 s and 32.0 s.
- **It downloads about 7× less data:** about 0.3 MB per page instead of about 2.3 MB.
- **Throughput:** 8 parallel HTML-only sessions did **73 product pages per minute** on one machine, with heap below 350 MB.

## Setup

| | |
|---|---|
| Machine | Apple Silicon Mac (arm64), macOS |
| Java | Zulu JDK 17.0.15, `-Xmx1g` |
| Server | `serve --max-sessions 8` on localhost; each page is one `POST /render` call |
| Network | "direct": this machine's internet connection. "proxy": 4 HTTP proxies from the stora-scrapers list, rotated per request |
| Emulation | Chrome (default), `Accept-Language: en-US,en;q=0.9` |
| Products | `B08L6RT1X9` (Apple iPhone 12 Pro), `B01B3GMNU4` (Hanes T-shirts), `B0CNHPZ5NS` (ASUS ROG router) |
| Searches | `coffee beans`, `iphone case`, `usb c cable` |

### Modes

| Mode | Request settings | What happens |
|---|---|---|
| full | `jsWaitMs: 1000` | Loads the page and runs all of Amazon's scripts, then 1 s of timers and async requests. Images and CSS are not downloaded (defaults) |
| lean | full + `blockTypes: [image, font, media, stylesheet]` + `blockUrls` for ads and trackers (`*amazon-adsystem*`, `*fls-na.amazon*`, `*unagi*`, `*doubleclick*`, `*google-analytics*`) | Page scripts run; static files and ad/tracking calls are answered locally with an empty 204 |
| html-only | `jsWaitMs: 0`, `blockTypes: [script, stylesheet, image, font, media]` | Only the documents. Inline scripts run, external script files are skipped; the extraction script still runs |

**Success** means the extraction script returned both a title and a price: `#productTitle` and the buy-box price for products, the first result's `h2` and `.a-price .a-offscreen` for searches. **Time** is the server's `timingMs`: load, wait and script, without the HTTP call to the server itself. **Requests** count every request the session made, including blocked ones (answered locally), so lean mode can show more requests than full mode while transferring less.

## Results by mode

| Page | Mode | Network | Success | Median time | Max time | Avg requests | Avg data |
|---|---|---|---|---|---|---|---|
| product | full | direct | 3/3 | 14.6 s | 16.7 s | 88 | 2.5 MB |
| product | lean | direct | 3/3 | 9.3 s | 9.6 s | 109 | 1.7 MB |
| product | html-only | direct | 3/3 | **3.8 s** | 3.9 s | 65 | 0.36 MB |
| search | full | direct | 3/3 | 15.8 s | 18.3 s | 88 | 2.1 MB |
| search | lean | direct | 3/3 | 7.3 s | 7.6 s | 101 | 1.5 MB |
| search | html-only | direct | 3/3 | **3.1 s** | 3.2 s | 14 | 0.32 MB |
| product | full | proxy | 3/3 | 35.5 s | 39.9 s | 83 | 2.5 MB |
| product | lean | proxy | 3/3 | 22.8 s | 23.2 s | 105 | 1.6 MB |
| product | html-only | proxy | 3/3 | **5.1 s** | 6.4 s | 75 | 0.35 MB |
| search | full | proxy | 3/3 | 32.0 s | 32.8 s | 70 | 1.8 MB |
| search | lean | proxy | 2/3 | 14.2 s | 15.6 s | 83 | 1.4 MB |
| search | html-only | proxy | 2/3 | **3.6 s** | 4.0 s | 14 | 0.29 MB |

The proxies add little in HTML-only mode (+1.3 s for products, +0.5 s for searches) but a lot in full mode (+16–21 s), because every script and XHR goes through the proxy.

## Concurrency

All sessions run in one JVM (`-Xmx1g`, `--max-sessions 8`), through the rotating proxies.

| Mode | Parallel sessions | Pages | Wall time | Throughput | Success | Heap after |
|---|---|---|---|---|---|---|
| html-only | 8 | 8 product pages | 6.6 s | **73 pages/min** | 8/8 | 341 MB |
| lean | 4 | 4 product pages | 26.6 s | 9 pages/min | 4/4 | 511 MB |

## Every run

| Page | Target | Mode | Network | Time ms | Requests | KB | Title / price (search: results with a price) |
|---|---|---|---|---|---|---|---|
| product | B08L6RT1X9 | full | direct | 14629 | 85 | 2935 | Apple iPhone 12 Pro (128GB, Gold) [Locked] … / $861.05 |
| product | B01B3GMNU4 | full | direct | 14373 | 77 | 2523 | Hanes Mens T-Shirts, Men's Beefyt Henley … / $17.00 |
| product | B0CNHPZ5NS | full | direct | 16741 | 103 | 2311 | ASUS ROG Rapture GT-AX11000 Pro Tri-Band … / $209.00 |
| product | B08L6RT1X9 | lean | direct | 9586 | 107 | 1663 | Apple iPhone 12 Pro … / $861.05 |
| product | B01B3GMNU4 | lean | direct | 8855 | 124 | 1767 | Hanes Mens T-Shirts … / $17.00 |
| product | B0CNHPZ5NS | lean | direct | 9270 | 96 | 1682 | ASUS ROG Rapture … / $209.00 |
| product | B08L6RT1X9 | html-only | direct | 3774 | 53 | 321 | Apple iPhone 12 Pro … / $861.05 |
| product | B01B3GMNU4 | html-only | direct | 3922 | 45 | 417 | Hanes Mens T-Shirts … / $17.00 |
| product | B0CNHPZ5NS | html-only | direct | 3781 | 99 | 368 | ASUS ROG Rapture … / $209.00 |
| search | coffee beans | full | direct | 18313 | 111 | 2449 | Starbucks Whole Bean Coffee, Dark Roast … / $14.97 (60/60) |
| search | iphone case | full | direct | 15806 | 83 | 1980 | SUPCASE for iPhone 18 Pro Max Case … / $26.09 (20/22) |
| search | usb c cable | full | direct | 13263 | 71 | 1940 | LISEN USB C to USB C Cable, 240W … / $8.99 (22/22) |
| search | coffee beans | lean | direct | 7550 | 148 | 1545 | Starbucks Whole Bean Coffee … / $14.97 (60/60) |
| search | iphone case | lean | direct | 7305 | 83 | 1522 | OtterBox iPhone 17e, 16e, 15, 14, & 13 … / $29.92 (15/16) |
| search | usb c cable | lean | direct | 6330 | 72 | 1412 | LISEN USB C to USB C Cable … / $8.99 (22/22) |
| search | coffee beans | html-only | direct | 3167 | 14 | 355 | Starbucks Whole Bean Coffee … / $14.97 (59/60) |
| search | iphone case | html-only | direct | 3073 | 14 | 354 | SUPFINE Magnetic for iPhone 15 Pro Max … / $9.99 (21/22) |
| search | usb c cable | html-only | direct | 2864 | 14 | 270 | LISEN USB C to USB C Cable … / $8.99 (22/22) |
| product | B08L6RT1X9 | full | proxy | 35480 | 90 | 3028 | Apple iPhone 12 Pro … / $861.05 |
| product | B01B3GMNU4 | full | proxy | 27746 | 75 | 2280 | Hanes Mens T-Shirts … / $17.00 |
| product | B0CNHPZ5NS | full | proxy | 39889 | 84 | 2255 | ASUS ROG Rapture … / $209.00 |
| product | B08L6RT1X9 | lean | proxy | 21442 | 98 | 1669 | Apple iPhone 12 Pro … / $861.05 |
| product | B01B3GMNU4 | lean | proxy | 23221 | 123 | 1672 | Hanes Mens T-Shirts … / $17.00 |
| product | B0CNHPZ5NS | lean | proxy | 22794 | 94 | 1689 | ASUS ROG Rapture … / $209.00 |
| product | B08L6RT1X9 | html-only | proxy | 6392 | 77 | 302 | Apple iPhone 12 Pro … / $861.05 |
| product | B01B3GMNU4 | html-only | proxy | 4629 | 50 | 416 | Hanes Mens T-Shirts … / $17.00 |
| product | B0CNHPZ5NS | html-only | proxy | 5062 | 99 | 367 | ASUS ROG Rapture … / $209.00 |
| search | coffee beans | full | proxy | 32817 | 87 | 2124 | Lavazza Super Crema Whole Bean Coffee … / $26.99 (48/48) |
| search | iphone case | full | proxy | 31954 | 67 | 1771 | OtterBox iPhone 17e … / $29.92 (14/16) |
| search | usb c cable | full | proxy | 28090 | 57 | 1783 | Anker USB C to USB C Cable, 60W … / $9.99 (16/16) |
| search | coffee beans | lean | proxy | 13522 | 117 | 1407 | Lavazza Super Crema … / $26.99 (48/48) |
| search | iphone case | lean | proxy | 14183 | 71 | 1484 | OtterBox iPhone 17e … / no price on first result (13/16) |
| search | usb c cable | lean | proxy | 15619 | 63 | 1445 | Anker USB C to USB C Cable … / $9.99 (16/16) |
| search | coffee beans | html-only | proxy | 3953 | 14 | 316 | Lavazza Super Crema … / $26.99 (48/48) |
| search | iphone case | html-only | proxy | 3624 | 14 | 308 | OtterBox iPhone 17e … / no price on first result (13/16) |
| search | usb c cable | html-only | proxy | 3531 | 15 | 252 | Anker USB C to USB C Cable … / $9.99 (16/16) |

Searches through the proxies return 16–48 results instead of 22–60, and different first results. This is most likely because Amazon tailors results to the visitor's location, which differs between the proxies and the direct connection.

## Observations and recommendations

- **Use HTML-only mode for Amazon product and search pages.** It extracts the same data for a fraction of the time and traffic. Switch to lean or full only for content that Amazon fills in with JavaScript after load.
- **Lean mode** cuts the time of full mode by a third to a half by skipping static files and ad/tracking calls, while still running the page's scripts.
- **Product pages still make 45–99 requests in HTML-only mode.** These are mostly iframe documents and script URLs without a `.js` extension, which the extension-based `blockTypes` cannot recognise. Adding `blockUrls` for the remaining hosts would cut this further.
- **No bot challenge appeared** with either the direct connection or the 4 proxies at this volume (48 page loads in one run). Higher volumes may behave differently; rotate proxies as stora-scrapers already does.
- **Size the server by mode.** HTML-only sessions are short and light: 8 parallel sessions stayed under 350 MB of heap. Sessions that run Amazon's scripts hold them in memory: 4 parallel lean sessions reached 511 MB (about 125 MB each). Plan 100–150 MB per concurrent lean or full session, so `-Xmx1g` fits about 4–6 of them.

## Reproduce

```bash
cd stora-headless
mvn package
java -Xmx1g -jar target/stora-headless-0.1.0-SNAPSHOT-all.jar serve --port 8090 --max-sessions 8 --token "$TOKEN"

# proxies.txt: one proxy URL per line, e.g. http://USER:PASS@HOST:PORT (optional)
STORA_HEADLESS_URL=http://127.0.0.1:8090 STORA_HEADLESS_TOKEN="$TOKEN" \
  python3 bench/amazon_benchmark.py --proxies proxies.txt --runs 1 --parallel 8 --out results.json
```

The script prints one line per page load, the summary table and the concurrency results, and writes all rows to `--out`. Proxy values are never printed or written.
