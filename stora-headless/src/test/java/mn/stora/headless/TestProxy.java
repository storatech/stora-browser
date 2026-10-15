package mn.stora.headless;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal forward HTTP proxy for tests. Requires Basic proxy credentials, forwards plain-HTTP
 * requests and adds {@code X-Test-Proxy: yes} so the target can see the hop.
 */
final class TestProxy implements AutoCloseable {

  private final ServerSocket socket;
  private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
    Thread t = new Thread(r, "test-proxy");
    t.setDaemon(true);
    return t;
  });
  private final String expectedAuth;
  final AtomicInteger forwarded = new AtomicInteger();
  final AtomicInteger challenged = new AtomicInteger();

  TestProxy(String user, String password) throws IOException {
    this.expectedAuth = "Basic " + Base64.getEncoder()
        .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    pool.execute(this::acceptLoop);
  }

  String url(String user, String password) {
    String auth = user == null ? "" : user + ":" + password + "@";
    return "http://" + auth + "127.0.0.1:" + socket.getLocalPort();
  }

  private void acceptLoop() {
    while (!socket.isClosed()) {
      try {
        Socket client = socket.accept();
        pool.execute(() -> serve(client));
      } catch (IOException e) {
        return;
      }
    }
  }

  private void serve(Socket client) {
    try (Socket c = client) {
      InputStream in = new BufferedInputStream(c.getInputStream());
      String requestLine = readLine(in);
      if (requestLine == null || requestLine.isEmpty()) {
        return;
      }
      Map<String, String> headers = new LinkedHashMap<>();
      String line;
      while ((line = readLine(in)) != null && !line.isEmpty()) {
        int colon = line.indexOf(':');
        if (colon > 0) {
          headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
        }
      }
      OutputStream out = c.getOutputStream();
      if (!expectedAuth.equals(headers.get("proxy-authorization"))) {
        challenged.incrementAndGet();
        write(out, "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"test\"\r\n"
            + "Content-Length: 0\r\nConnection: close\r\n\r\n");
        return;
      }
      String[] parts = requestLine.split(" ");
      if (parts.length < 2 || !parts[1].startsWith("http://")) {
        write(out, "HTTP/1.1 501 Not Implemented\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        return;
      }
      int length = headers.containsKey("content-length") ? Integer.parseInt(headers.get("content-length")) : 0;
      byte[] body = in.readNBytes(length);
      forwarded.incrementAndGet();
      HttpURLConnection target = (HttpURLConnection) new URL(parts[1]).openConnection(java.net.Proxy.NO_PROXY);
      target.setInstanceFollowRedirects(false);
      target.setRequestMethod(parts[0]);
      headers.forEach((k, v) -> {
        if (!k.startsWith("proxy-") && !k.equals("connection") && !k.equals("host")) {
          target.setRequestProperty(k, v);
        }
      });
      target.setRequestProperty("X-Test-Proxy", "yes");
      if (body.length > 0) {
        target.setDoOutput(true);
        target.getOutputStream().write(body);
      }
      int status = target.getResponseCode();
      InputStream stream = status >= 400 ? target.getErrorStream() : target.getInputStream();
      byte[] content = stream == null ? new byte[0] : stream.readAllBytes();
      StringBuilder head = new StringBuilder("HTTP/1.1 " + status + " " + target.getResponseMessage() + "\r\n");
      target.getHeaderFields().forEach((k, values) -> {
        if (k != null && !k.equalsIgnoreCase("transfer-encoding") && !k.equalsIgnoreCase("content-length")
            && !k.equalsIgnoreCase("connection")) {
          values.forEach(v -> head.append(k).append(": ").append(v).append("\r\n"));
        }
      });
      head.append("Content-Length: ").append(content.length).append("\r\nConnection: close\r\n\r\n");
      write(out, head.toString());
      out.write(content);
      out.flush();
    } catch (IOException e) {
      // Client went away; fine for a test proxy.
    }
  }

  private static String readLine(InputStream in) throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    int b;
    while ((b = in.read()) != -1) {
      if (b == '\n') {
        break;
      }
      if (b != '\r') {
        buf.write(b);
      }
    }
    if (b == -1 && buf.size() == 0) {
      return null;
    }
    return buf.toString(StandardCharsets.ISO_8859_1);
  }

  private static void write(OutputStream out, String s) throws IOException {
    out.write(s.getBytes(StandardCharsets.ISO_8859_1));
    out.flush();
  }

  @Override
  public void close() throws IOException {
    socket.close();
    pool.shutdownNow();
  }
}
