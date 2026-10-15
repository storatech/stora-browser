package mn.stora.headless;

import java.util.List;
import java.util.Map;

/** Outcome of loading a URL: the final URL after redirects, HTTP status and response headers. */
public final class NavigationResult {

  private final String url;
  private final int status;
  private final String contentType;
  private final List<Map.Entry<String, String>> headers;

  NavigationResult(String url, int status, String contentType, List<Map.Entry<String, String>> headers) {
    this.url = url;
    this.status = status;
    this.contentType = contentType;
    this.headers = headers;
  }

  public String url() {
    return url;
  }

  public int status() {
    return status;
  }

  public String contentType() {
    return contentType;
  }

  public List<Map.Entry<String, String>> headers() {
    return headers;
  }
}
