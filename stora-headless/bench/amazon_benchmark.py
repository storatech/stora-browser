#!/usr/bin/env python3
"""Benchmark stora-headless on Amazon product and search pages.

Standard library only. Needs a running server.

    STORA_HEADLESS_URL=http://127.0.0.1:8090 STORA_HEADLESS_TOKEN=... \
      python3 bench/amazon_benchmark.py --proxies proxies.txt --out results.json

--proxies is optional: a file with one proxy URL per line (http://user:pass@host:port).
Proxies are rotated per request and never printed. Results (without proxy values) go to --out
and a summary is printed as Markdown.
"""
import argparse
import concurrent.futures
import json
import os
import statistics
import time
import urllib.error
import urllib.request

BASE = os.environ.get('STORA_HEADLESS_URL', 'http://127.0.0.1:8090')
TOKEN = os.environ.get('STORA_HEADLESS_TOKEN', '')

PRODUCTS = ['B08L6RT1X9', 'B01B3GMNU4', 'B0CNHPZ5NS']
SEARCHES = ['coffee beans', 'iphone case', 'usb c cable']

# Scripts run inside the page (plain ES2015: the engine has no async/await or class).
PRODUCT_SCRIPT = """(function () {
  function text(sel) { var e = document.querySelector(sel); return e ? e.textContent.trim().replace(/\\s+/g, ' ') : null; }
  return {
    captcha: !!document.querySelector('form[action*="validateCaptcha"]'),
    title: text('#productTitle'),
    price: text('#corePrice_feature_div .a-price .a-offscreen') || text('#corePriceDisplay_desktop_feature_div .a-price .a-offscreen')
      || text('#apex_desktop .a-price .a-offscreen') || text('.a-price .a-offscreen')
  };
})()"""

SEARCH_SCRIPT = """(function () {
  var items = Array.from(document.querySelectorAll('div[data-component-type="s-search-result"]'));
  function text(el, sel) { var e = el.querySelector(sel); return e ? e.textContent.trim().replace(/\\s+/g, ' ') : null; }
  var first = items[0];
  return {
    captcha: !!document.querySelector('form[action*="validateCaptcha"]'),
    count: items.length,
    withPrice: items.filter(function (el) { return !!el.querySelector('.a-price .a-offscreen'); }).length,
    title: first ? text(first, 'h2') : null,
    price: first ? text(first, '.a-price .a-offscreen') : null
  };
})()"""

AD_URLS = ['*amazon-adsystem*', '*fls-na.amazon*', '*unagi*', '*doubleclick*', '*google-analytics*']

MODES = {
    # Everything a browser would do (images and CSS are still off by default).
    'full': {'jsWaitMs': 1000},
    # Page scripts run, but static assets and ad/tracking requests are skipped.
    'lean': {'jsWaitMs': 1000, 'blockTypes': ['image', 'font', 'media', 'stylesheet'], 'blockUrls': AD_URLS},
    # Only the HTML document; external page scripts are skipped, our script still runs.
    'html-only': {'jsWaitMs': 0, 'blockTypes': ['script', 'stylesheet', 'image', 'font', 'media']},
}


def call(method, path, body=None, timeout=180):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={'Authorization': 'Bearer ' + TOKEN, 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b'{}')
        except ValueError:
            return e.code, {}
    except Exception as e:  # network error talking to the server
        return 0, {'error': str(e), 'kind': 'CLIENT'}


