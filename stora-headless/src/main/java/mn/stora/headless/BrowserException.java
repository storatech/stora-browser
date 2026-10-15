package mn.stora.headless;

/**
 * Base exception for browser failures. {@link #kind()} tells callers (and the HTTP server)
 * what went wrong without parsing messages.
 */
public class BrowserException extends RuntimeException {

  /** Failure categories, mapped to HTTP status codes by the server. */
  public enum Kind {
    /** Chrome could not be started or the DevTools connection was lost. */
    BROWSER,
    /** A DevTools command returned an error. */
    PROTOCOL,
    /** The page could not be loaded (DNS, proxy, TLS, aborted). */
    NAVIGATION,
    /** A script threw or could not be evaluated. */
    SCRIPT,
    /** An operation did not finish in time. */
    TIMEOUT,
    /** Invalid input from the caller. */
    BAD_REQUEST
  }

  private final Kind kind;

  public BrowserException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public BrowserException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }

  static BrowserException timeout(String what, long timeoutMs) {
    return new BrowserException(Kind.TIMEOUT, what + " timed out after " + timeoutMs + " ms");
  }
}
