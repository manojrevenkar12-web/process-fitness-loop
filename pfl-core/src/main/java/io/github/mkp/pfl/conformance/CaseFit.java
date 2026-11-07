package io.github.mkp.pfl.conformance;

import java.util.List;

/**
 * Token replay counts for one case.
 *
 * @param caseId the case
 * @param produced tokens produced
 * @param consumed tokens consumed
 * @param missing tokens that had to be added because a step ran when the model did not allow it
 * @param remaining tokens left when the history ended
 * @param problems keys of the deviations seen in this case, e.g. {@code RAN_WHEN_NOT_ALLOWED:send_recall}
 */
public record CaseFit(String caseId, int produced, int consumed, int missing, int remaining, List<String> problems) {

  public CaseFit {
    problems = List.copyOf(problems);
  }

  public double fitness() {
    double mPart = consumed == 0 ? 1.0 : 1.0 - (double) missing / consumed;
    double rPart = produced == 0 ? 1.0 : 1.0 - (double) remaining / produced;
    return 0.5 * mPart + 0.5 * rPart;
  }

  public boolean perfect() {
    return missing == 0 && remaining == 0 && problems.isEmpty();
  }
}
