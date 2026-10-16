package mn.stora.headless;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Settings for one {@link BrowserSession}. Every session is an isolated browser: its own cookies,
 * proxy, user agent and headers. Defaults favour speed: JavaScript on, CSS and images off.
 */
public final class SessionOptions {

  /** Browsers HtmlUnit can emulate (user agent, headers and JavaScript quirks). */
  public enum Emulation {
    CHROME, EDGE, FIREFOX, FIREFOX_ESR;

    public static Emulation parse(String value) {
      if (value == null || value.isBlank()) {
        return CHROME;
      }
      try {
        return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
      } catch (IllegalArgumentException e) {
        throw new BrowserException(BrowserException.Kind.BAD_REQUEST,
            "Unknown browser '" + value + "'; use chrome, edge, firefox or firefox_esr");
      }
    }
  }

  /** Resource categories that can be skipped, matched on the URL's file extension. */
  public static final Set<String> RESOURCE_TYPES = Set.of("image", "font", "stylesheet", "media", "script");

  private Emulation emulation = Emulation.CHROME;
  private String userAgent;
  private String acceptLanguage;
  private String timezone;
  private ProxySpec proxy;
  private final Map<String, String> headers = new LinkedHashMap<>();
  private boolean javaScript = true;
  private boolean css;
  private boolean downloadImages;
  private boolean insecureSsl;
  private int timeoutMs = 30_000;
  private long scriptTimeoutMs = 20_000;
  private final List<String> blockUrls = new ArrayList<>();
  private final Set<String> blockTypes = new LinkedHashSet<>();

  public SessionOptions emulation(Emulation value) {
    this.emulation = value == null ? Emulation.CHROME : value;
    return this;
  }

  /** Overrides the emulated browser's User-Agent header and {@code navigator.userAgent}. */
  public SessionOptions userAgent(String value) {
    this.userAgent = blankToNull(value);
    return this;
  }

  /** Accept-Language header, e.g. {@code en-US,en;q=0.9}. */
  public SessionOptions acceptLanguage(String value) {
    this.acceptLanguage = blankToNull(value);
    return this;
  }

  /** Time zone seen by page scripts, e.g. {@code America/New_York}. */
  public SessionOptions timezone(String value) {
    this.timezone = blankToNull(value);
    return this;
  }

  public SessionOptions proxy(String url) {
    this.proxy = ProxySpec.parse(url);
    return this;
  }

  public SessionOptions proxy(ProxySpec value) {
    this.proxy = value;
    return this;
  }

  /** Header added to every request of the session. */
  public SessionOptions header(String name, String value) {
    if (name != null && value != null) {
      headers.put(name, value);
    }
    return this;
  }

  public SessionOptions javaScript(boolean value) {
    this.javaScript = value;
    return this;
  }

  /** Load and apply CSS. Off by default; turn on when scripts depend on computed styles. */
  public SessionOptions css(boolean value) {
    this.css = value;
    return this;
  }

  public SessionOptions downloadImages(boolean value) {
    this.downloadImages = value;
    return this;
  }

  /** Accept invalid or self-signed TLS certificates. */
  public SessionOptions insecureSsl(boolean value) {
    this.insecureSsl = value;
    return this;
  }

  /** Connect and read timeout for each HTTP request. */
  public SessionOptions timeoutMs(int value) {
    this.timeoutMs = value;
    return this;
  }

  /** Maximum run time of a single script execution inside the page. */
  public SessionOptions scriptTimeoutMs(long value) {
    this.scriptTimeoutMs = value;
    return this;
  }

  /**
   * Skips requests whose URL matches: a glob with {@code *} (e.g. {@code *google-analytics*}) or a
   * plain substring.
   */
  public SessionOptions blockUrl(String pattern) {
    if (pattern != null && !pattern.isBlank()) {
      blockUrls.add(pattern);
    }
    return this;
  }

  /** Skips a resource type: image, font, stylesheet, media or script. */
  public SessionOptions blockType(String type) {
    if (type == null || type.isBlank()) {
      return this;
    }
    String t = type.trim().toLowerCase(Locale.ROOT);
    if (!RESOURCE_TYPES.contains(t)) {
      throw new BrowserException(BrowserException.Kind.BAD_REQUEST,
          "Unknown resource type '" + type + "'; use one of " + RESOURCE_TYPES);
    }
    blockTypes.add(t);
    return this;
  }

  public Emulation emulation() {
    return emulation;
  }

  public String userAgent() {
    return userAgent;
  }

  public String acceptLanguage() {
    return acceptLanguage;
  }

  public String timezone() {
    return timezone;
  }

  public ProxySpec proxy() {
    return proxy;
  }

  public Map<String, String> headers() {
    return Collections.unmodifiableMap(headers);
  }

  public boolean javaScript() {
    return javaScript;
  }

  public boolean css() {
    return css;
  }

  public boolean downloadImages() {
    return downloadImages;
  }

  public boolean insecureSsl() {
    return insecureSsl;
  }

  public int timeoutMs() {
    return timeoutMs;
  }

  public long scriptTimeoutMs() {
    return scriptTimeoutMs;
  }

  public List<String> blockUrls() {
    return Collections.unmodifiableList(blockUrls);
  }

  public Set<String> blockTypes() {
    return Collections.unmodifiableSet(blockTypes);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
