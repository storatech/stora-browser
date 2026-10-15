package mn.stora.headless;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A proxy given as a URL: {@code http://host:port}, {@code http://user:pass@host:port},
 * {@code https://host:port} or {@code socks5://host:port}. A value without a scheme is treated as
 * HTTP. Percent-encode special characters in the user name and password ({@code #} as {@code %23},
 * {@code @} as {@code %40}), as JavaScript's {@code encodeURIComponent} does.
 */
public final class ProxySpec {

  private final String scheme;
  private final String host;
  private final int port;
  private final String username;
  private final String password;

  private ProxySpec(String scheme, String host, int port, String username, String password) {
    this.scheme = scheme;
    this.host = host;
    this.port = port;
    this.username = username;
    this.password = password;
  }

  /** Parses a proxy URL; returns null for null or blank input. */
  public static ProxySpec parse(String url) {
    if (url == null || url.isBlank()) {
      return null;
    }
    String value = url.trim();
    if (!value.contains("://")) {
      value = "http://" + value;
    }
    URI uri;
    try {
      uri = new URI(value);
    } catch (URISyntaxException e) {
      throw new BrowserException(BrowserException.Kind.BAD_REQUEST, "Invalid proxy URL");
    }
    String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("socks") && !scheme.equals("socks4")
        && !scheme.equals("socks5")) {
      throw new BrowserException(BrowserException.Kind.BAD_REQUEST, "Unsupported proxy scheme: " + scheme);
    }
    if (uri.getHost() == null || uri.getPort() <= 0) {
      throw new BrowserException(BrowserException.Kind.BAD_REQUEST, "Proxy URL needs a host and a port");
    }
    String username = null;
    String password = null;
    String userInfo = uri.getRawUserInfo();
    if (userInfo != null && !userInfo.isEmpty()) {
      int colon = userInfo.indexOf(':');
      username = decode(colon < 0 ? userInfo : userInfo.substring(0, colon));
      password = colon < 0 ? "" : decode(userInfo.substring(colon + 1));
    }
    return new ProxySpec(scheme, uri.getHost(), uri.getPort(), username, password);
  }

  /** Percent-decodes user info; a literal '+' stays '+' (it is not a space in URLs' user info). */
  private static String decode(String s) {
    return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
  }

  public String scheme() {
    return scheme;
  }

  public String host() {
    return host;
  }

  public int port() {
    return port;
  }

  public boolean isSocks() {
    return scheme.startsWith("socks");
  }

  public boolean hasCredentials() {
    return username != null;
  }

  public String username() {
    return username;
  }

  public String password() {
    return password;
  }

  /** Safe for logs: never includes credentials. */
  @Override
  public String toString() {
    return scheme + "://" + host + ":" + port + (hasCredentials() ? " (with credentials)" : "");
  }
}
