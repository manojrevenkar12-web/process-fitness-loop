package io.github.mkp.pfl.hub;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

/** Serves a {@link LegacyHub} over HTTP the way the bank's gateway does, lost answers included. */
public final class HubHttpServer implements AutoCloseable {

  private final HttpServer server;

  /** @param stall how long a lost answer keeps the connection open before it is dropped */
  public HubHttpServer(LegacyHub hub, int port, Duration stall) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/ph9/gateway", exchange -> {
      try (exchange) {
        if (!"POST".equals(exchange.getRequestMethod())) {
          exchange.sendResponseHeaders(405, -1);
          return;
        }
        String line = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.US_ASCII);
        try {
          byte[] body = hub.handle(line).getBytes(StandardCharsets.US_ASCII);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        } catch (LegacyHub.Overloaded e) {
          exchange.sendResponseHeaders(503, -1);
        } catch (LegacyHub.AnswerLost e) {
          try {
            Thread.sleep(stall.toMillis());
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
          }
          exchange.sendResponseHeaders(504, -1);
        }
      }
    });
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
