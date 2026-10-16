package mn.stora.headless;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.htmlunit.BrowserVersion;
import org.htmlunit.DefaultCredentialsProvider;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.ScriptException;
import org.htmlunit.ScriptResult;
import org.htmlunit.SgmlPage;
import org.htmlunit.WebClient;
import org.htmlunit.WebClientOptions;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.WebWindow;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.http.Cookie;
import org.htmlunit.html.DomElement;
import org.htmlunit.util.UrlUtils;
import org.htmlunit.util.NameValuePair;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

/**
 * One isolated headless browser: its own cookies, proxy, headers and JavaScript engine. Pure Java
 * (HtmlUnit); no external browser process.
 *
 * <p>Not thread-safe across calls from different threads at once; methods are synchronized so a
 * session can be shared, but its operations run one at a time.
 */
public final class BrowserSession implements AutoCloseable {

  /** Strong references, so the JUL levels set in {@link #quietLibraryLogging()} are not lost to GC. */
  private static final List<Logger> LIBRARY_LOGGERS = new ArrayList<>();
  private static final AtomicLong EVAL_IDS = new AtomicLong();
  private static final long POLL_MS = 50;

  static {
    quietLibraryLogging();
  }

  private final WebClient client;
  /** The session's tab. Frames and pop-ups get their own windows and must not replace it. */
  private final WebWindow mainWindow;
  private final RequestFilter filter;
  private final ScriptErrors scriptErrors = new ScriptErrors();
  private final SessionOptions options;
  private NavigationResult lastNavigation;
  private volatile boolean closed;

  public BrowserSession(SessionOptions options) {
    this.options = options == null ? new SessionOptions() : options;
    this.client = new WebClient(browserVersion(this.options));
    this.mainWindow = client.getCurrentWindow().getTopWindow();
    configure(client, this.options);
    client.setJavaScriptErrorListener(scriptErrors);
    this.filter = new RequestFilter(client, this.options);
  }

  private static BrowserVersion browserVersion(SessionOptions o) {
    BrowserVersion base;
    switch (o.emulation()) {
      case EDGE:
        base = BrowserVersion.EDGE;
        break;
      case FIREFOX:
        base = BrowserVersion.FIREFOX;
        break;
      case FIREFOX_ESR:
        base = BrowserVersion.FIREFOX_ESR;
        break;
      default:
        base = BrowserVersion.CHROME;
    }
    if (o.userAgent() == null && o.acceptLanguage() == null && o.timezone() == null) {
      return base;
    }
    BrowserVersion.BrowserVersionBuilder builder = new BrowserVersion.BrowserVersionBuilder(base);
    if (o.userAgent() != null) {
      builder.setUserAgent(o.userAgent());
    }
    if (o.acceptLanguage() != null) {
      builder.setAcceptLanguageHeader(o.acceptLanguage());
      builder.setBrowserLanguage(o.acceptLanguage().split("[,;]")[0].trim());
    }
    if (o.timezone() != null) {
      builder.setSystemTimezone(TimeZone.getTimeZone(o.timezone()));
    }
    return builder.build();
  }

  private static void configure(WebClient client, SessionOptions o) {
    WebClientOptions w = client.getOptions();
    w.setJavaScriptEnabled(o.javaScript());
    w.setCssEnabled(o.css());
    w.setDownloadImages(o.downloadImages());
    w.setUseInsecureSSL(o.insecureSsl());
    w.setTimeout(o.timeoutMs());
    w.setRedirectEnabled(true);
    w.setThrowExceptionOnScriptError(false);
    w.setThrowExceptionOnFailingStatusCode(false);
    w.setPrintContentOnFailingStatusCode(false);
    w.setPopupBlockerEnabled(true);
    w.setGeolocationEnabled(false);
    w.setWebSocketEnabled(false);
    w.setFetchPolyfillEnabled(true);
    w.setHistorySizeLimit(5);
    client.setJavaScriptTimeout(o.scriptTimeoutMs());
    client.setAjaxController(new org.htmlunit.NicelyResynchronizingAjaxController());
    for (Map.Entry<String, String> h : o.headers().entrySet()) {
      client.addRequestHeader(h.getKey(), h.getValue());
    }
    ProxySpec proxy = o.proxy();
    if (proxy != null) {
      w.setProxyConfig(new org.htmlunit.ProxyConfig(proxy.host(), proxy.port(),
          proxy.isSocks() ? "http" : proxy.scheme(), proxy.isSocks()));
      if (proxy.hasCredentials()) {
        ((DefaultCredentialsProvider) client.getCredentialsProvider())
            .addCredentials(proxy.username(), proxy.password().toCharArray(), proxy.host(), proxy.port(), null);
      }
    }
  }

