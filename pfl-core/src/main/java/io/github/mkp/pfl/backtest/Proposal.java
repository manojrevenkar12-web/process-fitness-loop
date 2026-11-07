package io.github.mkp.pfl.backtest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A proposed change to a process, with the evidence it claims for every step that differs from
 * today.
 *
 * @param id short identifier
 * @param title one line for the review queue
 * @param rationale why someone proposed it
 * @param model path of the candidate BPMN, relative to the repository root
 * @param teams team sizes to use instead of today's staffing, for staffing changes
 * @param steps evidence per candidate step. A step without an entry uses the history of the step
 *     with the same id, if today's process has it and the same kind of actor does it; otherwise the
 *     backtest refuses to run, because every number must have a source.
 * @param author who proposed it; a different person must approve it
 */
public record Proposal(
    String id, String title, String rationale, String model, Map<String, Integer> teams,
    Map<String, StepEvidence> steps, String author) {

  public Proposal {
    teams = teams == null ? Map.of() : Map.copyOf(teams);
    steps = steps == null ? Map.of() : Map.copyOf(steps);
  }

  /**
   * Evidence for one candidate step.
   *
   * @param history the step of today's process whose recorded durations stand for this one
   * @param outputsFrom the step of today's process whose recorded decisions stand for this one's
   *     outputs (an agent that classifies inquiries makes the decisions people made)
   * @param assume an assumption, used when there is no history of the work
   */
  public record StepEvidence(String history, String outputsFrom, Assumption assume) {}

  /**
   * An assumption about work nobody has done yet.
   *
   * @param medianMinutes typical duration
   * @param p90Minutes duration nine cases in ten stay under
   * @param errors probability of each BPMN error code the step may throw
   * @param outputs per variable, the probability of each value
   * @param uncertainty how far off the median may be, as a factor (1.5 means anywhere from ÷1.5 to
   *     ×1.5); the backtest varies it across replications so the interval reflects it
   * @param source where the numbers come from
   * @param measured whether the numbers were measured (a staging run, a pilot) rather than
   *     estimated; a proposal resting on an unmeasured assumption is never approved without a
   *     shadow run
   * @param agreement for a step that takes over decisions recorded for another step: the range of
   *     the share of cases in which it decides the same way, e.g. [0.9, 1.0]. The backtest varies
   *     it across replications; where it disagrees, yes/no outputs flip.
   */
  public record Assumption(
      double medianMinutes, double p90Minutes, Map<String, Double> errors,
      Map<String, Map<String, Double>> outputs, Double uncertainty, String source, boolean measured,
      List<Double> agreement) {

    public Assumption {
      agreement = agreement == null ? List.of(1.0, 1.0) : List.copyOf(agreement);
      if (agreement.size() != 2 || agreement.get(0) > agreement.get(1) || agreement.get(0) < 0 || agreement.get(1) > 1) {
        throw new IllegalArgumentException("agreement is a range [low, high] within [0, 1]");
      }
      errors = errors == null ? Map.of() : Map.copyOf(errors);
      outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
      uncertainty = uncertainty == null ? 1.5 : uncertainty;
      if (uncertainty < 1.0) {
        throw new IllegalArgumentException("uncertainty is a factor of at least 1");
      }
      if (source == null || source.isBlank()) {
        throw new IllegalArgumentException("an assumption needs a source");
      }
    }
  }

  public static Proposal load(Path file) throws IOException {
    return new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(file.toFile(), Proposal.class);
  }

  public static List<Proposal> loadAll(Path dir) throws IOException {
    try (var files = java.nio.file.Files.list(dir)) {
      List<Path> paths = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
      List<Proposal> out = new java.util.ArrayList<>();
      for (Path p : paths) {
        out.add(load(p));
      }
      return out;
    }
  }
}
