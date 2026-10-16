package mn.stora.headless;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** A local website for tests. Counts hits per path. */
final class TestSite implements AutoCloseable {

  private final HttpServer server;
  private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

  TestSite() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
  }

  String url(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  int hits(String path) {
    AtomicInteger n = hits.get(path);
    return n == null ? 0 : n.get();
  }

  private void handle(HttpExchange ex) throws IOException {
    String path = ex.getRequestURI().getPath();
    hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
    switch (path) {
      case "/spa":
        html(ex, 200, "<!DOCTYPE html><html><head><title>Product</title></head><body>"
            + "<div id='app'>loading</div><div id='name'></div>"
            + "<script>"
            + "setTimeout(function () {"
            + "  document.getElementById('app').innerHTML = '<span class=\"price\">42</span>';"
            + "}, 300);"
            + "fetch('/api/product').then(function (r) { return r.json(); }).then(function (p) {"
            + "  document.getElementById('name').textContent = p.name;"
            + "});"
            + "</script></body></html>");
        break;
      case "/api/product":
        send(ex, 200, "application/json", "{\"name\":\"Widget\",\"price\":42}");
        break;
      case "/headers": {
        StringBuilder sb = new StringBuilder("<html><body><pre id='headers'>");
        Map<String, List<String>> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        sorted.putAll(ex.getRequestHeaders());
        sorted.forEach((k, v) -> sb.append(k.toLowerCase()).append(": ").append(String.join(",", v)).append('\n'));
        html(ex, 200, sb.append("</pre></body></html>").toString());
        break;
      }
      case "/cookie":
        ex.getResponseHeaders().add("Set-Cookie", "sid=abc; Path=/");
        html(ex, 200, "<html><body><script>document.cookie = 'js=1; path=/';</script></body></html>");
        break;
      case "/tracked":
        html(ex, 200, "<html><body><script src='/tracker.js'></script>"
            + "<script>document.title = window.trackerLoaded ? 'tracked' : 'clean';</script></body></html>");
        break;
      case "/tracker.js":
        send(ex, 200, "application/javascript", "window.trackerLoaded = true;");
        break;
      case "/redirect":
        ex.getResponseHeaders().add("Location", "/spa");
        send(ex, 302, "text/plain", "");
        break;
      case "/shop":
        html(ex, 200, "<!DOCTYPE html><html><head><title>Shop</title></head><body><ul>"
            + "<li><a class='product-link' href='/product/1'>Coffee beans</a></li>"
            + "<li><a class='product-link' href='/product/2'>Tea leaves</a></li>"
            + "</ul><button id='later' onclick=\"setTimeout(function () { location.href = '/product/2'; }, 200)\">"
            + "later</button></body></html>");
        break;
      case "/product/1":
      case "/product/2": {
        String id = path.substring(path.lastIndexOf('/') + 1);
        html(ex, 200, "<!DOCTYPE html><html><head><title>Loading</title></head><body><h1 id='name'></h1>"
            + "<span class='price'></span><script>"
            + "fetch('/api/item/" + id + "').then(function (r) { return r.json(); }).then(function (p) {"
            + "  document.title = p.name; document.getElementById('name').textContent = p.name;"
            + "  document.querySelector('.price').textContent = p.price; });"
            + "</script></body></html>");
        break;
      }
      case "/api/item/1":
        send(ex, 200, "application/json", "{\"name\":\"Coffee beans\",\"price\":12000}");
        break;
      case "/api/item/2":
        send(ex, 200, "application/json", "{\"name\":\"Tea leaves\",\"price\":8000}");
        break;
      case "/framed":
        html(ex, 200, "<!DOCTYPE html><html><head><title>Main page</title></head><body><p id='main'>main</p>"
            + "<script>setTimeout(function () { var f = document.createElement('iframe');"
            + " f.src = '/headers'; document.body.appendChild(f); }, 100);</script></body></html>");
        break;
      case "/forbidden":
        html(ex, 403, "<html><body>Access denied</body></html>");
        break;
      default:
        send(ex, 404, "text/plain", "not found");
    }
  }

  private static void html(HttpExchange ex, int status, String body) throws IOException {
    send(ex, status, "text/html; charset=utf-8", body);
  }

  private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", type);
    ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