  /** Loads a URL with GET and lets page scripts run for up to {@code jsWaitMs}. */
  public synchronized NavigationResult navigate(String url, long jsWaitMs) {
    return navigate(url, "GET", null, null, jsWaitMs);
  }

  /**
   * Loads a URL. {@code jsWaitMs} is how long to keep running timers and async requests started by
   * the page after it loads; 0 returns right after the load event.
   */
  public synchronized NavigationResult navigate(String url, String method, String body,
      Map<String, String> headers, long jsWaitMs) {
    ensureOpen();
    HttpMethod httpMethod = httpMethod(method);
    WebRequest request = new WebRequest(toUrl(url), httpMethod);
    if (body != null) {
      if (httpMethod != HttpMethod.POST && httpMethod != HttpMethod.PUT && httpMethod != HttpMethod.PATCH) {
        throw new BrowserException(BrowserException.Kind.BAD_REQUEST, "A request body needs method POST, PUT or PATCH");
      }
      request.setRequestBody(body);
    }
    if (headers != null) {
      headers.forEach(request::setAdditionalHeader);
    }
    try {
      client.getPage(mainWindow, request);
    } catch (SocketTimeoutException e) {
      throw new BrowserException(BrowserException.Kind.TIMEOUT, "Loading " + url + " timed out", e);
    } catch (UnknownHostException e) {
      throw new BrowserException(BrowserException.Kind.NAVIGATION, "Unknown host: " + e.getMessage(), e);
    } catch (IOException e) {
      throw new BrowserException(BrowserException.Kind.NAVIGATION, "Cannot load " + url + ": " + e, e);
    } catch (RuntimeException e) {
      throw new BrowserException(BrowserException.Kind.NAVIGATION, "Cannot load " + url + ": " + e, e);
    }
    if (jsWaitMs > 0 && options.javaScript()) {
      client.waitForBackgroundJavaScriptStartingBefore(jsWaitMs);
    }
    lastNavigation = describe(currentPage());
    return lastNavigation;
  }

  /** Accepts http, https, data and about URLs. */
  static URL toUrl(String url) {
    if (url == null || url.isBlank()) {
      throw new BrowserException(BrowserException.Kind.BAD_REQUEST, "URL is empty");
    }
    String lower = url.trim().toLowerCase(java.util.Locale.ROOT);
    if (!lower.startsWith("http://") && !lower.startsWith("https://") && !lower.startsWith("data:")
        && !lower.startsWith("about:")) {
      throw new BrowserException(BrowserException.Kind.BAD_REQUEST, "Invalid URL (use http, https, data or about): " + url);
    }
    try {
      return UrlUtils.toUrlUnsafe(url.trim());
    } catch (MalformedURLException e) {
      throw new BrowserException(BrowserException.Kind.BAD_REQUEST, "Invalid URL: " + url);
    }
  }

