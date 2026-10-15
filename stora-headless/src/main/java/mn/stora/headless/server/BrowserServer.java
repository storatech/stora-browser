package mn.stora.headless.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import mn.stora.headless.BrowserException;
import mn.stora.headless.BrowserSession;
import mn.stora.headless.Json;
import mn.stora.headless.Version;

/**
 * JSON-over-HTTP API around {@link BrowserSession}. Uses only the JDK's built-in HTTP server.
 *
 * <pre>
 * GET    /  or /tester                 browser-like tester page (static, no token)
 * GET    /health
 * POST   /render                       one-shot: open, load, wait, run script, return, close
 * POST   /sessions                     open a session (optionally load a URL)
 * GET    /sessions                     list open sessions
 * POST   /sessions/{id}/goto           load a URL in the session
 * POST   /sessions/{id}/evaluate       run a script or function (waitForNavigation for scripts that navigate)
 * POST   /sessions/{id}/click          click an element, wait for the next page
 * POST   /sessions/{id}/wait           wait for a selector or condition
 * GET    /sessions/{id}/content        current HTML
 * GET    /sessions/{id}/text           current visible text
 * GET    /sessions/{id}/cookies        session cookies
 * POST   /sessions/{id}/cookies        add cookies
 * DELETE /sessions/{id}                close the session
 * </pre>
 */
