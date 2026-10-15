package mn.stora.headless;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import mn.stora.headless.server.BrowserServer;
import mn.stora.headless.server.ServerConfig;

/**
 * Command line entry point.
 *
 * <pre>
 * java -jar stora-headless-all.jar serve [--host H] [--port P] [--token T] [--max-sessions N] [--session-ttl S]
 * java -jar stora-headless-all.jar render URL [--proxy URL] [--script JS] [--wait-for CSS] [--js-wait MS]
 *                                             [--browser chrome|edge|firefox] [--user-agent UA] [--text] [--no-js]
 * java -jar stora-headless-all.jar --version
 * </pre>
 */
public final class Main {

  private Main() {
  }

  public static void main(String[] args) throws Exception {
    List<String> a = new ArrayList<>(List.of(args));
    String command = a.isEmpty() || a.get(0).startsWith("--") ? "serve" : a.remove(0);
    if (a.contains("--version")) {
      System.out.println("stora-headless " + Version.VERSION + " (" + Version.ENGINE + ")");
      return;
    }
    if (a.contains("--help") || a.contains("-h")) {
      usage(System.out);
      return;
    }
    switch (command) {
      case "serve":
        serve(a);
        break;
      case "render":
        System.exit(render(a));
        break;
      default:
        usage(System.err);
        System.exit(2);
    }
  }

  private static void serve(List<String> a) throws IOException {
    configureLogging();
    ServerConfig config = ServerConfig.fromEnv(System.getenv());
    for (int i = 0; i < a.size(); i++) {
      String flag = a.get(i);
      switch (flag) {
        case "--host":
          config.host = value(a, ++i, flag);
          break;
        case "--port":
          config.port = Integer.parseInt(value(a, ++i, flag));
          break;
        case "--token":
          config.token = value(a, ++i, flag);
          break;
        case "--max-sessions":
          config.maxSessions = Integer.parseInt(value(a, ++i, flag));
          break;
        case "--session-ttl":
          config.sessionTtlSeconds = Integer.parseInt(value(a, ++i, flag));
          break;
        default:
          throw new IllegalArgumentException("Unknown flag " + flag);
      }
    }
    BrowserServer server = new BrowserServer(config).start();
    Runtime.getRuntime().addShutdownHook(new Thread(server::close, "shutdown"));
  }

  private static int render(List<String> a) {
    if (a.isEmpty() || a.get(0).startsWith("--")) {
      usage(System.err);
      return 2;
    }
    String url = a.remove(0);
    SessionOptions options = new SessionOptions();
    String script = null;
    String waitFor = null;
    long jsWait = 1000;
    boolean text = false;
    for (int i = 0; i < a.size(); i++) {
      String flag = a.get(i);
      switch (flag) {
        case "--proxy":
          options.proxy(value(a, ++i, flag));
          break;
        case "--script":
          script = value(a, ++i, flag);
          break;
        case "--wait-for":
          waitFor = value(a, ++i, flag);
          break;
        case "--js-wait":
          jsWait = Long.parseLong(value(a, ++i, flag));
          break;
        case "--browser":
          options.emulation(SessionOptions.Emulation.parse(value(a, ++i, flag)));
          break;
        case "--user-agent":
          options.userAgent(value(a, ++i, flag));
          break;
        case "--block":
          options.blockType(value(a, ++i, flag));
          break;
        case "--text":
          text = true;
          break;
        case "--no-js":
          options.javaScript(false);
          break;
        default:
          throw new IllegalArgumentException("Unknown flag " + flag);
      }
    }
    JsonObject out = new JsonObject();
    try (BrowserSession session = new BrowserSession(options)) {
      NavigationResult nav = session.navigate(url, jsWait);
      if (waitFor != null) {
        session.waitForSelector(waitFor, 15_000);
      }
      out.addProperty("ok", true);
      out.addProperty("url", session.current().url());
      out.addProperty("status", nav.status());
      out.addProperty("title", session.title());
      if (script != null) {
        out.add("result", session.evaluate(script, 15_000));
      }
      if (text) {
        out.addProperty("text", session.text());
      } else {
        out.addProperty("html", session.content());
      }
      out.add("stats", session.stats());
    } catch (BrowserException e) {
      out.addProperty("ok", false);
      out.addProperty("error", e.getMessage());
      out.addProperty("kind", e.kind().name());
      print(out);
      return 1;
    }
    print(out);
    return 0;
  }

  private static void print(JsonObject out) {
    PrintStream ps = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    ps.println(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(out));
  }

  private static String value(List<String> a, int i, String flag) {
    if (i >= a.size()) {
      throw new IllegalArgumentException(flag + " needs a value");
    }
    return a.get(i);
  }

  /** One line per log record on stderr. */
  private static void configureLogging() {
    System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$s %3$s - %5$s%6$s%n");
    Logger root = Logger.getLogger("");
    for (Handler h : root.getHandlers()) {
      root.removeHandler(h);
    }
    ConsoleHandler console = new ConsoleHandler() {
      @Override
      public synchronized void publish(LogRecord record) {
        super.publish(record);
        flush();
      }
    };
    console.setFormatter(new SimpleFormatter());
    console.setLevel(Level.ALL);
    root.addHandler(console);
    root.setLevel(Level.INFO);
    BrowserSession.quietLibraryLogging();
  }

  private static void usage(PrintStream out) {
    out.println("stora-headless " + Version.VERSION + " - pure-Java headless browser for scraping");
    out.println();
    out.println("Usage:");
    out.println("  serve  [--host 127.0.0.1] [--port 8090] [--token T] [--max-sessions 8] [--session-ttl 300]");
    out.println("  render URL [--proxy http://user:pass@host:port] [--script JS] [--wait-for CSS]");
    out.println("             [--js-wait MS] [--browser chrome|edge|firefox] [--user-agent UA]");
    out.println("             [--block image|font|stylesheet|media|script] [--text] [--no-js]");
    out.println("  --version");
    out.println();
    out.println("Environment for serve: HOST, PORT, STORA_HEADLESS_TOKEN, MAX_SESSIONS, SESSION_TTL_SECONDS");
  }
}
