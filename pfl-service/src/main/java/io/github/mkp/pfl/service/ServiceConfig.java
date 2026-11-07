package io.github.mkp.pfl.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Where the service finds things, from {@code config/service.json}. Paths are relative to the
 * repository root.
 *
 * @param port HTTP port of the API
 * @param today the process that runs today
 * @param documented the process as the procedure document describes it
 * @param history the recorded history of today's process
 * @param proposals folder of proposal files
 * @param decisions the append-only decision log
 * @param camundaUrl the cluster to deploy approved changes to, or null
 */
public record ServiceConfig(int port, String today, String documented, String history, String proposals, String decisions,
    String camundaUrl) {

  public static ServiceConfig load(Path file) throws IOException {
    return new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(file.toFile(), ServiceConfig.class);
  }
}