public final class BrowserServer implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger("stora.headless.server");

  private static final class Entry {
    final BrowserSession session;
    final long created = System.currentTimeMillis();
    volatile long lastUsed = System.currentTimeMillis();

    Entry(BrowserSession session) {
      this.session = session;
    }
  }

  private final ServerConfig config;
  private final Semaphore slots;
  private final Map<String, Entry> sessions = new ConcurrentHashMap<>();
  private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "session-reaper");
    t.setDaemon(true);
    return t;
  });
  private ExecutorService workers;
  private HttpServer server;

  public BrowserServer(ServerConfig config) {
    this.config = config;
    this.slots = new Semaphore(config.maxSessions, true);
  }

  public synchronized BrowserServer start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(config.host, config.port), 64);
    workers = Executors.newFixedThreadPool(config.maxSessions + 8, r -> {
      Thread t = new Thread(r, "http-worker");
      t.setDaemon(true);
      return t;
    });
    server.setExecutor(workers);
    server.createContext("/", this::handle);
    server.start();
    reaper.scheduleAtFixedRate(this::closeIdleSessions, 10, 10, TimeUnit.SECONDS);
    LOG.info(() -> "stora-headless " + Version.VERSION + " listening on http://" + config.host + ":" + port()
        + " (max sessions " + config.maxSessions + (config.token == null ? ", no token" : ", token required") + ")");
    return this;
  }

  public int port() {
    return server.getAddress().getPort();
  }

  @Override
  public synchronized void close() {
    if (server != null) {
      server.stop(0);
    }
    reaper.shutdownNow();
    sessions.keySet().forEach(this::closeSession);
    if (workers != null) {
      workers.shutdownNow();
    }
  }

  private void handle(HttpExchange ex) throws IOException {
    long started = System.currentTimeMillis();
    String method = ex.getRequestMethod();
    String path = ex.getRequestURI().getPath();
    int status;
    try {
      if (!isPublic(method, path) && !authorized(ex)) {
        status = send(ex, 401, error("Missing or invalid token", "UNAUTHORIZED"));
      } else {
        status = route(ex, method, path);
      }
    } catch (BrowserException e) {
      status = send(ex, httpStatus(e.kind()), error(e.getMessage(), e.kind().name()));
    } catch (JsonParseException | IllegalStateException | UnsupportedOperationException | NumberFormatException e) {
      status = send(ex, 400, error("Invalid JSON: " + e.getMessage(), "BAD_REQUEST"));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Unexpected error on " + method + " " + path, e);
      status = send(ex, 500, error(String.valueOf(e), "INTERNAL"));
    } finally {
      ex.close();
    }
    long ms = System.currentTimeMillis() - started;
    int finalStatus = status;
    LOG.info(() -> method + " " + path + " " + finalStatus + " " + ms + "ms");
  }

  private int route(HttpExchange ex, String method, String path) throws IOException {
    String[] parts = path.replaceAll("/+$", "").split("/");
    // parts[0] is "" because the path starts with '/'.
    if (parts.length == 2 && parts[1].equals("health") && method.equals("GET")) {
      Runtime rt = Runtime.getRuntime();
      long mb = 1024 * 1024;
      return send(ex, 200, Json.obj("ok", true, "version", Version.VERSION, "engine", Version.ENGINE,
          "sessions", sessions.size(), "maxSessions", config.maxSessions,
          "freeSlots", slots.availablePermits(),
          "heapUsedMb", (rt.totalMemory() - rt.freeMemory()) / mb, "heapMaxMb", rt.maxMemory() / mb));
    }
    if (method.equals("GET") && (path.equals("/") || path.equals("/tester") || path.equals("/tester/"))) {
      return sendTester(ex);
    }
    if (parts.length == 2 && parts[1].equals("render") && method.equals("POST")) {
      return send(ex, 200, render(readJson(ex)));
    }
    if (parts.length >= 2 && parts[1].equals("sessions")) {
      if (parts.length == 2 && method.equals("POST")) {
        return send(ex, 200, openSession(readJson(ex)));
      }
      if (parts.length == 2 && method.equals("GET")) {
        return send(ex, 200, listSessions());
      }
      if (parts.length >= 3) {
        String id = parts[2];
        if (parts.length == 3 && method.equals("DELETE")) {
          boolean existed = closeSession(id);
          return send(ex, existed ? 200 : 404, existed ? Json.obj("ok", true) : error("No session " + id, "NOT_FOUND"));
        }
        Entry entry = sessions.get(id);
        if (entry == null) {
          return send(ex, 404, error("No session " + id, "NOT_FOUND"));
        }
        entry.lastUsed = System.currentTimeMillis();
        try {
          if (parts.length == 4) {
            return sessionAction(ex, method, parts[3], entry.session);
          }
        } finally {
          entry.lastUsed = System.currentTimeMillis();
        }
      }
    }
    return send(ex, 404, error("No route for " + method + " " + path, "NOT_FOUND"));
  }

  private int sessionAction(HttpExchange ex, String method, String action, BrowserSession session)
      throws IOException {
    switch (method + " " + action) {
      case "POST goto": {
        JsonObject body = readJson(ex);
        if (Json.str(body, "url") == null) {
          throw new BrowserException(BrowserException.Kind.BAD_REQUEST, "url is required");
        }
        return send(ex, 200, Requests.run(session, body, Requests.stringMap(body, "headers")));
      }
      case "POST evaluate": {
        JsonObject body = readJson(ex);
        if (Json.str(body, "script") == null && Json.str(body, "function") == null) {
          throw new BrowserException(BrowserException.Kind.BAD_REQUEST, "script or function is required");
        }
        if (Boolean.TRUE.equals(Json.bool(body, "waitForNavigation"))) {
          if (Json.str(body, "script") == null) {
            throw new BrowserException(BrowserException.Kind.BAD_REQUEST, "waitForNavigation needs script");
          }
          long started = System.currentTimeMillis();
          session.evaluateAndWaitForNavigation(Json.str(body, "script"), navigationTimeout(body), jsWait(body));
          return send(ex, 200, afterNavigation(session, body, true, started));
        }
        JsonObject result = Requests.run(session, scriptOnly(body), null);
        return send(ex, 200, Json.obj("ok", true, "result", result.get("result"), "url", result.get("url")));
      }
      case "POST click": {
        JsonObject body = readJson(ex);
        String selector = Json.str(body, "selector");
        if (selector == null) {
          throw new BrowserException(BrowserException.Kind.BAD_REQUEST, "selector is required");
        }
        boolean waitForNavigation = !Boolean.FALSE.equals(Json.bool(body, "waitForNavigation"));
        long started = System.currentTimeMillis();
        session.click(selector, waitForNavigation, navigationTimeout(body), jsWait(body));
        return send(ex, 200, afterNavigation(session, body, waitForNavigation, started));
      }
      case "POST wait": {
        JsonObject body = readJson(ex);
        JsonObject waitOnly = new JsonObject();
        for (String key : new String[] {"waitForSelector", "waitForFunction", "waitTimeoutMs", "failOnWaitTimeout"}) {
          if (body.has(key)) {
            waitOnly.add(key, body.get(key));
          }
        }
        waitOnly.add("return", new JsonArray());
        JsonObject result = Requests.run(session, waitOnly, null);
        return send(ex, 200, Json.obj("ok", true, "url", result.get("url"),
            "waitTimedOut", result.has("waitTimedOut") ? result.get("waitTimedOut") : null));
      }
      case "GET content":
        return send(ex, 200, Json.obj("ok", true, "url", session.current().url(),
            "status", session.current().status(), "html", session.content()));
      case "GET text":
        return send(ex, 200, Json.obj("ok", true, "url", session.current().url(), "text", session.text()));
      case "GET cookies":
        return send(ex, 200, Json.obj("ok", true, "cookies", session.cookies()));
      case "POST cookies": {
        JsonObject body = readJson(ex);
        session.setCookies(Json.array(body, "cookies"));
        return send(ex, 200, Json.obj("ok", true, "cookies", session.cookies()));
      }
      default:
        return send(ex, 404, error("No route for " + method + " /sessions/{id}/" + action, "NOT_FOUND"));
    }
  }

  /**
   * Response after a click or navigating script: the new page, plus optional waits, a follow-up
   * {@code then} script and {@code return} parts (none by default).
   */
  private static JsonObject afterNavigation(BrowserSession session, JsonObject body, boolean navigated,
      long startedMs) {
    JsonObject follow = new JsonObject();
    for (String key : new String[] {"waitForSelector", "waitForFunction", "waitTimeoutMs", "failOnWaitTimeout",
        "scriptTimeoutMs", "return"}) {
      if (body.has(key)) {
        follow.add(key, body.get(key));
      }
    }
    if (body.has("then")) {
      follow.add("script", body.get("then"));
    }
    if (!follow.has("return")) {
      follow.add("return", new JsonArray());
    }
    JsonObject out = Requests.run(session, follow, null);
    out.addProperty("navigated", navigated);
    out.addProperty("timingMs", System.currentTimeMillis() - startedMs);
    return out;
  }

  private static long navigationTimeout(JsonObject body) {
    Long v = Json.lng(body, "navigationTimeoutMs");
    return v == null ? 30_000 : v;
  }

  private static long jsWait(JsonObject body) {
    Long v = Json.lng(body, "jsWaitMs");
    return v == null ? Requests.DEFAULT_JS_WAIT_MS : v;
  }

  private static JsonObject scriptOnly(JsonObject body) {
    JsonObject o = new JsonObject();
    for (String key : new String[] {"script", "function", "args", "scriptTimeoutMs"}) {
      if (body.has(key)) {
        o.add(key, body.get(key));
      }
    }
    o.add("return", new JsonArray());
    return o;
  }

  private JsonObject render(JsonObject body) {
    if (Json.str(body, "url") == null) {
      throw new BrowserException(BrowserException.Kind.BAD_REQUEST, "url is required");
    }
    acquireSlot();
    try (BrowserSession session = new BrowserSession(Requests.sessionOptions(body))) {
      session.setCookies(Json.array(body, "cookies"));
      return Requests.run(session, body, null);
    } finally {
      slots.release();
    }
  }

  private JsonObject openSession(JsonObject body) {
    acquireSlot();
    BrowserSession session;
    try {
      session = new BrowserSession(Requests.sessionOptions(body));
    } catch (RuntimeException e) {
      slots.release();
      throw e;
    }
    String id = UUID.randomUUID().toString();
    sessions.put(id, new Entry(session));
    try {
      session.setCookies(Json.array(body, "cookies"));
      JsonObject out;
      if (Json.str(body, "url") != null) {
        out = Requests.run(session, body, null);
      } else {
        out = Json.obj("ok", true);
      }
      out.addProperty("id", id);
      return out;
    } catch (RuntimeException e) {
      closeSession(id);
      throw e;
    }
  }

  private JsonObject listSessions() {
    JsonArray list = new JsonArray();
    long now = System.currentTimeMillis();
    sessions.forEach((id, e) -> list.add(Json.obj("id", id, "ageMs", now - e.created,
        "idleMs", now - e.lastUsed)));
    return Json.obj("ok", true, "sessions", list);
  }

  private boolean closeSession(String id) {
    Entry entry = sessions.remove(id);
    if (entry == null) {
      return false;
    }
    try {
      entry.session.close();
    } catch (RuntimeException e) {
      LOG.log(Level.FINE, "Error closing session " + id, e);
    } finally {
      slots.release();
    }
    return true;
  }

  private void closeIdleSessions() {
    long cutoff = System.currentTimeMillis() - config.sessionTtlSeconds * 1000L;
    sessions.forEach((id, e) -> {
      if (e.lastUsed < cutoff) {
        LOG.info(() -> "Closing idle session " + id);
        closeSession(id);
      }
    });
  }

  private void acquireSlot() {
    try {
      if (!slots.tryAcquire(config.queueTimeoutMs, TimeUnit.MILLISECONDS)) {
        throw new BrowserException(BrowserException.Kind.BROWSER, "All " + config.maxSessions
            + " browser slots are busy; try again later");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BrowserException(BrowserException.Kind.BROWSER, "Interrupted", e);
    }
  }

  /** The health check and the static tester page need no token; the API calls they make do. */
  private static boolean isPublic(String method, String path) {
    return method.equals("GET")
        && (path.equals("/health") || path.equals("/") || path.equals("/tester") || path.equals("/tester/"));
  }

  private static int sendTester(HttpExchange ex) throws IOException {
    byte[] bytes;
    try (InputStream in = BrowserServer.class.getResourceAsStream("/tester.html")) {
      if (in == null) {
        return send(ex, 404, error("Tester page not packaged", "NOT_FOUND"));
      }
      bytes = in.readAllBytes();
    }
    ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
    ex.getResponseHeaders().set("Cache-Control", "no-store");
    ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
    ex.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(bytes);
    }
    return 200;
  }

  private boolean authorized(HttpExchange ex) {
    if (config.token == null) {
      return true;
    }
    String header = ex.getRequestHeaders().getFirst("Authorization");
    String given = null;
    if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      given = header.substring(7).trim();
    } else if (ex.getRequestHeaders().getFirst("X-Api-Key") != null) {
      given = ex.getRequestHeaders().getFirst("X-Api-Key").trim();
    }
    return given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
        config.token.getBytes(StandardCharsets.UTF_8));
  }

  private JsonObject readJson(HttpExchange ex) throws IOException {
    byte[] bytes = readBody(ex.getRequestBody(), config.maxBodyBytes);
    if (bytes.length == 0) {
      return new JsonObject();
    }
    JsonObject body = Json.parse(new String(bytes, StandardCharsets.UTF_8));
    if (body == null) {
      return new JsonObject();
    }
    return body;
  }

  private static byte[] readBody(InputStream in, int max) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) > 0) {
      if (out.size() + n > max) {
        throw new BrowserException(BrowserException.Kind.BAD_REQUEST, "Request body larger than " + max + " bytes");
      }
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  private static int send(HttpExchange ex, int status, JsonObject body) throws IOException {
    byte[] bytes = Json.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(bytes);
    }
    return status;
  }

  private static JsonObject error(String message, String kind) {
    return Json.obj("ok", false, "error", message, "kind", kind);
  }

  static int httpStatus(BrowserException.Kind kind) {
    switch (kind) {
      case BAD_REQUEST:
        return 400;
      case NAVIGATION:
        return 502;
      case SCRIPT:
        return 422;
      case TIMEOUT:
        return 504;
      case BROWSER:
        return 503;
      default:
        return 500;
    }
  }
}