  private static HttpMethod httpMethod(String method) {
    if (method == null || method.isBlank()) {
      return HttpMethod.GET;
    }
    try {
      return HttpMethod.valueOf(method.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BrowserException(BrowserException.Kind.BAD_REQUEST, "Unsupported HTTP method: " + method);
    }
  }

  private static NavigationResult describe(Page page) {
    if (page == null) {
      return new NavigationResult("about:blank", 0, null, List.of());
    }
    WebResponse response = page.getWebResponse();
    List<Map.Entry<String, String>> headers = new ArrayList<>();
    for (NameValuePair h : response.getResponseHeaders()) {
      headers.add(Map.entry(h.getName(), h.getValue()));
    }
    return new NavigationResult(page.getUrl().toString(), response.getStatusCode(), response.getContentType(), headers);
  }

  /**
   * The page in the session's main window, which may differ from the last navigation if a script
   * navigated. Never a frame or pop-up, even if one loaded last.
   */
  private Page currentPage() {
    return mainWindow.getEnclosedPage();
  }

  /** Status, URL and headers of the document currently shown. */
  public synchronized NavigationResult current() {
    ensureOpen();
    return describe(currentPage());
  }

  /** Keeps running page timers and async requests for up to {@code ms}. */
  public synchronized void runScripts(long ms) {
    ensureOpen();
    if (ms > 0) {
      client.waitForBackgroundJavaScript(ms);
    }
  }

  /**
   * Evaluates a JavaScript expression in the page and returns its value as JSON. If the value is a
   * Promise, waits for it (up to {@code timeoutMs}). Use an IIFE for statements:
   * {@code (() => { ...; return x })()}.
   */
  public synchronized JsonElement evaluate(String expression, long timeoutMs) {
    ensureOpen();
    if (expression == null || expression.isBlank()) {
      throw new BrowserException(BrowserException.Kind.BAD_REQUEST, "Script is empty");
    }
    HtmlPage page = requireHtmlPage();
    return evaluateOn(page, expression, timeoutMs);
  }

  /**
   * Runs a script that navigates (clicks a link, submits a form, sets {@code location}) and waits
   * for the new document. Returns the new page's URL and status; then call {@link #evaluate} again
   * to read the new page.
   */
  public synchronized NavigationResult evaluateAndWaitForNavigation(String expression, long timeoutMs, long jsWaitMs) {
    ensureOpen();
    if (expression == null || expression.isBlank()) {
      throw new BrowserException(BrowserException.Kind.BAD_REQUEST, "Script is empty");
    }
    HtmlPage page = requireHtmlPage();
    evaluateOn(page, expression, timeoutMs);
    return waitForPageChange(page, timeoutMs, jsWaitMs);
  }

  /**
   * Clicks the first element matching {@code selector} like a user would (mouse events, link
   * following, form submission). With {@code waitForNavigation}, waits for the new document.
   */
  public synchronized NavigationResult click(String selector, boolean waitForNavigation, long timeoutMs,
      long jsWaitMs) {
    ensureOpen();
    HtmlPage page = requireHtmlPage();
    DomElement element;
    try {
      element = page.querySelector(selector);
    } catch (RuntimeException e) {
      throw new BrowserException(BrowserException.Kind.BAD_REQUEST, "Invalid selector: " + selector, e);
    }
    if (element == null) {
      throw new BrowserException(BrowserException.Kind.BAD_REQUEST, "No element matches " + selector);
    }
    try {
      element.click();
    } catch (IOException e) {
      throw new BrowserException(BrowserException.Kind.NAVIGATION, "Click on " + selector + " failed: " + e, e);
    }
    if (waitForNavigation) {
      return waitForPageChange(page, timeoutMs, jsWaitMs);
    }
    if (jsWaitMs > 0) {
      client.waitForBackgroundJavaScriptStartingBefore(jsWaitMs);
    }
    return describe(currentPage());
  }

  /** Waits until the window shows a different document than {@code before}. */
  private NavigationResult waitForPageChange(Page before, long timeoutMs, long jsWaitMs) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (currentPage() == before) {
      if (System.currentTimeMillis() >= deadline) {
        throw BrowserException.timeout("Waiting for navigation", timeoutMs);
      }
      if (client.waitForBackgroundJavaScript(POLL_MS) == 0) {
        sleep(POLL_MS);
      }
    }
    if (jsWaitMs > 0 && options.javaScript()) {
      client.waitForBackgroundJavaScriptStartingBefore(jsWaitMs);
    }
    lastNavigation = describe(currentPage());
    return lastNavigation;
  }

  /**
   * Core of {@link #evaluate}. If the script navigates away, the result is lost with the old
   * document and JSON null is returned.
   */
  private JsonElement evaluateOn(HtmlPage page, String expression, long timeoutMs) {
    String key = "__storaEval" + EVAL_IDS.incrementAndGet();
    String k = Json.jsString(key);
    String start = "(function(){var k=" + k + ";window[k]={done:false};"
        + "function fail(e){var m=String(e);if(e&&e.stack&&String(e.stack).indexOf(m)<0)m+='\\n'+e.stack;"
        + "window[k]={done:true,error:m};}"
        + "try{Promise.resolve(" + expression + "\n).then(function(v){var s;"
        + "try{s=JSON.stringify(v===undefined?null:v);}catch(e){fail(e);return;}"
        + "window[k]={done:true,value:s===undefined?'null':s};},fail);}catch(e){fail(e);}})()";
    scriptErrors.clear();
    runScript(page, start);
    if (currentPage() != page) {
      return JsonNull.INSTANCE;
    }
    Object started = runScript(page, "typeof window[" + k + "]");
    if (!"object".equals(started)) {
      String reason = scriptErrors.last();
      throw new BrowserException(BrowserException.Kind.SCRIPT,
          reason == null ? "Script did not run (syntax error?)" : reason);
    }
    String poll = "(function(){var r=window[" + k + "];if(!r||!r.done)return null;delete window[" + k + "];"
        + "return r.error!==undefined?'E'+r.error:'V'+r.value;})()";
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (true) {
      if (currentPage() != page) {
        return JsonNull.INSTANCE;
      }
      Object state = runScript(page, poll);
      if (state != null && !(state instanceof org.htmlunit.corejs.javascript.Undefined)) {
        String s = String.valueOf(state);
        if (s.startsWith("E")) {
          throw new BrowserException(BrowserException.Kind.SCRIPT, s.substring(1));
        }
        try {
          return Json.GSON.fromJson(s.substring(1), JsonElement.class);
        } catch (JsonParseException e) {
          throw new BrowserException(BrowserException.Kind.SCRIPT, "Result is not JSON: " + s.substring(1), e);
        }
      }
      if (System.currentTimeMillis() >= deadline) {
        throw BrowserException.timeout("Script", timeoutMs);
      }
      if (client.waitForBackgroundJavaScript(POLL_MS) == 0) {
        sleep(POLL_MS);
      }
    }
  }

