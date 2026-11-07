package io.github.mkp.pfl.hub;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** The hub gateway over HTTP: one text/plain POST per request line. */
public final class HttpHubTransport implements HubTransport {

  private final HttpClient http;
  private final URI endpoint;
  private final Duration timeout;

  public HttpHubTransport(URI endpoint, Duration timeout) {
    this.endpoint = endpoint;
    this.timeout = timeout;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  }

  @Override
  public String exchange(String request) throws TransportFailure {
    HttpRequest req = HttpRequest.newBuilder(endpoint).timeout(timeout)
        .header("Content-Type", "text/plain; charset=US-ASCII")
        .POST(HttpRequest.BodyPublishers.ofString(request, StandardCharsets.US_ASCII)).build();
    try {
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.US_ASCII));
      if (res.statusCode() == 503) {
        throw new TransportFailure("hub overloaded (503)", false);
      }
      if (res.statusCode() != 200) {
        throw new TransportFailure("hub answered HTTP " + res.statusCode(), true);
      }
      return res.body();
    } catch (HttpTimeoutException e) {
      throw new TransportFailure("timed out waiting for the hub", true);
    } catch (ConnectException e) {
      throw new TransportFailure("cannot connect to the hub: " + e.getMessage(), false);
    } catch (IOException e) {
      throw new TransportFailure("connection to the hub failed: " + e.getMessage(), true);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TransportFailure("interrupted", true);
    }
  }
}
