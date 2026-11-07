package io.github.mkp.pfl.verify;

import java.util.List;

/**
 * Everything the verifier found about one process.
 *
 * @param processId the process checked
 * @param issues all issues, deploy problems first
 * @param statesExplored how many distinct token placements the behaviour check visited
 * @param explorationComplete false when the behaviour check hit its state limit, so "no behaviour
 *     issues" is not a proof
 */
public record VerificationReport(
    String processId, List<Issue> issues, int statesExplored, boolean explorationComplete) {

  public VerificationReport {
    issues = List.copyOf(issues);
  }

  /** True when nothing predicts that the engine refuses the file. */
  public boolean deployable() {
    return issues.stream()
        .noneMatch(i -> i.category() == Issue.Category.DEPLOY && i.severity() == Issue.Severity.ERROR);
  }

  /** True when it deploys and every run can finish without hanging or duplicating work. */
  public boolean sound() {
    return deployable()
        && explorationComplete
        && issues.stream()
            .noneMatch(i -> i.category() == Issue.Category.BEHAVIOUR && i.severity() == Issue.Severity.ERROR);
  }

  public List<Issue> byCategory(Issue.Category category) {
    return issues.stream().filter(i -> i.category() == category).toList();
  }

  public boolean has(String code) {
    return issues.stream().anyMatch(i -> i.code().equals(code));
  }
}
