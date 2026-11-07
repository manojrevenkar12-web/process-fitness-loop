package io.github.mkp.pfl.bpmn;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One executable BPMN process as parsed from a file.
 *
 * @param id process id ({@code bpmnProcessId} in Zeebe)
 * @param name process name, may be blank
 * @param executable value of {@code isExecutable}
 * @param nodes flow nodes by id, in document order
 * @param flows sequence flows by id, in document order
 * @param findings things the parser met but could not model: unsupported elements, references to
 *     ids that do not exist. The verifier turns these into issues.
 */
public record ProcessModel(
    String id,
    String name,
    boolean executable,
    Map<String, Node> nodes,
    Map<String, Flow> flows,
    List<ParseFinding> findings) {

  public ProcessModel {
    nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
    flows = Collections.unmodifiableMap(new LinkedHashMap<>(flows));
    findings = List.copyOf(findings);
    name = name == null ? "" : name.strip();
  }

  public Node node(String nodeId) {
    Node node = nodes.get(nodeId);
    if (node == null) {
      throw new IllegalArgumentException("no node " + nodeId + " in process " + id);
    }
    return node;
  }

  public Optional<Node> findNode(String nodeId) {
    return Optional.ofNullable(nodes.get(nodeId));
  }

  public Flow flow(String flowId) {
    Flow flow = flows.get(flowId);
    if (flow == null) {
      throw new IllegalArgumentException("no flow " + flowId + " in process " + id);
    }
    return flow;
  }

  public List<Flow> outgoing(Node node) {
    return node.outgoing().stream().map(flows::get).filter(f -> f != null).toList();
  }

  public List<Flow> incoming(Node node) {
    return node.incoming().stream().map(flows::get).filter(f -> f != null).toList();
  }

  /** Boundary events attached to the given activity, in document order. */
  public List<Node> boundaryEventsOf(String activityId) {
    return nodes.values().stream()
        .filter(n -> n.kind() == NodeKind.BOUNDARY_EVENT && activityId.equals(n.attachedTo()))
        .toList();
  }

  public List<Node> nodesOfKind(NodeKind kind) {
    return nodes.values().stream().filter(n -> n.kind() == kind).toList();
  }

  public String label() {
    return name.isBlank() ? id : name;
  }

  /**
   * Something the parser could not represent.
   *
   * @param code stable code, e.g. {@code UNSUPPORTED_ELEMENT}
   * @param elementId id of the offending element, may be null
   * @param detail human-readable detail
   */
  public record ParseFinding(String code, String elementId, String detail) {}
}
