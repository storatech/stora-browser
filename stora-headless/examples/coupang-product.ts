/**
 * Open Coupang, find a product with JavaScript, click it, wait for the product page, read it, close.
 *
 * No dependencies (Node 18+ fetch). Run with:
 *   node --experimental-strip-types examples/coupang-product.ts     (Node 22+)
 *   npx tsx examples/coupang-product.ts
 *
 * Environment:
 *   STORA_HEADLESS_URL    server URL (default http://127.0.0.1:8090)
 *   STORA_HEADLESS_TOKEN  server token, if the server uses one
 *   PROXY                 optional proxy, e.g. http://USER:PASS@HOST:PORT
 *
 * In stora-scrapers the same calls can be made with axios; see the README.
 */

const BASE = process.env.STORA_HEADLESS_URL ?? 'http://127.0.0.1:8090'
const TOKEN = process.env.STORA_HEADLESS_TOKEN ?? ''

interface PageResponse<T = unknown> {
  ok: boolean
  id?: string
  url: string
  status: number
  title?: string
  result?: T
  html?: string
  navigated?: boolean
  timingMs: number
  error?: string
  kind?: string
}

async function call<T = unknown> (method: string, path: string, body?: unknown): Promise<PageResponse<T>> {
  const res = await fetch(BASE + path, {
    method,
    headers: { Authorization: `Bearer ${TOKEN}`, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  const json = await res.json() as PageResponse<T>
  if (!res.ok) {
    throw new Error(`${method} ${path} -> ${res.status} ${json.kind ?? ''}: ${json.error ?? ''}`)
  }
  return json
}

class HeadlessSession {
  readonly id: string
  readonly opened: PageResponse

  private constructor (id: string, opened: PageResponse) {
    this.id = id
    this.opened = opened
  }

  static async open (url: string, options: Record<string, unknown> = {}): Promise<HeadlessSession> {
    const res = await call('POST', '/sessions', { url, return: [], ...options })
    return new HeadlessSession(res.id as string, res)
  }

  async evaluate<T> (script: string): Promise<T> {
    return (await call<T>('POST', `/sessions/${this.id}/evaluate`, { script })).result as T
  }

  async click<T> (selector: string, then: string, options: Record<string, unknown> = {}): Promise<PageResponse<T>> {
    return await call<T>('POST', `/sessions/${this.id}/click`, { selector, then, ...options })
  }

  async close (): Promise<void> {
    await call('DELETE', `/sessions/${this.id}`).catch(() => undefined)
  }
}

// JavaScript run inside the page. Plain ES2015 (no async/await or class; see README).
const FIND_PRODUCTS = `Array.from(new Set(Array.from(document.querySelectorAll('a[href*="/vp/products/"]'))
  .map(function (a) { return a.href.split('?')[0]; })))`

const PRODUCT_INFO = `(function () {
  function meta(p) { var m = document.querySelector('meta[property="' + p + '"]'); return m ? m.content : null; }
  function text(sel) { var e = document.querySelector(sel); return e ? e.textContent.trim().replace(/\\s+/g, ' ') : null; }
  return {
    url: location.href.split('?')[0],
    title: document.title,
    blocked: !!document.getElementById('sec-if-cpt-container') || document.title === 'Access Denied',
    name: meta('og:title') || text('h1'),
    image: meta('og:image'),
    price: text('.total-price strong') || text('.final-price-amount') || text('.price-amount')
  };
})()`

interface ProductInfo {
  url: string
  title: string
  blocked: boolean
  name: string | null
  image: string | null
  price: string | null
}

async function main (): Promise<void> {
  const options: Record<string, unknown> = { acceptLanguage: 'ko-KR,ko;q=0.9,en-US;q=0.8', jsWaitMs: 1000 }
  if (process.env.PROXY !== undefined && process.env.PROXY !== '') {
    options.proxy = process.env.PROXY
  }

  // 1. Open the link
  const session = await HeadlessSession.open('https://www.coupang.com/', options)
  console.log('opened ', session.opened.status, session.opened.title, `${session.opened.timingMs} ms`)
  try {
    // 2. Run JavaScript: find products
    const links = await session.evaluate<string[]>(FIND_PRODUCTS)
    console.log('found  ', links.length, 'products, first:', links[0])
    if (links.length === 0) return

    // 3. Click a product, wait for navigation, run JavaScript on the product page
    const page = await session.click<ProductInfo>('a[href*="/vp/products/"]', PRODUCT_INFO, { jsWaitMs: 3000 })
    console.log('clicked', page.status, `${page.timingMs} ms`)
    console.log('product', page.result)
    if (page.result?.blocked === true) {
      console.log('note    Coupang answered with its bot-protection page; product data is not available.')
    }

    // ...more evaluate / click / goto calls can follow on the same session...
  } finally {
    // 4. Close
    await session.close()
  }
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
