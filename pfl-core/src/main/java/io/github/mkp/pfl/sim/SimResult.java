package io.github.mkp.pfl.sim;

import io.github.mkp.pfl.fitness.CaseOutcome;
import io.github.mkp.pfl.log.EventLog;
import java.util.List;

/** All simulated cases of one run. */
public record SimResult(String processId, List<SimCase> cases) {

  public SimResult {
    cases = List.copyOf(cases);
  }

  public List<CaseOutcome> outcomes() {
    return cases.stream().map(SimCase::outcome).toList();
  }

  public EventLog log() {
    return new EventLog(processId, cases.stream().map(SimCase::record).toList());
  }

  public long incidents() {
    return cases.stream().filter(c -> c.incident() != null).count();
  }
}
