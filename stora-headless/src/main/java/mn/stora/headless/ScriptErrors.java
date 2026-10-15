package mn.stora.headless;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.htmlunit.ScriptException;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.javascript.JavaScriptErrorListener;

/**
 * Collects script errors. Errors of the visited site's own scripts are normal on real pages, so
 * they are logged at FINE only; the last error is kept so {@link BrowserSession#evaluate} can
 * report why an injected script did not run.
 */
final class ScriptErrors implements JavaScriptErrorListener {

  private static final Logger LOG = Logger.getLogger("stora.headless.page-scripts");

  private volatile String last;

  void clear() {
    last = null;
  }

  String last() {
    return last;
  }

  @Override
  public void scriptException(HtmlPage page, ScriptException e) {
    last = e.getMessage();
    LOG.log(Level.FINE, () -> "Script error on " + url(page) + ": " + e.getMessage());
  }

  @Override
  public void timeoutError(HtmlPage page, long allowedTime, long executionTime) {
    last = "Script ran longer than " + allowedTime + " ms";
    LOG.log(Level.FINE, () -> "Script timeout on " + url(page));
  }

  @Override
  public void malformedScriptURL(HtmlPage page, String url, MalformedURLException e) {
    LOG.log(Level.FINE, () -> "Malformed script URL on " + url(page) + ": " + url);
  }

  @Override
  public void loadScriptError(HtmlPage page, URL scriptUrl, Exception e) {
    LOG.log(Level.FINE, () -> "Cannot load script " + scriptUrl + ": " + e);
  }

  @Override
  public void warn(String message, String sourceName, int line, String lineSource, int lineOffset) {
    LOG.log(Level.FINEST, () -> "Script warning " + sourceName + ":" + line + " " + message);
  }

  private static String url(HtmlPage page) {
    return page == null ? "?" : String.valueOf(page.getUrl());
  }
}
