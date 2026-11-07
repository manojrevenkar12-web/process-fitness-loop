package io.github.mkp.pfl.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.mkp.pfl.bpmn.BpmnParseException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The HTTP API behind the dashboard.
 *
 * <pre>
 * GET  /api/engagement                         baseline, conformance, proposals, decisions
 * POST /api/verify                             body: BPMN XML; issues and plain-language steps
 * POST /api/proposals/{id}/backtest            run the backtest
 * POST /api/proposals/{id}/decision            {"reviewer", "action", "note"}
 * POST /api/proposals/{id}/deploy              {"deployer"}
 * GET  /healthz, /metrics
 * </pre>
 */
public final class ApiServer implements AutoCloseable {

  private static final Pattern PROPOSAL = Pattern.compile("^/api/proposals/([a-z0-9-]+)/(backtest|decision|deploy)$");

  private final HttpServer server;
  private final Engagement engagement;
  private final Map<String, LongAdder> requests = new ConcurrentHashMap<>();

  public ApiServer(Engagement engagement, int port) throws IOException {
    this.engagement = engagement;
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/", this::route);
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  private void route(HttpExchange ex) throws IOException {
    try (ex) {
      String path = ex.getRequestURI().getPath();
      String method = ex.getRequestMethod();
      ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
      ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
      if (method.equals("OPTIONS")) {
        ex.sendResponseHeaders(204, -1);
        return;
      }
      requests.computeIfAbsent(method + " " + path.replaceAll("/proposals/[^/]+/", "/proposals/{id}/"), k -> new LongAdder()).increment();
      try {
        if (method.equals("GET") && path.equals("/healthz")) {
          send(ex, 200, Map.of("status", "ok"));
        } else if (method.equals("GET") && path.equals("/metrics")) {
          sendText(ex, metrics());
        } else if (method.equals("GET") && path.equals("/api/engagement")) {
          send(ex, 200, engagement.overview());
        } else if (method.equals("POST") && path.equals("/api/verify")) {
          send(ex, 200, engagement.verify(ex.getRequestBody().readAllBytes()));
        } else {
          Matcher m = PROPOSAL.matcher(path);
          if (method.equals("POST") && m.matches()) {
            Map<String, String> body = body(ex);
            switch (m.group(2)) {
              case "backtest" -> send(ex, 200, engagement.backtest(m.group(1)));
              case "decision" -> send(ex, 201, engagement.decide(m.group(1), body.get("reviewer"), body.get("action"), body.get("note")));
              default -> send(ex, 201, engagement.deploy(m.group(1), body.get("deployer")));
            }
          } else {
            send(ex, 404, Map.of("error", "no route " + method + " " + path));
          }
        }
      } catch (Engagement.Refused e) {
        send(ex, e.status(), Map.of("error", e.getMessage()));
      } catch (BpmnParseException | IllegalArgumentException e) {
        send(ex, 400, Map.of("error", e.getMessage()));
      } catch (RuntimeException e) {
        send(ex, 500, Map.of("error", e.toString()));
      }
    }
  }

  private Map<String, String> body(HttpExchange ex) throws IOException {
    byte[] raw = ex.getRequestBody().readAllBytes();
    if (raw.length == 0) {
      return Map.of();
    }
    return Engagement.JSON.readValue(raw, new TypeReference<>() {});
  }

  private String metrics() {
    StringBuilder out = new StringBuilder("# TYPE pfl_http_requests_total counter\n");
    requests.forEach((k, v) -> out.append("pfl_http_requests_total{route=\"").append(k).append("\"} ").append(v.sum()).append('\n'));
    return out.toString();
  }

  private static void send(HttpExchange ex, int status, Object body) throws IOException {
    byte[] bytes = Engagement.JSON.writeValueAsBytes(body);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void sendText(HttpExchange ex, String text) throws IOException {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "text/plain; version=0.0.4");
    ex.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
