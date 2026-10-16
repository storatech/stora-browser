package mn.stora.headless.server;

import java.util.Map;

/** HTTP server settings. Values come from command-line flags, then environment variables. */
public final class ServerConfig {

  /** Address to bind. Defaults to loopback so the service is not exposed by accident. */
  public String host = "127.0.0.1";
  public int port = 8090;
  /** If set, every request except {@code /health} needs {@code Authorization: Bearer <token>}. */
  public String token;
  /** Browser sessions open at the same time, including one-shot {@code /render} calls. */
  public int maxSessions = 8;
  /** Idle sessions are closed after this many seconds. */
  public int sessionTtlSeconds = 300;
  /** How long a request waits for a free session slot before getting 503. */
  public int queueTimeoutMs = 30_000;
  public int maxBodyBytes = 5 * 1024 * 1024;
  /**
   * Proxy for sessions that do not send their own {@code proxy}. Sending {@code "proxy": ""}
   * opts a session out. Never logged or reported.
   */
  public String defaultProxy;

  /**
   * Applies environment variables: HOST, PORT, STORA_HEADLESS_TOKEN, STORA_HEADLESS_PROXY,
   * MAX_SESSIONS, SESSION_TTL_SECONDS.
   */
  public static ServerConfig fromEnv(Map<String, String> env) {
    ServerConfig c = new ServerConfig();
    if (notBlank(env.get("HOST"))) {
      c.host = env.get("HOST");
    }
    if (notBlank(env.get("PORT"))) {
      c.port = Integer.parseInt(env.get("PORT"));
    }
    if (notBlank(env.get("STORA_HEADLESS_TOKEN"))) {
      c.token = env.get("STORA_HEADLESS_TOKEN");
    }
    if (notBlank(env.get("STORA_HEADLESS_PROXY"))) {
      c.defaultProxy = env.get("STORA_HEADLESS_PROXY");
    }
    if (notBlank(env.get("MAX_SESSIONS"))) {
      c.maxSessions = Integer.parseInt(env.get("MAX_SESSIONS"));
    }
    if (notBlank(env.get("SESSION_TTL_SECONDS"))) {
      c.sessionTtlSeconds = Integer.parseInt(env.get("SESSION_TTL_SECONDS"));
    }
    return c;
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }
}
