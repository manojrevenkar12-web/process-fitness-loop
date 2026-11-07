package io.github.mkp.pfl.bpmn;

/** The BPMN elements this toolkit understands. Anything else is reported, never silently dropped. */
public enum NodeKind {
  START_EVENT("startEvent"),
  END_EVENT("endEvent"),
  INTERMEDIATE_CATCH_EVENT("intermediateCatchEvent"),
  BOUNDARY_EVENT("boundaryEvent"),
  SERVICE_TASK("serviceTask"),
  SEND_TASK("sendTask"),
  USER_TASK("userTask"),
  BUSINESS_RULE_TASK("businessRuleTask"),
  SCRIPT_TASK("scriptTask"),
  MANUAL_TASK("manualTask"),
  TASK("task"),
  EXCLUSIVE_GATEWAY("exclusiveGateway"),
  PARALLEL_GATEWAY("parallelGateway");

  private final String element;

  NodeKind(String element) {
    this.element = element;
  }

  /** Local name of the BPMN XML element. */
  public String element() {
    return element;
  }

  public boolean isActivity() {
    return switch (this) {
      case SERVICE_TASK, SEND_TASK, USER_TASK, BUSINESS_RULE_TASK, SCRIPT_TASK, MANUAL_TASK, TASK ->
          true;
      default -> false;
    };
  }

  public boolean isGateway() {
    return this == EXCLUSIVE_GATEWAY || this == PARALLEL_GATEWAY;
  }

  /** Activities Zeebe hands to a job worker and that therefore need a job type. */
  public boolean needsJobType() {
    return this == SERVICE_TASK || this == SEND_TASK || this == SCRIPT_TASK;
  }

  public static NodeKind fromElement(String localName) {
    for (NodeKind kind : values()) {
      if (kind.element.equals(localName)) {
        return kind;
      }
    }
    return null;
  }
}
