package mn.stora.headless.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import mn.stora.headless.BrowserException;
import mn.stora.headless.BrowserSession;
import mn.stora.headless.Json;
import mn.stora.headless.NavigationResult;
import mn.stora.headless.SessionOptions;

/** Turns HTTP JSON bodies into browser calls and results back into JSON. */
final class Requests {

  static final long DEFAULT_JS_WAIT_MS = 1000;
  static final long DEFAULT_WAIT_TIMEOUT_MS = 10_000;
  static final long DEFAULT_SCRIPT_TIMEOUT_MS = 10_000;
  private static final Set<String> RETURN_FIELDS = Set.of("html", "text", "cookies", "headers");

  private Requests() {
  }

  /** Session-level options shared by {@code /render} and {@code POST /sessions}. */
  static SessionOptions sessionOptions(JsonObject body) {
    SessionOptions o = new SessionOptions()
        .emulation(SessionOptions.Emulation.parse(Json.str(body, "browser")))
        .userAgent(Json.str(body, "userAgent"))
        .acceptLanguage(Json.str(body, "acceptLanguage"))
        .timezone(Json.str(body, "timezone"))
        .proxy(Json.str(body, "proxy"));
    Boolean javascript = Json.bool(body, "javascript");
    if (javascript != null) {
      o.javaScript(javascript);
    }
    Boolean css = Json.bool(body, "css");
    if (css != null) {
      o.css(css);
    }
    Boolean images = Json.bool(body, "images");
    if (images != null) {
      o.downloadImages(images);
    }
    Boolean insecure = Json.bool(body, "insecureSsl");
    if (insecure != null) {
      o.insecureSsl(insecure);
    }
    Integer timeout = Json.integer(body, "timeoutMs");
    if (timeout != null) {
      o.timeoutMs(timeout);
    }
    Long scriptTimeout = Json.lng(body, "scriptTimeoutMs");
    if (scriptTimeout != null) {
      o.scriptTimeoutMs(scriptTimeout);
    }
    stringMap(body, "headers").forEach(o::header);
    for (String pattern : stringList(body, "blockUrls")) {
      o.blockUrl(pattern);
    }
    for (String type : stringList(body, "blockTypes")) {
      o.blockType(type);
    }
    return o;
  }

  /**
   * Loads {@code url} (if given), waits as requested, runs the script and builds the response.
   * {@code requestHeaders} apply to this navigation only.
   */
  static JsonObject run(BrowserSession session, JsonObject body, Map<String, String> requestHeaders) {
    long started = System.currentTimeMillis();
    JsonObject out = Json.obj("ok", true);
    String url = Json.str(body, "url");
    long jsWaitMs = orDefault(Json.lng(body, "jsWaitMs"), DEFAULT_JS_WAIT_MS);
    if (url != null) {
      session.navigate(url, Json.str(body, "method"), Json.str(body, "body"), requestHeaders, jsWaitMs);
    }

    long waitTimeout = orDefault(Json.lng(body, "waitTimeoutMs"), DEFAULT_WAIT_TIMEOUT_MS);
    boolean failOnWaitTimeout = !Boolean.FALSE.equals(Json.bool(body, "failOnWaitTimeout"));
    try {
      String selector = Json.str(body, "waitForSelector");
      if (selector != null) {
        session.waitForSelector(selector, waitTimeout);
      }
      String condition = Json.str(body, "waitForFunction");
      if (condition != null) {
        session.waitForFunction(condition, waitTimeout);
      }
    } catch (BrowserException e) {
      if (e.kind() != BrowserException.Kind.TIMEOUT || failOnWaitTimeout) {
        throw e;
      }
      out.addProperty("waitTimedOut", true);
    }

    long scriptTimeout = orDefault(Json.lng(body, "scriptTimeoutMs"), DEFAULT_SCRIPT_TIMEOUT_MS);
    String script = Json.str(body, "script");
    String function = Json.str(body, "function");
    if (script != null && function != null) {
      throw new BrowserException(BrowserException.Kind.BAD_REQUEST, "Send either script or function, not both");
    }
    if (script != null) {
      out.add("result", session.evaluate(script, scriptTimeout));
    } else if (function != null) {
      JsonArray args = Json.array(body, "args");
      List<JsonElement> list = new ArrayList<>();
      if (args != null) {
        args.forEach(list::add);
      }
      out.add("result", session.callFunction(function, list, scriptTimeout));
    }

    NavigationResult current = session.current();
    out.addProperty("url", current.url());
    out.addProperty("status", current.status());
    out.addProperty("contentType", current.contentType());
    String title = session.title();
    if (title != null) {
      out.addProperty("title", title);
    }
    Set<String> fields = returnFields(body);
    if (fields.contains("html")) {
      out.addProperty("html", session.content());
    }
    if (fields.contains("text")) {
      out.addProperty("text", session.text());
    }
    if (fields.contains("cookies")) {
      out.add("cookies", session.cookies());
    }
    if (fields.contains("headers")) {
      JsonArray headers = new JsonArray();
      for (Map.Entry<String, String> h : current.headers()) {
        headers.add(Json.obj("name", h.getKey(), "value", h.getValue()));
      }
      out.add("headers", headers);
    }
    out.add("stats", session.stats());
    out.addProperty("timingMs", System.currentTimeMillis() - started);
    return out;
  }

  /** Which parts of the page to return; default is just the HTML, an empty list returns none. */
  static Set<String> returnFields(JsonObject body) {
    if (body == null || !body.has("return") || body.get("return").isJsonNull()) {
      return Set.of("html");
    }
    List<String> requested = stringList(body, "return");
    Set<String> out = new java.util.HashSet<>();
    for (String r : requested) {
      String f = r.toLowerCase(Locale.ROOT);
      if (!RETURN_FIELDS.contains(f)) {
        throw new BrowserException(BrowserException.Kind.BAD_REQUEST,
            "Unknown return field '" + r + "'; use " + RETURN_FIELDS);
      }
      out.add(f);
    }
    return out;
  }

  static Map<String, String> stringMap(JsonObject body, String key) {
    Map<String, String> out = new LinkedHashMap<>();
    JsonObject o = Json.object(body, key);
    if (o == null) {
      if (body != null && body.has(key) && !body.get(key).isJsonNull()) {
        throw new BrowserException(BrowserException.Kind.BAD_REQUEST, key + " must be an object");
      }
      return out;
    }
    for (Map.Entry<String, JsonElement> e : o.entrySet()) {
      if (!e.getValue().isJsonNull()) {
        out.put(e.getKey(), e.getValue().getAsString());
      }
    }
    return out;
  }

  static List<String> stringList(JsonObject body, String key) {
    List<String> out = new ArrayList<>();
    JsonArray a = Json.array(body, key);
    if (a == null) {
      if (body != null && body.has(key) && !body.get(key).isJsonNull()) {
        throw new BrowserException(BrowserException.Kind.BAD_REQUEST, key + " must be an array");
      }
      return out;
    }
    for (JsonElement e : a) {
      if (!e.isJsonNull()) {
        out.add(e.getAsString());
      }
    }
    return out;
  }

  private static long orDefault(Long value, long fallback) {
    return value == null ? fallback : value;
  }
}
