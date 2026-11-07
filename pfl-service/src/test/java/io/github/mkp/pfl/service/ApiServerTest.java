package io.github.mkp.pfl.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ApiServerTest {

  static ApiServer server;
  static final HttpClient HTTP = HttpClient.newHttpClient();

  @BeforeAll
  static void start() throws Exception {
    server = new ApiServer(TestRoot.engagement(TestRoot.copy()), 0);
  }

  @AfterAll
  static void stop() {
    server.close();
  }

  static HttpResponse<String> call(String method, String path, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
    b = body == null ? b.method(method, HttpRequest.BodyPublishers.noBody()) : b.method(method, HttpRequest.BodyPublishers.ofString(body));
    return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void healthAndMetrics() throws Exception {
    assertEquals(200, call("GET", "/healthz", null).statusCode());
    call("GET", "/api/engagement", null);
    assertTrue(call("GET", "/metrics", null).body().contains("pfl_http_requests_total{route=\"GET /api/engagement\"}"));
  }

  @Test
  void verifyExplainsAProblemInAPostedFile() throws Exception {
    String bpmn = "<?xml version=\"1.0\"?><bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" id=\"d\" targetNamespace=\"t\">"
        + "<bpmn:process id=\"p\" isExecutable=\"true\"><bpmn:startEvent id=\"s\"/><bpmn:serviceTask id=\"t\" name=\"Lookup\"/><bpmn:endEvent id=\"e\"/>"
        + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t\"/><bpmn:sequenceFlow id=\"f2\" sourceRef=\"t\" targetRef=\"e\"/></bpmn:process></bpmn:definitions>";
    HttpResponse<String> r = call("POST", "/api/verify", bpmn);
    assertEquals(200, r.statusCode());
    assertTrue(r.body().contains("MISSING_JOB_TYPE"));
    assertTrue(r.body().contains("Done automatically by a system"));
    assertEquals(400, call("POST", "/api/verify", "not xml").statusCode());
  }

  @Test
  void rulesAreEnforcedAtTheApi() throws Exception {
    assertEquals(404, call("POST", "/api/proposals/nope/backtest", null).statusCode());
    assertEquals(409, call("POST", "/api/proposals/add-analyst/decision", "{\"reviewer\":\"risk.officer\",\"action\":\"APPROVE\"}").statusCode(),
        "no backtest yet");
    assertEquals(200, call("POST", "/api/proposals/add-analyst/backtest", null).statusCode());
    assertEquals(403, call("POST", "/api/proposals/add-analyst/decision", "{\"reviewer\":\"ops.lead\",\"action\":\"APPROVE\"}").statusCode(),
        "ops.lead wrote this proposal");
  }
}
