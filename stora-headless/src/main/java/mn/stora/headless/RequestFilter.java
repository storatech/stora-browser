package mn.stora.headless;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import org.htmlunit.WebClient;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.WebResponseData;
import org.htmlunit.util.WebConnectionWrapper;

/**
 * Sits between HtmlUnit and the network: skips blocked URLs and resource types (answering them
 * with an empty 204) and counts requests for the session's stats.
 */
final class RequestFilter extends WebConnectionWrapper {

  private static final Map<String, Set<String>> EXTENSIONS = Map.of(
      "image", Set.of("png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp"),
      "font", Set.of("woff", "woff2", "ttf", "otf", "eot"),
      "stylesheet", Set.of("css"),
      "media", Set.of("mp4", "webm", "mp3", "ogg", "wav", "m4a", "m3u8", "mov"),
      "script", Set.of("js", "mjs"));

  private final List<Pattern> blockedUrls = new ArrayList<>();
  private final Set<String> blockedExtensions;
  private final AtomicInteger requests = new AtomicInteger();
  private final AtomicInteger blocked = new AtomicInteger();
  private final AtomicLong bytes = new AtomicLong();

  RequestFilter(WebClient client, SessionOptions options) {
    super(client);
    for (String pattern : options.blockUrls()) {
      blockedUrls.add(toPattern(pattern));
    }
    Set<String> extensions = new java.util.HashSet<>();
    for (String type : options.blockTypes()) {
      extensions.addAll(EXTENSIONS.getOrDefault(type, Collections.emptySet()));
    }
    this.blockedExtensions = extensions;
  }

  @Override
  public WebResponse getResponse(WebRequest request) throws IOException {
    requests.incrementAndGet();
    String url = request.getUrl().toString();
    if (isBlocked(url, request.getUrl().getPath())) {
      blocked.incrementAndGet();
      return new WebResponse(new WebResponseData(new byte[0], 204, "No Content", Collections.emptyList()), request, 0);
    }
    WebResponse response = super.getResponse(request);
    long length = response.getContentLength();
    if (length > 0) {
      bytes.addAndGet(length);
    }
    return response;
  }

  private boolean isBlocked(String url, String path) {
    for (Pattern p : blockedUrls) {
      if (p.matcher(url).find()) {
        return true;
      }
    }
    if (!blockedExtensions.isEmpty() && path != null) {
      int dot = path.lastIndexOf('.');
      int slash = path.lastIndexOf('/');
      if (dot > slash) {
        return blockedExtensions.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
      }
    }
    return false;
  }

  /** {@code *} matches anything; without {@code *} the pattern is a plain substring. */
  static Pattern toPattern(String glob) {
    if (!glob.contains("*")) {
      return Pattern.compile(Pattern.quote(glob));
    }
    StringBuilder regex = new StringBuilder("^");
    for (String part : glob.split("\\*", -1)) {
      if (regex.length() > 1) {
        regex.append(".*");
      }
      regex.append(Pattern.quote(part));
    }
    return Pattern.compile(regex.append('$').toString());
  }

  int requests() {
    return requests.get();
  }

  int blocked() {
    return blocked.get();
  }

  long bytes() {
    return bytes.get();
  }
}
