package io.github.mkp.pfl.sim;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** Test behaviour: fixed minutes per step, outputs and errors by step and occurrence. */
public final class FixedBehaviour implements Behaviour {
  private final Map<String, Double> minutes = new HashMap<>();
  private final Map<String, Map<String, Object>> outputs = new HashMap<>();
  private final Map<String, String> errors = new HashMap<>();
  private double defaultMinutes = 10;
  private Duration reply = Duration.ofHours(4);
  private Function<StepContext, Map<String, Object>> dynamicOutputs = ctx -> null;

  public FixedBehaviour minutes(String node, double m) {
    minutes.put(node, m);
    return this;
  }

  public FixedBehaviour defaultMinutes(double m) {
    defaultMinutes = m;
    return this;
  }

  /** Output for the n-th occurrence of a step in every case; key "node#n" or "node" for all. */
  public FixedBehaviour output(String key, Map<String, Object> out) {
    outputs.put(key, out);
    return this;
  }

  public FixedBehaviour outputs(Function<StepContext, Map<String, Object>> f) {
    dynamicOutputs = f;
    return this;
  }

  public FixedBehaviour error(String key, String code) {
    errors.put(key, code);
    return this;
  }

  public FixedBehaviour reply(Duration d) {
    reply = d;
    return this;
  }

  @Override
  public Duration serviceTime(StepContext step) {
    return Duration.ofMillis(Math.round(minutes.getOrDefault(step.nodeId(), defaultMinutes) * 60_000));
  }

  @Override
  public Map<String, Object> outputs(StepContext step) {
    Map<String, Object> dyn = dynamicOutputs.apply(step);
    if (dyn != null) {
      return dyn;
    }
    Map<String, Object> out = outputs.get(step.nodeId() + "#" + step.occurrence());
    if (out == null) {
      out = outputs.get(step.nodeId());
    }
    return out == null ? Map.of() : out;
  }

  @Override
  public Duration messageDelay(StepContext step) {
    return reply;
  }

  @Override
  public String error(StepContext step) {
    String e = errors.get(step.caseId() + ":" + step.nodeId());
    return e != null ? e : errors.get(step.nodeId());
  }
}
