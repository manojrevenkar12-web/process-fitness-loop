package io.github.mkp.pfl.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.mkp.pfl.camunda.CamundaClient;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Runs the worker against a fake cluster that speaks the /v2 job endpoints and records what it is told. */
class JobWorkerTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void activatesWorksAndReportsEachOutcomeOnTheRightEndpoint() throws Exception {
    List<String> calls = new CopyOnWriteArrayList<>();
    List<Map<String, Object>> bodies = new CopyOnWriteArrayList<>();
    AtomicBoolean served = new AtomicBoolean();
    HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    fake.createContext("/v2/", ex -> {
      try (ex) {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = ex.getRequestURI().getPath();
        calls.add(path);
        bodies.add(body.isEmpty() ? Map.of() : JSON.readValue(body, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        String answer = "";
        if (path.equals("/v2/jobs/activation")) {
          answer = served.compareAndSet(false, true)
              ? "{\"jobs\":[{\"jobKey\":\"11\",\"type\":\"t\",\"processInstanceKey\":\"5\",\"elementId\":\"a\",\"retries\":3,"
                  + "\"variables\":{\"n\":1},\"jobLeaseToken\":\"lease-1\"},"
                  + "{\"jobKey\":\"12\",\"type\":\"t\",\"processInstanceKey\":\"5\",\"elementId\":\"a\",\"retries\":3,\"variables\":{\"n\":2}},"
                  + "{\"jobKey\":\"13\",\"type\":\"t\",\"processInstanceKey\":\"5\",\"elementId\":\"a\",\"retries\":3,\"variables\":{\"n\":3}}]}"
              : "{\"jobs\":[]}";
        }
        byte[] b = answer.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(answer.isEmpty() ? 204 : 200, answer.isEmpty() ? -1 : b.length);
        if (!answer.isEmpty()) {
          ex.getResponseBody().write(b);
        }
      }
    });
    fake.start();
    CamundaClient client = new CamundaClient(URI.create("http://127.0.0.1:" + fake.getAddress().getPort()), null, null);
    JobHandler handler = job -> switch (((Number) job.variables().get("n")).intValue()) {
      case 1 -> new JobResult.Complete(Map.of("done", true));
      case 2 -> new JobResult.BpmnError("PAYMENT_NOT_FOUND", "no");
      default -> new JobResult.Fail("hub down", Duration.ofSeconds(30));
    };
    try (JobWorker worker = new JobWorker(client, "test-worker", Map.of("t", handler), Duration.ofMinutes(1)).start()) {
      long deadline = System.currentTimeMillis() + 5_000;
      while (System.currentTimeMillis() < deadline && worker.counts("t").completed() + worker.counts("t").bpmnErrors()
          + worker.counts("t").failed() < 3) {
        Thread.sleep(20);
      }
      assertEquals(new JobWorker.Counts(1, 1, 1), worker.counts("t"));
    } finally {
      fake.stop(0);
    }
    assertTrue(calls.contains("/v2/jobs/11/completion"));
    assertTrue(calls.contains("/v2/jobs/12/error"));
    assertTrue(calls.contains("/v2/jobs/13/failure"));
    Map<String, Object> activation = bodies.get(calls.indexOf("/v2/jobs/activation"));
    assertEquals("t", activation.get("type"));
    assertEquals(16, activation.get("maxJobsToActivate"));
    assertEquals(60_000, ((Number) activation.get("timeout")).intValue());
    Map<String, Object> completion = bodies.get(calls.indexOf("/v2/jobs/11/completion"));
    assertEquals("lease-1", completion.get("jobLeaseToken"), "the lease token goes back with the result");
    assertEquals(Map.of("done", true), completion.get("variables"));
    Map<String, Object> failure = bodies.get(calls.indexOf("/v2/jobs/13/failure"));
    assertEquals(2, failure.get("retries"));
    assertEquals(30_000, ((Number) failure.get("retryBackOff")).intValue());
    assertEquals("PAYMENT_NOT_FOUND", bodies.get(calls.indexOf("/v2/jobs/12/error")).get("errorCode"));
  }
}