  /**
   * Calls a JavaScript function with JSON arguments, e.g.
   * {@code callFunction("(sel) => document.querySelector(sel).textContent", ".price")}.
   */
  public synchronized JsonElement callFunction(String function, List<JsonElement> args, long timeoutMs) {
    if (function == null || function.isBlank()) {
      throw new BrowserException(BrowserException.Kind.BAD_REQUEST, "Function is empty");
    }
    JsonArray array = new JsonArray();
    if (args != null) {
      args.forEach(a -> array.add(a == null ? JsonNull.INSTANCE : a));
    }
    return evaluate("(" + function + "\n).apply(null, " + Json.GSON.toJson(array) + ")", timeoutMs);
  }

  /** Waits until a CSS selector matches, running page scripts meanwhile. */
  public synchronized void waitForSelector(String selector, long timeoutMs) {
    ensureOpen();
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (true) {
      Page page = currentPage();
      if (page instanceof SgmlPage) {
        try {
          if (((SgmlPage) page).querySelector(selector) != null) {
            return;
          }
        } catch (RuntimeException e) {
          throw new BrowserException(BrowserException.Kind.BAD_REQUEST, "Invalid selector: " + selector, e);
        }
      }
      if (System.currentTimeMillis() >= deadline) {
        throw BrowserException.timeout("Waiting for selector " + selector, timeoutMs);
      }
      if (client.waitForBackgroundJavaScript(POLL_MS) == 0) {
        sleep(POLL_MS);
      }
    }
  }

  /** Waits until a JavaScript expression is truthy, running page scripts meanwhile. */
  public synchronized void waitForFunction(String expression, long timeoutMs) {
    ensureOpen();
    long deadline = System.currentTimeMillis() + timeoutMs;
    String script = "(function(){try{return !!(" + expression + "\n);}catch(e){return false;}})()";
    while (true) {
      Page page = currentPage();
      if (page instanceof HtmlPage && Boolean.TRUE.equals(runScript((HtmlPage) page, script))) {
        return;
      }
      if (System.currentTimeMillis() >= deadline) {
        throw BrowserException.timeout("Waiting for condition", timeoutMs);
      }
      if (client.waitForBackgroundJavaScript(POLL_MS) == 0) {
        sleep(POLL_MS);
      }
    }
  }

  /**
   * The current document as HTML, including changes made by scripts. For non-HTML responses (JSON,
   * text, XML) returns the raw body.
   */
  public synchronized String content() {
    ensureOpen();
    Page page = currentPage();
    if (page == null) {
      return "";
    }
    if (page instanceof HtmlPage) {
      HtmlPage html = (HtmlPage) page;
      if (options.javaScript()) {
        Object result = runScript(html,
            "(function(){var d=document.doctype;var t=d?'<!DOCTYPE '+d.name+'>\\n':'';"
                + "return t+(document.documentElement?document.documentElement.outerHTML:'');})()");
        if (result != null) {
          return String.valueOf(result);
        }
      }
      return html.asXml();
    }
    return page.getWebResponse().getContentAsString();
  }

  /** Visible text of the current document, normalized like a browser's innerText. */
  public synchronized String text() {
    ensureOpen();
    Page page = currentPage();
    if (page instanceof SgmlPage) {
      return ((SgmlPage) page).asNormalizedText();
    }
    return page == null ? "" : page.getWebResponse().getContentAsString();
  }

  public synchronized String title() {
    ensureOpen();
    Page page = currentPage();
    return page instanceof HtmlPage ? ((HtmlPage) page).getTitleText() : null;
  }

