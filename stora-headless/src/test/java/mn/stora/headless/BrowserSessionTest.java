package mn.stora.headless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

class BrowserSessionTest {

  private static TestSite site;
  private static TestProxy proxy;

  @BeforeAll
  static void start() throws Exception {
    site = new TestSite();
    proxy = new TestProxy("scraper", "s3cret");
  }

  @AfterAll
  static void stop() throws Exception {
    site.close();
    proxy.close();
  }

  @Test
  void rendersJavaScriptContentAndFetch() {
    try (BrowserSession s = new BrowserSession(new SessionOptions())) {
      NavigationResult nav = s.navigate(site.url("/spa"), 0);
      assertEquals(200, nav.status());
      s.waitForSelector(".price", 5_000);
      s.waitForFunction("document.getElementById('name').textContent === 'Widget'", 5_000);
      assertEquals("42", s.evaluate("document.querySelector('.price').textContent", 5_000).getAsString());
      String html = s.content();
      assertTrue(html.startsWith("<!DOCTYPE html>"), html);
      assertTrue(html.contains("<span class=\"price\">42</span>"), html);
      assertTrue(html.contains("Widget"), html);
      assertEquals("Product", s.title());
    }
  }

  @Test
  void followsRedirects() {
    try (BrowserSession s = new BrowserSession(new SessionOptions())) {
      NavigationResult nav = s.navigate(site.url("/redirect"), 500);
      assertEquals(site.url("/spa"), nav.url());
      assertEquals(200, nav.status());
    }
  }

  @Test
  void reportsErrorStatusWithoutThrowing() {
    try (BrowserSession s = new BrowserSession(new SessionOptions())) {
      NavigationResult nav = s.navigate(site.url("/forbidden"), 0);
      assertEquals(403, nav.status());
      assertTrue(s.text().contains("Access denied"));
    }
  }

  @Test
  void evaluatesObjectsPromisesAndFunctions() {
    try (BrowserSession s = new BrowserSession(new SessionOptions())) {
      s.navigate(site.url("/spa"), 0);
      JsonElement obj = s.evaluate("({a: 1, list: [1, 'two'], nested: {ok: true}})", 5_000);
      assertEquals(1, obj.getAsJsonObject().get("a").getAsInt());
      assertEquals("two", obj.getAsJsonObject().getAsJsonArray("list").get(1).getAsString());

      JsonElement later = s.evaluate("new Promise(function (r) { setTimeout(function () { r({done: true}); }, 100); })",
          5_000);
      assertTrue(later.getAsJsonObject().get("done").getAsBoolean());

      JsonElement fetched = s.evaluate("fetch('/api/product').then(function (r) { return r.json(); })", 5_000);
      assertEquals("Widget", fetched.getAsJsonObject().get("name").getAsString());

      JsonElement called = s.callFunction("function (a, b) { return a + b.n; }",
          List.of(new JsonPrimitive(2), Json.obj("n", 40)), 5_000);
      assertEquals(42, called.getAsInt());

      assertTrue(s.evaluate("undefined", 5_000).isJsonNull());
      JsonElement statements = s.evaluate("(function () { var x = 20; x += 1; return x * 2; })()", 5_000);
      assertEquals(42, statements.getAsInt());
    }
  }

  @Test
  void scriptErrorsAreReported() {
    try (BrowserSession s = new BrowserSession(new SessionOptions())) {
      s.navigate(site.url("/spa"), 0);
      BrowserException thrown = assertThrows(BrowserException.class,
          () -> s.evaluate("(function () { throw new Error('boom'); })()", 5_000));
      assertEquals(BrowserException.Kind.SCRIPT, thrown.kind());
      assertTrue(thrown.getMessage().contains("boom"), thrown.getMessage());

      BrowserException rejected = assertThrows(BrowserException.class,
          () -> s.evaluate("Promise.reject(new Error('nope'))", 5_000));
      assertTrue(rejected.getMessage().contains("nope"), rejected.getMessage());

      BrowserException syntax = assertThrows(BrowserException.class, () -> s.evaluate("this is not js", 5_000));
      assertEquals(BrowserException.Kind.SCRIPT, syntax.kind());
    }
  }

  @Test
  void waitTimesOut() {
    try (BrowserSession s = new BrowserSession(new SessionOptions())) {
      s.navigate(site.url("/spa"), 0);
      BrowserException thrown = assertThrows(BrowserException.class, () -> s.waitForSelector(".missing", 300));
      assertEquals(BrowserException.Kind.TIMEOUT, thrown.kind());
    }
  }

  @Test
  void sendsUserAgentHeadersAndEmulation() {
    SessionOptions o = new SessionOptions()
        .userAgent("StoraTest/1.0")
        .acceptLanguage("ko-KR,ko;q=0.9")
        .header("X-Custom", "hello");
    try (BrowserSession s = new BrowserSession(o)) {
      s.navigate(site.url("/headers"), 0);
      String text = s.text();
      assertTrue(text.contains("user-agent: StoraTest/1.0"), text);
      assertTrue(text.contains("accept-language: ko-KR,ko;q=0.9"), text);
      assertTrue(text.contains("x-custom: hello"), text);
      assertEquals("StoraTest/1.0", s.evaluate("navigator.userAgent", 5_000).getAsString());
    }
    try (BrowserSession s = new BrowserSession(new SessionOptions().emulation(SessionOptions.Emulation.FIREFOX))) {
      s.navigate(site.url("/headers"), 0);
      assertTrue(s.evaluate("navigator.userAgent", 5_000).getAsString().contains("Firefox"));
    }
  }

