package io.github.mkp.pfl.camunda;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The parts of the Camunda 8 Orchestration Cluster REST API ({@code /v2}) a deployment needs:
 * deploy a model, start instances, work jobs and user tasks, publish messages and read history.
 *
 * <p>Plain JDK HTTP and Jackson, nothing else: inside a bank every dependency goes through a
 * security review, and a field kit that needs two is easier to get approved than one that needs
 * sixty.
 */
public final class CamundaClient {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final URI base;
  private final String authorization;

  /** @param base e.g. {@code http://localhost:8080}; credentials null for an unprotected cluster */
  public CamundaClient(URI base, String user, String password) {
    this.base = base;
    this.authorization = user == null ? null
        : "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  // ------------------------------------------------------------------ cluster and deployment

  public Map<String, Object> topology() {
    return send(get("/v2/topology"), Duration.ofSeconds(10));
  }

  /** Deploys one BPMN file; returns the process definition key and version. */
  public Map<String, Object> deploy(String fileName, byte[] bpmn) {
    String boundary = "pfl-" + UUID.randomUUID();
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"resources\"; filename=\"" + fileName
        + "\"\r\nContent-Type: application/xml\r\n\r\n";
    body.writeBytes(head.getBytes(StandardCharsets.UTF_8));
    body.writeBytes(bpmn);
    body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    HttpRequest.Builder req = request("/v2/deployments")
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
    return send(req, Duration.ofSeconds(30));
  }

  public String startInstance(String processId, Map<String, Object> variables) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("processDefinitionId", processId);
    body.put("variables", variables);
    Map<String, Object> res = send(post("/v2/process-instances", body), Duration.ofSeconds(10));
    return String.valueOf(res.get("processInstanceKey"));
  }

  // ------------------------------------------------------------------ jobs

  /** Long-polls for jobs of one type. */
  public List<ActivatedJob> activate(String type, String worker, int max, Duration lock, Duration wait) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", type);
    body.put("worker", worker);
    body.put("maxJobsToActivate", max);
    body.put("timeout", lock.toMillis());
    body.put("requestTimeout", wait.toMillis());
    Map<String, Object> res = send(post("/v2/jobs/activation", body), wait.plusSeconds(10));
    List<ActivatedJob> jobs = new ArrayList<>();
    for (Map<String, Object> j : list(res.get("jobs"))) {
      jobs.add(new ActivatedJob(String.valueOf(j.get("jobKey")), (String) j.get("type"), String.valueOf(j.get("processInstanceKey")),
          (String) j.get("elementId"), ((Number) j.getOrDefault("retries", 0)).intValue(), map(j.get("variables")),
          j.get("jobLeaseToken") == null ? null : String.valueOf(j.get("jobLeaseToken"))));
    }
    return jobs;
  }

  public void complete(ActivatedJob job, Map<String, Object> variables) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("variables", variables);
    lease(job, body);
    send(post("/v2/jobs/" + job.jobKey() + "/completion", body), Duration.ofSeconds(10));
  }

  public void fail(ActivatedJob job, int retries, String message, Duration backoff) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("retries", retries);
    body.put("errorMessage", message);
    body.put("retryBackOff", backoff.toMillis());
    lease(job, body);
    send(post("/v2/jobs/" + job.jobKey() + "/failure", body), Duration.ofSeconds(10));
  }

  public void throwError(ActivatedJob job, String code, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("errorCode", code);
    body.put("errorMessage", message);
    lease(job, body);
    send(post("/v2/jobs/" + job.jobKey() + "/error", body), Duration.ofSeconds(10));
  }

  private static void lease(ActivatedJob job, Map<String, Object> body) {
    if (job.leaseToken() != null) {
      body.put("jobLeaseToken", job.leaseToken());
    }
  }

  // ------------------------------------------------------------------ user tasks and messages

  /** Open user tasks of one process instance. */
  public List<Map<String, Object>> openUserTasks(String processInstanceKey) {
    Map<String, Object> filter = new LinkedHashMap<>();
    filter.put("processInstanceKey", processInstanceKey);
    filter.put("state", "CREATED");
    return list(send(post("/v2/user-tasks/search", Map.of("filter", filter, "page", Map.of("limit", 100))), Duration.ofSeconds(10))
        .get("items"));
  }

  public void completeUserTask(String userTaskKey, Map<String, Object> variables) {
    send(post("/v2/user-tasks/" + userTaskKey + "/completion", Map.of("variables", variables)), Duration.ofSeconds(10));
  }

  public void publishMessage(String name, String correlationKey, Map<String, Object> variables) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("correlationKey", correlationKey);
    body.put("variables", variables);
    body.put("timeToLive", 60_000);
    send(post("/v2/messages/publication", body), Duration.ofSeconds(10));
  }

  // ------------------------------------------------------------------ history

  /** Element instances of a process instance in the order they were created. */
  public List<Map<String, Object>> elementInstances(String processInstanceKey) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("filter", Map.of("processInstanceKey", processInstanceKey));
    body.put("sort", List.of(Map.of("field", "elementInstanceKey", "order", "ASC")));
    body.put("page", Map.of("limit", 500));
    return list(send(post("/v2/element-instances/search", body), Duration.ofSeconds(10)).get("items"));
  }

  public Map<String, Object> processInstance(String processInstanceKey) {
    return send(get("/v2/process-instances/" + processInstanceKey), Duration.ofSeconds(10));
  }

  // ------------------------------------------------------------------ plumbing

  private HttpRequest.Builder request(String path) {
    HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path)).header("Accept", "application/json");
    if (authorization != null) {
      b.header("Authorization", authorization);
    }
    return b;
  }

  private HttpRequest.Builder get(String path) {
    return request(path).GET();
  }

  private HttpRequest.Builder post(String path, Object body) {
    try {
      return request(path).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    } catch (IOException e) {
      throw new IllegalArgumentException("cannot serialise request body", e);
    }
  }

  private Map<String, Object> send(HttpRequest.Builder builder, Duration timeout) {
    HttpRequest req = builder.timeout(timeout).build();
    try {
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() >= 300) {
        throw new CamundaException(res.statusCode(), req.method() + " " + req.uri().getPath() + " -> " + res.statusCode() + ": " + res.body());
      }
      if (res.body() == null || res.body().isBlank()) {
        return Map.of();
      }
      return JSON.readValue(res.body(), new TypeReference<>() {});
    } catch (IOException e) {
      throw new CamundaException(0, req.method() + " " + req.uri().getPath() + " failed: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CamundaException(0, "interrupted");
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> list(Object value) {
    return value == null ? List.of() : (List<Map<String, Object>>) value;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return value == null ? Map.of() : (Map<String, Object>) value;
  }
}