  /** All cookies of this session. */
  public synchronized JsonArray cookies() {
    ensureOpen();
    JsonArray out = new JsonArray();
    for (Cookie c : client.getCookieManager().getCookies()) {
      out.add(Json.obj(
          "name", c.getName(),
          "value", c.getValue(),
          "domain", c.getDomain(),
          "path", c.getPath(),
          "expires", c.getExpires() == null ? null : c.getExpires().getTime() / 1000,
          "secure", c.isSecure(),
          "httpOnly", c.isHttpOnly(),
          "sameSite", c.getSameSite()));
    }
    return out;
  }

  /**
   * Adds cookies: objects with name, value, domain and optional path, expires (epoch seconds),
   * secure, httpOnly, sameSite.
   */
  public synchronized void setCookies(JsonArray cookies) {
    ensureOpen();
    if (cookies == null) {
      return;
    }
    for (JsonElement e : cookies) {
      if (!e.isJsonObject()) {
        throw new BrowserException(BrowserException.Kind.BAD_REQUEST, "Cookie must be an object");
      }
      JsonObject c = e.getAsJsonObject();
      String name = Json.str(c, "name");
      String domain = Json.str(c, "domain");
      if (name == null || domain == null) {
        throw new BrowserException(BrowserException.Kind.BAD_REQUEST, "Cookie needs name and domain");
      }
      Long expires = Json.lng(c, "expires");
      client.getCookieManager().addCookie(new Cookie(domain, name, String.valueOf(Json.str(c, "value")),
          Json.str(c, "path") == null ? "/" : Json.str(c, "path"),
          expires == null ? null : new Date(expires * 1000),
          Boolean.TRUE.equals(Json.bool(c, "secure")),
          Boolean.TRUE.equals(Json.bool(c, "httpOnly")),
          Json.str(c, "sameSite")));
    }
  }

  /** Requests made by this session so far, how many were blocked, and bytes received. */
  public synchronized JsonObject stats() {
    return Json.obj("requests", filter.requests(), "blocked", filter.blocked(), "bytes", filter.bytes());
  }

  public SessionOptions options() {
    return options;
  }

  public boolean isClosed() {
    return closed;
  }

  @Override
  public synchronized void close() {
    if (!closed) {
      closed = true;
      client.close();
    }
  }

  private HtmlPage requireHtmlPage() {
    Page page = currentPage();
    if (page == null) {
      try {
        page = client.getPage(mainWindow, new WebRequest(UrlUtils.URL_ABOUT_BLANK));
      } catch (IOException e) {
        throw new BrowserException(BrowserException.Kind.BROWSER, "Cannot open about:blank", e);
      }
    }
    if (!(page instanceof HtmlPage)) {
      throw new BrowserException(BrowserException.Kind.BAD_REQUEST,
          page == null ? "No page loaded; navigate first" : "Current page is not HTML; scripts need an HTML page");
    }
    if (!options.javaScript()) {
      throw new BrowserException(BrowserException.Kind.BAD_REQUEST, "JavaScript is disabled for this session");
    }
    return (HtmlPage) page;
  }


  private Object runScript(HtmlPage page, String script) {
    try {
      ScriptResult result = page.executeJavaScript(script);
      Object value = result == null ? null : result.getJavaScriptResult();
      if (value instanceof CharSequence) {
        return value.toString();
      }
      return value;
    } catch (ScriptException e) {
      throw new BrowserException(BrowserException.Kind.SCRIPT, e.getMessage(), e);
    } catch (RuntimeException e) {
      throw new BrowserException(BrowserException.Kind.SCRIPT, String.valueOf(e), e);
    }
  }

  private void ensureOpen() {
    if (closed) {
      throw new BrowserException(BrowserException.Kind.BAD_REQUEST, "Session is closed");
    }
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BrowserException(BrowserException.Kind.BROWSER, "Interrupted", e);
    }
  }

  /**
   * HtmlUnit logs every script and CSS problem of the visited sites. Keep only severe messages
   * unless {@code -Dstora.headless.libraryLogLevel=INFO} (or another JUL level) is set.
   */
  static void quietLibraryLogging() {
    Level level;
    try {
      level = Level.parse(System.getProperty("stora.headless.libraryLogLevel", "SEVERE"));
    } catch (IllegalArgumentException e) {
      level = Level.SEVERE;
    }
    synchronized (LIBRARY_LOGGERS) {
      LIBRARY_LOGGERS.clear();
      for (String name : new String[] {"org.htmlunit", "org.apache.http"}) {
        Logger logger = Logger.getLogger(name);
        logger.setLevel(level);
        LIBRARY_LOGGERS.add(logger);
      }
    }
  }
}
