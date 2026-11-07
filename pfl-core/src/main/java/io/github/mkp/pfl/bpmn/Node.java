package io.github.mkp.pfl.bpmn;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One flow node of a BPMN process, reduced to what Zeebe executes and what the verifier, explainer
 * and simulator need.
 *
 * @param id BPMN element id
 * @param name label shown in the diagram, may be blank
 * @param kind what kind of element this is
 * @param incoming ids of incoming sequence flows, in document order
 * @param outgoing ids of outgoing sequence flows, in document order
 * @param taskType {@code zeebe:taskDefinition type} for service-like tasks, or null
 * @param candidateGroups {@code zeebe:assignmentDefinition candidateGroups} of a user task (the role
 *     that works on it), empty if none
 * @param nativeUserTask whether a user task carries {@code zeebe:userTask} (Camunda user task)
 * @param event the event definition of an event, or null
 * @param attachedTo for boundary events, the id of the activity it is attached to, else null
 * @param cancelActivity for boundary events, whether it interrupts the activity
 * @param defaultFlow for exclusive gateways, the id of the default flow, else null
 * @param properties {@code zeebe:properties} name/value pairs
 * @param documentation text of {@code bpmn:documentation}, may be blank
 */
public record Node(
    String id,
    String name,
    NodeKind kind,
    List<String> incoming,
    List<String> outgoing,
    String taskType,
    List<String> candidateGroups,
    boolean nativeUserTask,
    EventDefinition event,
    String attachedTo,
    boolean cancelActivity,
    String defaultFlow,
    Map<String, String> properties,
    String documentation) {

  public Node {
    incoming = List.copyOf(incoming);
    outgoing = List.copyOf(outgoing);
    candidateGroups = List.copyOf(candidateGroups);
    properties = Map.copyOf(properties);
    name = name == null ? "" : name.strip();
    documentation = documentation == null ? "" : documentation.strip();
  }

  /** The label a person would recognise: the name, or the id when the diagram left it blank. */
  public String label() {
    return name.isBlank() ? id : name;
  }

  public Optional<String> property(String key) {
    return Optional.ofNullable(properties.get(key));
  }

  /** Who carries out this step, as the explainer and the cost model see it. */
  public Actor actor() {
    if (kind == NodeKind.USER_TASK) {
      return Actor.PERSON;
    }
    if (kind == NodeKind.SERVICE_TASK || kind == NodeKind.SEND_TASK) {
      String declared = properties.get("pfl:actor");
      if ("agent".equals(declared)) {
        return Actor.AGENT;
      }
      return Actor.SYSTEM;
    }
    if (kind == NodeKind.BUSINESS_RULE_TASK) {
      return Actor.RULE;
    }
    return Actor.NONE;
  }

  /** Who performs a step. */
  public enum Actor {
    PERSON,
    AGENT,
    SYSTEM,
    RULE,
    NONE
  }
}
