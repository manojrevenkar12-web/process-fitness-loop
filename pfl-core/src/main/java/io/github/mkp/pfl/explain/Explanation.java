package io.github.mkp.pfl.explain;

import java.util.List;

/**
 * A process written out as numbered steps.
 *
 * @param processId the process explained
 * @param title the process name
 * @param summary one paragraph: how many steps, who does them, how many decisions
 * @param steps the steps in reading order
 */
public record Explanation(String processId, String title, String summary, List<Step> steps) {

  public Explanation {
    steps = List.copyOf(steps);
  }

  /** Markdown for a review document or a pull request. */
  public String toMarkdown() {
    StringBuilder md = new StringBuilder();
    md.append("## ").append(title).append("\n\n").append(summary).append("\n\n");
    for (Step step : steps) {
      md.append(step.number()).append(". **").append(step.label()).append("** (`").append(step.elementId())
          .append("`). ").append(step.text()).append("\n");
    }
    return md.toString();
  }
}
