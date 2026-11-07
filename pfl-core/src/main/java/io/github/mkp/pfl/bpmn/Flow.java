package io.github.mkp.pfl.bpmn;

/**
 * A sequence flow.
 *
 * @param id BPMN element id
 * @param name label, may be blank
 * @param source id of the source node
 * @param target id of the target node
 * @param condition FEEL condition expression including the leading {@code =}, or null
 */
public record Flow(String id, String name, String source, String target, String condition) {

  public Flow {
    name = name == null ? "" : name.strip();
    condition = condition == null || condition.isBlank() ? null : condition.strip();
  }
}