  @Test
  void usesProxyWithCredentials() {
    int before = proxy.forwarded.get();
    try (BrowserSession s = new BrowserSession(new SessionOptions().proxy(proxy.url("scraper", "s3cret")))) {
      NavigationResult nav = s.navigate(site.url("/headers"), 0);
      assertEquals(200, nav.status());
      assertTrue(s.text().contains("x-test-proxy: yes"), s.text());
    }
    assertTrue(proxy.forwarded.get() > before);
  }

  @Test
  void proxyWithoutCredentialsGets407() {
    try (BrowserSession s = new BrowserSession(new SessionOptions().proxy(proxy.url(null, null)))) {
      assertEquals(407, s.navigate(site.url("/headers"), 0).status());
    }
  }

  @Test
  void sessionsAreIsolated() {
    int before = proxy.forwarded.get();
    try (BrowserSession direct = new BrowserSession(new SessionOptions());
        BrowserSession proxied = new BrowserSession(new SessionOptions().proxy(proxy.url("scraper", "s3cret")))) {
      direct.navigate(site.url("/cookie"), 0);
      assertFalse(direct.text().contains("x-test-proxy"));
      assertEquals(before, proxy.forwarded.get());
      JsonArray cookies = direct.cookies();
      assertEquals(2, cookies.size(), cookies.toString());

      proxied.navigate(site.url("/headers"), 0);
      assertTrue(proxied.text().contains("x-test-proxy: yes"));
      assertEquals(0, proxied.cookies().size());
    }
  }

  @Test
  void setsCookiesBeforeLoading() {
    try (BrowserSession s = new BrowserSession(new SessionOptions())) {
      JsonArray cookies = new JsonArray();
      cookies.add(Json.obj("name", "region", "value", "kr", "domain", "127.0.0.1"));
      s.setCookies(cookies);
      s.navigate(site.url("/headers"), 0);
      assertTrue(s.text().contains("cookie: region=kr"), s.text());
    }
  }

  @Test
  void blocksUrlsAndResourceTypes() {
    int before = site.hits("/tracker.js");
    try (BrowserSession s = new BrowserSession(new SessionOptions().blockUrl("*tracker*"))) {
      s.navigate(site.url("/tracked"), 0);
      assertEquals("clean", s.title());
      assertEquals(before, site.hits("/tracker.js"));
      JsonObject stats = s.stats();
      assertEquals(1, stats.get("blocked").getAsInt());
    }
    try (BrowserSession s = new BrowserSession(new SessionOptions().blockType("script"))) {
      s.navigate(site.url("/tracked"), 0);
      assertEquals("clean", s.title());
    }
    try (BrowserSession s = new BrowserSession(new SessionOptions())) {
      s.navigate(site.url("/tracked"), 0);
      assertEquals("tracked", s.title());
    }
  }

  @Test
  void javaScriptCanBeDisabled() {
    try (BrowserSession s = new BrowserSession(new SessionOptions().javaScript(false))) {
      s.navigate(site.url("/spa"), 500);
      assertTrue(s.text().contains("loading"), s.text());
      assertFalse(s.text().contains("42"), s.text());
      assertThrows(BrowserException.class, () -> s.evaluate("1 + 1", 1_000));
    }
  }

  @Test
  void returnsRawBodyForJson() {
    try (BrowserSession s = new BrowserSession(new SessionOptions())) {
      NavigationResult nav = s.navigate(site.url("/api/product"), 0);
      assertTrue(nav.contentType().contains("json"));
      assertEquals("{\"name\":\"Widget\",\"price\":42}", s.content());
      assertNull(s.title());
    }
  }

  @Test
  void findClickWaitAndRead() {
    try (BrowserSession s = new BrowserSession(new SessionOptions())) {
      s.navigate(site.url("/shop"), 0);
      JsonElement links = s.evaluate(
          "Array.from(document.querySelectorAll('.product-link')).map(function (a) { return a.textContent; })", 5_000);
      assertEquals(2, links.getAsJsonArray().size());

      NavigationResult nav = s.click(".product-link", true, 10_000, 1_000);
      assertEquals(site.url("/product/1"), nav.url());
      assertEquals(200, nav.status());
      s.waitForFunction("document.querySelector('.price').textContent !== ''", 5_000);
      JsonObject product = s.evaluate("({name: document.getElementById('name').textContent,"
          + " price: Number(document.querySelector('.price').textContent), title: document.title})", 5_000)
          .getAsJsonObject();
      assertEquals("Coffee beans", product.get("name").getAsString());
      assertEquals(12000, product.get("price").getAsInt());
      assertEquals("Coffee beans", product.get("title").getAsString());
    }
  }

