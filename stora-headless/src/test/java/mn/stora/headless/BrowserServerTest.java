package mn.stora.headless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import mn.stora.headless.server.BrowserServer;
import mn.stora.headless.server.ServerConfig;

class BrowserServerTest {

  private static final String TOKEN = "test-token";
  private static TestSite site;
  private static TestProxy proxy;
  private static BrowserServer server;
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @BeforeAll
  static void start() throws Exception {
    site = new TestSite();
    proxy = new TestProxy("scraper", "s3cret");
    ServerConfig config = new ServerConfig();
    config.port = 0;
    config.token = TOKEN;
    config.maxSessions = 2;
    config.queueTimeoutMs = 500;
    server = new BrowserServer(config).start();
  }

  @AfterAll
  static void stop() throws Exception {
    server.close();
    site.close();
    proxy.close();
  }

  private static Response call(String method, String path, JsonObject body, String token) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(Json.GSON.toJson(body)))
        .header("Content-Type", "application/json");
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    return new Response(r.statusCode(), Json.parse(r.body()));
  }

  private static Response call(String method, String path, JsonObject body) throws Exception {
    return call(method, path, body, TOKEN);
  }

  private static final class Response {
    final int status;
    final JsonObject json;

    Response(int status, JsonObject json) {
      this.status = status;
      this.json = json;
    }
  }

  @Test
  void healthNeedsNoToken() throws Exception {
    Response r = call("GET", "/health", null, null);
    assertEquals(200, r.status);
    assertTrue(r.json.get("ok").getAsBoolean());
    assertEquals(2, r.json.get("maxSessions").getAsInt());
  }

  @Test
  void servesTesterPageWithoutToken() throws Exception {
    for (String path : new String[] {"/", "/tester"}) {
      HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
          .GET().build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, r.statusCode());
      assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
      assertTrue(r.body().contains("<title>stora-headless tester</title>"));
      assertTrue(r.body().contains("Object.assign({ url: url, return: ['html'] }"), "tester script must not be filtered");
    }
  }

  @Test
  void rejectsMissingOrWrongToken() throws Exception {
    assertEquals(401, call("POST", "/render", Json.obj("url", site.url("/spa")), null).status);
    assertEquals(401, call("POST", "/render", Json.obj("url", site.url("/spa")), "wrong").status);
  }

  @Test
  void renderRunsScriptAndReturnsParts() throws Exception {
    JsonArray ret = new JsonArray();
    ret.add("html");
    ret.add("text");
    ret.add("cookies");
    ret.add("headers");
    Response r = call("POST", "/render", Json.obj(
        "url", site.url("/spa"),
        "waitForSelector", ".price",
        "script", "({price: document.querySelector('.price').textContent, title: document.title})",
        "return", ret));
    assertEquals(200, r.status, r.json.toString());
    assertTrue(r.json.get("ok").getAsBoolean());
    assertEquals(200, r.json.get("status").getAsInt());
    assertEquals("42", r.json.getAsJsonObject("result").get("price").getAsString());
    assertTrue(r.json.get("html").getAsString().contains("class=\"price\""));
    assertTrue(r.json.get("text").getAsString().contains("42"));
    assertTrue(r.json.has("cookies"));
    assertTrue(r.json.getAsJsonArray("headers").size() > 0);
    assertTrue(r.json.getAsJsonObject("stats").get("requests").getAsInt() >= 2);
  }

  @Test
  void renderThroughProxyWithFunctionArgs() throws Exception {
    JsonArray args = new JsonArray();
    args.add("#headers");
    Response r = call("POST", "/render", Json.obj(
        "url", site.url("/headers"),
        "proxy", proxy.url("scraper", "s3cret"),
        "userAgent", "StoraTest/2.0",
        "function", "function (sel) { return document.querySelector(sel).textContent; }",
        "args", args,
        "return", new JsonArray()));
    assertEquals(200, r.status, r.json.toString());
    String headers = r.json.get("result").getAsString();
    assertTrue(headers.contains("x-test-proxy: yes"), headers);
    assertTrue(headers.contains("user-agent: StoraTest/2.0"), headers);
    assertFalse(r.json.has("html"));
  }

  @Test
  void mapsErrorsToStatusCodes() throws Exception {
    assertEquals(400, call("POST", "/render", Json.obj("nourl", true)).status);
    assertEquals(400, call("POST", "/render", Json.obj("url", "not a url")).status);
    Response script = call("POST", "/render", Json.obj("url", site.url("/spa"), "script", "nope("));
    assertEquals(422, script.status);
    assertEquals("SCRIPT", script.json.get("kind").getAsString());
    Response wait = call("POST", "/render", Json.obj("url", site.url("/spa"), "waitForSelector", ".missing",
        "waitTimeoutMs", 200));
    assertEquals(504, wait.status);
    Response lenient = call("POST", "/render", Json.obj("url", site.url("/spa"), "waitForSelector", ".missing",
        "waitTimeoutMs", 200, "failOnWaitTimeout", false));
    assertEquals(200, lenient.status);
    assertTrue(lenient.json.get("waitTimedOut").getAsBoolean());
    Response unreachable = call("POST", "/render", Json.obj("url", "http://127.0.0.1:1/", "timeoutMs", 2000));
    assertEquals(502, unreachable.status, unreachable.json.toString());
    assertEquals(404, call("GET", "/nope", null).status);
  }

  @Test
  void sessionLifecycle() throws Exception {
    Response open = call("POST", "/sessions", Json.obj("url", site.url("/cookie")));
    assertEquals(200, open.status, open.json.toString());
    String id = open.json.get("id").getAsString();
    try {
      Response cookies = call("GET", "/sessions/" + id + "/cookies", null);
      assertEquals(2, cookies.json.getAsJsonArray("cookies").size());

      Response go = call("POST", "/sessions/" + id + "/goto", Json.obj("url", site.url("/spa"), "jsWaitMs", 0));
      assertEquals(200, go.status, go.json.toString());

      Response wait = call("POST", "/sessions/" + id + "/wait", Json.obj("waitForSelector", ".price"));
      assertEquals(200, wait.status, wait.json.toString());

      Response eval = call("POST", "/sessions/" + id + "/evaluate",
          Json.obj("script", "document.querySelector('.price').textContent"));
      assertEquals("42", eval.json.get("result").getAsString());

      Response content = call("GET", "/sessions/" + id + "/content", null);
      assertTrue(content.json.get("html").getAsString().contains("price"));

      Response text = call("GET", "/sessions/" + id + "/text", null);
      assertTrue(text.json.get("text").getAsString().contains("42"));

      Response list = call("GET", "/sessions", null);
      assertTrue(list.json.getAsJsonArray("sessions").toString().contains(id));
    } finally {
      assertEquals(200, call("DELETE", "/sessions/" + id, null).status);
    }
    assertEquals(404, call("GET", "/sessions/" + id + "/content", null).status);
    assertEquals(404, call("DELETE", "/sessions/" + id, null).status);
  }

  /** The main scraper flow: open a link, run scripts one after another, then close. */
  @Test
  void openRunScriptsThenClose() throws Exception {
    Response open = call("POST", "/sessions", Json.obj(
        "url", site.url("/spa"),
        "proxy", proxy.url("scraper", "s3cret"),
        "waitForSelector", ".price",
        "return", new JsonArray()));
    assertEquals(200, open.status, open.json.toString());
    assertEquals(200, open.json.get("status").getAsInt());
    String id = open.json.get("id").getAsString();

    Response first = call("POST", "/sessions/" + id + "/evaluate", Json.obj(
        "script", "(function () { window.seen = document.querySelector('.price').textContent; return window.seen; })()"));
    assertEquals("42", first.json.get("result").getAsString());

    Response second = call("POST", "/sessions/" + id + "/evaluate", Json.obj(
        "script", "fetch('/api/product').then(function (r) { return r.json(); })"
            + ".then(function (p) { return {name: p.name, seenBefore: window.seen}; })"));
    assertEquals("Widget", second.json.getAsJsonObject("result").get("name").getAsString());
    assertEquals("42", second.json.getAsJsonObject("result").get("seenBefore").getAsString());

    assertEquals(200, call("DELETE", "/sessions/" + id, null).status);
    assertEquals(404, call("POST", "/sessions/" + id + "/evaluate", Json.obj("script", "1")).status);
  }

  /** Find a product with a script, click it, wait for the product page, read it, close. */
  @Test
  void clickProductAndReadIt() throws Exception {
    Response open = call("POST", "/sessions", Json.obj("url", site.url("/shop"), "return", new JsonArray()));
    String id = open.json.get("id").getAsString();
    try {
      Response names = call("POST", "/sessions/" + id + "/evaluate", Json.obj("script",
          "Array.from(document.querySelectorAll('.product-link')).map(function (a) { return a.href; })"));
      assertEquals(2, names.json.getAsJsonArray("result").size());

      Response clicked = call("POST", "/sessions/" + id + "/click", Json.obj(
          "selector", ".product-link",
          "waitForFunction", "document.querySelector('.price').textContent !== ''",
          "then", "({name: document.getElementById('name').textContent, price: document.querySelector('.price').textContent})"));
      assertEquals(200, clicked.status, clicked.json.toString());
      assertTrue(clicked.json.get("navigated").getAsBoolean());
      assertEquals(site.url("/product/1"), clicked.json.get("url").getAsString());
      assertEquals("Coffee beans", clicked.json.getAsJsonObject("result").get("name").getAsString());

      call("POST", "/sessions/" + id + "/goto", Json.obj("url", site.url("/shop"), "return", new JsonArray()));
      Response viaScript = call("POST", "/sessions/" + id + "/evaluate", Json.obj(
          "script", "document.querySelectorAll('.product-link')[1].click()",
          "waitForNavigation", true,
          "waitForSelector", "h1#name"));
      assertEquals(200, viaScript.status, viaScript.json.toString());
      assertEquals(site.url("/product/2"), viaScript.json.get("url").getAsString());

      Response read = call("POST", "/sessions/" + id + "/evaluate", Json.obj("script", "document.title"));
      assertEquals("Tea leaves", read.json.get("result").getAsString());
    } finally {
      call("DELETE", "/sessions/" + id, null);
    }
  }

  @Test
  void limitsConcurrentSessions() throws Exception {
    String a = call("POST", "/sessions", new JsonObject()).json.get("id").getAsString();
    String b = call("POST", "/sessions", new JsonObject()).json.get("id").getAsString();
    try {
      Response busy = call("POST", "/sessions", new JsonObject());
      assertEquals(503, busy.status, busy.json.toString());
    } finally {
      call("DELETE", "/sessions/" + a, null);
      call("DELETE", "/sessions/" + b, null);
    }
    String c = call("POST", "/sessions", new JsonObject()).json.get("id").getAsString();
    call("DELETE", "/sessions/" + c, null);
  }
}
