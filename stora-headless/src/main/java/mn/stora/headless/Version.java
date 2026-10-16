package mn.stora.headless;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Version strings reported by {@code /health} and {@code --version}, written by Maven at build time. */
public final class Version {

  private static final Properties BUILD = load();

  public static final String VERSION = BUILD.getProperty("version", "dev");
  public static final String ENGINE = "HtmlUnit " + BUILD.getProperty("engine.version", "5.x");

  private Version() {
  }

  private static Properties load() {
    Properties p = new Properties();
    try (InputStream in = Version.class.getResourceAsStream("/stora-headless.properties")) {
      if (in != null) {
        p.load(in);
      }
    } catch (IOException ignored) {
      // Defaults above.
    }
    return p;
  }
}