def run_one(kind, target, mode, proxy):
    if kind == 'product':
        url, script = 'https://www.amazon.com/dp/' + target, PRODUCT_SCRIPT
    else:
        url, script = 'https://www.amazon.com/s?k=' + urllib.request.quote(target), SEARCH_SCRIPT
    body = {'url': url, 'acceptLanguage': 'en-US,en;q=0.9', 'script': script, 'return': [], 'timeoutMs': 30000}
    body.update(MODES[mode])
    if proxy:
        body['proxy'] = proxy
    started = time.time()
    status, r = call('POST', '/render', body)
    wall_ms = int((time.time() - started) * 1000)
    result = r.get('result') or {}
    return {
        'kind': kind, 'target': target, 'mode': mode, 'network': 'proxy' if proxy else 'direct',
        'httpStatus': status, 'pageStatus': r.get('status'), 'ok': bool(r.get('ok')),
        'error': r.get('kind'), 'captcha': bool(result.get('captcha')),
        'title': result.get('title'), 'price': result.get('price'),
        'count': result.get('count'), 'withPrice': result.get('withPrice'),
        'success': bool(result.get('title')) and bool(result.get('price')),
        'ms': r.get('timingMs', wall_ms), 'requests': (r.get('stats') or {}).get('requests'),
        'bytes': (r.get('stats') or {}).get('bytes'),
    }


def concurrency(mode, parallel, proxies):
    jobs = [(PRODUCTS[i % len(PRODUCTS)], proxies[i % len(proxies)] if proxies else None) for i in range(parallel)]
    started = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=parallel) as pool:
        rows = list(pool.map(lambda j: run_one('product', j[0], mode, j[1]), jobs))
    seconds = time.time() - started
    _, health = call('GET', '/health')
    return {'mode': mode, 'parallel': parallel, 'seconds': round(seconds, 1),
            'pagesPerMinute': round(len(rows) / seconds * 60, 1),
            'success': sum(r['success'] for r in rows), 'heapUsedMb': health.get('heapUsedMb')}


def summarize(rows):
    out = ['| Page | Mode | Network | Success | Median ms | Max ms | Avg requests | Avg KB |', '|---|---|---|---|---|---|---|---|']
    keys = []
    for r in rows:
        k = (r['kind'], r['mode'], r['network'])
        if k not in keys:
            keys.append(k)
    for k in keys:
        group = [r for r in rows if (r['kind'], r['mode'], r['network']) == k]
        ms = [r['ms'] for r in group if r['ok']]
        req = [r['requests'] for r in group if r['requests'] is not None]
        kb = [r['bytes'] / 1024 for r in group if r['bytes']]
        out.append('| %s | %s | %s | %d/%d | %s | %s | %s | %s |' % (
            k[0], k[1], k[2], sum(r['success'] for r in group), len(group),
            int(statistics.median(ms)) if ms else '-', max(ms) if ms else '-',
            int(statistics.mean(req)) if req else '-', int(statistics.mean(kb)) if kb else '-'))
    return '\n'.join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--proxies', help='file with one proxy URL per line')
    ap.add_argument('--runs', type=int, default=1, help='runs per page, mode and network')
    ap.add_argument('--out', default='amazon-benchmark-results.json')
    ap.add_argument('--parallel', type=int, default=8)
    args = ap.parse_args()

    proxies = []
    if args.proxies:
        with open(args.proxies) as f:
            proxies = [line.strip() for line in f if line.strip()]
    networks = [None] + (['proxy'] if proxies else [])

    rows, n = [], 0
    for network in networks:
        for kind, targets in (('product', PRODUCTS), ('search', SEARCHES)):
            for mode in MODES:
                for target in targets:
                    for _ in range(args.runs):
                        proxy = proxies[n % len(proxies)] if network else None
                        n += 1
                        row = run_one(kind, target, mode, proxy)
                        rows.append(row)
                        print('%-7s %-12s %-9s %-6s ok=%s success=%s captcha=%s %6sms req=%s %s | %s' % (
                            kind, target[:12], mode, row['network'], row['ok'], row['success'], row['captcha'],
                            row['ms'], row['requests'], (row['title'] or row['error'] or '')[:40], row['price']), flush=True)

    conc = [concurrency('html-only', args.parallel, proxies), concurrency('lean', args.parallel // 2, proxies)]
    _, health = call('GET', '/health')
    with open(args.out, 'w') as f:
        json.dump({'rows': rows, 'concurrency': conc, 'health': health}, f, indent=2, ensure_ascii=False)
    print()
    print(summarize(rows))
    print()
    for c in conc:
        print(c)
    print(health)


if __name__ == '__main__':
    main()
