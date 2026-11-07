package io.github.mkp.pfl.log;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes event logs as JSON Lines: a header line naming the process, then one case per
 * line. One case per line keeps diffs readable and lets large histories stream.
 */
public final class EventLogIo {

  private static final ObjectMapper JSON = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  private EventLogIo() {}

  record Header(String processId, int cases) {}

  record JsonEvent(String a, Lifecycle l, Instant t, String r, Map<String, Object> o) {}

  record JsonCase(String caseId, Instant arrival, Map<String, Object> attributes, List<JsonEvent> events) {}

  public static void write(EventLog log, Path file) throws IOException {
    Files.createDirectories(file.toAbsolutePath().getParent());
    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      out.write(JSON.writeValueAsString(new Header(log.processId(), log.size())));
      out.write('\n');
      for (CaseRecord c : log.cases()) {
        List<JsonEvent> events = new ArrayList<>();
        for (Event e : c.events()) {
          events.add(new JsonEvent(e.activity(), e.lifecycle(), e.time(), e.resource(), e.output().isEmpty() ? null : new java.util.TreeMap<>(e.output())));
        }
        out.write(JSON.writeValueAsString(new JsonCase(c.caseId(), c.arrival(), new java.util.TreeMap<>(c.attributes()), events)));
        out.write('\n');
      }
    }
  }

  public static EventLog read(Path file) throws IOException {
    try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String first = in.readLine();
      if (first == null) {
        throw new IOException(file + " is empty");
      }
      Header header = JSON.readValue(first, Header.class);
      List<CaseRecord> cases = new ArrayList<>();
      String line;
      while ((line = in.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        JsonCase c = JSON.readValue(line, JsonCase.class);
        List<Event> events = new ArrayList<>();
        for (JsonEvent e : c.events()) {
          events.add(new Event(e.a(), e.l(), e.t(), e.r(), e.o() == null ? Map.of() : normalise(e.o())));
        }
        cases.add(new CaseRecord(c.caseId(), c.arrival(), normalise(c.attributes()), events));
      }
      if (cases.size() != header.cases()) {
        throw new IOException(file + " announces " + header.cases() + " cases but holds " + cases.size());
      }
      return new EventLog(header.processId(), cases);
    }
  }

  /** JSON numbers come back as Integer, Long or BigDecimal; keep them, drop nulls Map.copyOf refuses. */
  private static Map<String, Object> normalise(Map<String, Object> in) {
    Map<String, Object> out = new LinkedHashMap<>();
    in.forEach((k, v) -> {
      if (v != null) {
        out.put(k, v);
      }
    });
    return out;
  }
}
