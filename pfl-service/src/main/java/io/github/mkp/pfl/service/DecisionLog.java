package io.github.mkp.pfl.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Every decision about a process change and every deployment, appended to a file and chained by
 * hash, so a later edit to any line breaks the chain and {@link #verify()} says where.
 */
public final class DecisionLog {

  /**
   * One entry.
   *
   * @param kind DECISION or DEPLOYMENT
   * @param proposal the proposal
   * @param actor who acted
   * @param action APPROVE, APPROVE_SHADOW, REJECT, or DEPLOYED
   * @param note free text from the actor
   * @param verdict the backtest verdict the actor saw
   * @param evidenceSha hash of the backtest result the decision rests on
   * @param modelSha hash of the BPMN file the decision is about
   * @param at when
   * @param previous hash of the previous entry
   * @param hash hash of this entry
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Entry(String kind, String proposal, String actor, String action, String note, String verdict,
      String evidenceSha, String modelSha, Instant at, String previous, String hash) {}

  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
      .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final Path file;

  public DecisionLog(Path file) {
    this.file = file;
  }

  public synchronized List<Entry> entries() throws IOException {
    List<Entry> out = new ArrayList<>();
    if (!Files.exists(file)) {
      return out;
    }
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      if (!line.isBlank()) {
        out.add(JSON.readValue(line, Entry.class));
      }
    }
    return out;
  }

  public synchronized Entry append(String kind, String proposal, String actor, String action, String note, String verdict,
      String evidenceSha, String modelSha) throws IOException {
    List<Entry> existing = entries();
    String previous = existing.isEmpty() ? "genesis" : existing.get(existing.size() - 1).hash();
    Entry unsigned = new Entry(kind, proposal, actor, action, note, verdict, evidenceSha, modelSha, Instant.now(), previous, null);
    Entry signed = new Entry(kind, proposal, actor, action, note, verdict, evidenceSha, modelSha, unsigned.at(), previous,
        sha256(JSON.writeValueAsString(unsigned)));
    Files.createDirectories(file.toAbsolutePath().getParent());
    Files.writeString(file, JSON.writeValueAsString(signed) + "\n", StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    return signed;
  }

  /** Returns the index of the first entry that does not match its hash or its predecessor, or -1. */
  public synchronized int verify() throws IOException {
    List<Entry> entries = entries();
    String previous = "genesis";
    for (int i = 0; i < entries.size(); i++) {
      Entry e = entries.get(i);
      Entry unsigned = new Entry(e.kind(), e.proposal(), e.actor(), e.action(), e.note(), e.verdict(), e.evidenceSha(),
          e.modelSha(), e.at(), e.previous(), null);
      if (!e.previous().equals(previous) || !e.hash().equals(sha256(JSON.writeValueAsString(unsigned)))) {
        return i;
      }
      previous = e.hash();
    }
    return -1;
  }

  public static String sha256(String text) {
    return sha256(text.getBytes(StandardCharsets.UTF_8));
  }

  public static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
