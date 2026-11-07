package io.github.mkp.pfl.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DecisionLogTest {

  @Test
  void anEditedLineBreaksTheChainAtThatLine() throws Exception {
    Path file = Files.createTempDirectory("log").resolve("decisions.jsonl");
    DecisionLog log = new DecisionLog(file);
    log.append("DECISION", "a", "ops.lead", "APPROVE", "", "IMPROVES", "e1", "m1");
    log.append("DECISION", "b", "ops.lead", "REJECT", "too risky", "UNCLEAR", "e2", "m2");
    log.append("DEPLOYMENT", "a", "ops.lead", "DEPLOYED", "", "IMPROVES", "e1", "m1");
    assertEquals(-1, log.verify());
    List<String> lines = Files.readAllLines(file);
    lines.set(1, lines.get(1).replace("REJECT", "APPROVE"));
    Files.write(file, lines);
    assertEquals(1, log.verify());
  }
}