  @Test
  void scriptNavigationIsAwaited() {
    try (BrowserSession s = new BrowserSession(new SessionOptions())) {
      s.navigate(site.url("/shop"), 0);
      NavigationResult nav = s.evaluateAndWaitForNavigation(
          "document.querySelectorAll('.product-link')[1].click()", 10_000, 1_000);
      assertEquals(site.url("/product/2"), nav.url());

      s.navigate(site.url("/shop"), 0);
      NavigationResult later = s.evaluateAndWaitForNavigation("document.getElementById('later').click()", 10_000, 1_000);
      assertEquals(site.url("/product/2"), later.url());
      assertEquals("Tea leaves", s.evaluate("document.getElementById('name').textContent", 5_000).getAsString());

      s.navigate(site.url("/shop"), 0);
      assertTrue(s.evaluate("(function () { location.href = '/product/1'; return 1; })()", 5_000).isJsonNull());
      BrowserException none = assertThrows(BrowserException.class,
          () -> s.evaluateAndWaitForNavigation("1 + 1", 300, 0));
      assertEquals(BrowserException.Kind.TIMEOUT, none.kind());
    }
  }

  @Test
  void clickNeedsMatchingElement() {
    try (BrowserSession s = new BrowserSession(new SessionOptions())) {
      s.navigate(site.url("/shop"), 0);
      assertEquals(BrowserException.Kind.BAD_REQUEST,
          assertThrows(BrowserException.class, () -> s.click(".missing", true, 1_000, 0)).kind());
    }
  }

  @Test
  void runsScriptsOnBlankAndDataPages() {
    try (BrowserSession s = new BrowserSession(new SessionOptions())) {
      assertEquals(3, s.evaluate("1 + 2", 5_000).getAsInt());
      s.navigate("data:text/html,<title>D</title><p id='x'>hi</p>", 0);
      assertEquals("hi", s.evaluate("document.getElementById('x').textContent", 5_000).getAsString());
    }
    assertThrows(BrowserException.class, () -> BrowserSession.toUrl("ftp://x"));
  }

  @Test
  void framesDoNotReplaceTheMainPage() {
    try (BrowserSession s = new BrowserSession(new SessionOptions())) {
      NavigationResult nav = s.navigate(site.url("/framed"), 1_000);
      assertEquals(site.url("/framed"), nav.url());
      assertEquals("Main page", s.title());
      assertEquals("main", s.evaluate("document.getElementById('main').textContent", 5_000).getAsString());
      assertEquals(1, s.evaluate("document.querySelectorAll('iframe').length", 5_000).getAsInt());
    }
  }

  @Test
  void defaultHeadersLookLikeChrome() {
    try (BrowserSession s = new BrowserSession(new SessionOptions())) {
      s.navigate(site.url("/headers"), 0);
      String text = s.text();
      assertTrue(text.contains("Chrome/"), text);
      assertTrue(text.contains("accept-language: en-US,en;q=0.9"), text);
    }
  }

  @Test
  void postsBodies() {
    try (BrowserSession s = new BrowserSession(new SessionOptions())) {
      NavigationResult nav = s.navigate(site.url("/headers"), "POST", "q=coffee",
          java.util.Map.of("Content-Type", "application/x-www-form-urlencoded"), 0);
      assertEquals(200, nav.status());
      assertTrue(s.text().contains("content-type: application/x-www-form-urlencoded"), s.text());
      assertEquals(BrowserException.Kind.BAD_REQUEST, assertThrows(BrowserException.class,
          () -> s.navigate(site.url("/headers"), "GET", "q=1", null, 0)).kind());
    }
  }

  @Test
  void parsesProxyUrls() {
    ProxySpec p = ProxySpec.parse("user:p%40ss@10.0.0.1:3128");
    assertEquals("http", p.scheme());
    assertEquals("10.0.0.1", p.host());
    assertEquals(3128, p.port());
    assertEquals("user", p.username());
    assertEquals("p@ss", p.password());
    assertFalse(p.toString().contains("p@ss"));
    assertTrue(ProxySpec.parse("socks5://127.0.0.1:1080").isSocks());
    assertEquals("a+b#c!", ProxySpec.parse("http://u:a+b%23c!@h:1").password());
    assertNull(ProxySpec.parse(" "));
    assertThrows(BrowserException.class, () -> ProxySpec.parse("ftp://h:1"));
    assertThrows(BrowserException.class, () -> ProxySpec.parse("http://nohost"));
  }

  @Test
  void globPatterns() {
    assertTrue(RequestFilter.toPattern("*google-analytics*").matcher("https://www.google-analytics.com/a.js").find());
    assertTrue(RequestFilter.toPattern("https://x.com/*.png").matcher("https://x.com/a/b.png").find());
    assertFalse(RequestFilter.toPattern("https://x.com/*.png").matcher("https://y.com/a.png").find());
    assertTrue(RequestFilter.toPattern("doubleclick").matcher("https://ad.doubleclick.net/x").find());
  }
}
