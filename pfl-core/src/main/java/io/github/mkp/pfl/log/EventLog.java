package io.github.mkp.pfl.log;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Case histories of one process, ordered by arrival. */
public record EventLog(String processId, List<CaseRecord> cases) {

  public EventLog {
    cases = cases.stream().sorted(Comparator.comparing(CaseRecord::arrival).thenComparing(CaseRecord::caseId)).toList();
  }

  public EventLog filter(Predicate<CaseRecord> keep) {
    return new EventLog(processId, cases.stream().filter(keep).toList());
  }

  /** Cases that arrived in [from, to). */
  public EventLog between(Instant from, Instant to) {
    return filter(c -> !c.arrival().isBefore(from) && c.arrival().isBefore(to));
  }

  public int size() {
    return cases.size();
  }
}
