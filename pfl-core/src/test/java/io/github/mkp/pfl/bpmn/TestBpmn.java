package io.github.mkp.pfl.bpmn;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Builds small BPMN files for tests without hand-writing XML. */
public final class TestBpmn {
  private final String processId;
  private final List<String> elements = new ArrayList<>();
  private final List<String> definitions = new ArrayList<>();

  private TestBpmn(String processId) {
    this.processId = processId;
  }

  public static TestBpmn process(String id) {
    return new TestBpmn(id);
  }

  public TestBpmn raw(String xml) {
    elements.add(xml);
    return this;
  }

  public TestBpmn definition(String xml) {
    definitions.add(xml);
    return this;
  }

  public TestBpmn start(String id) {
    return raw("<bpmn:startEvent id=\"" + id + "\" />");
  }

  public TestBpmn end(String id) {
    return raw("<bpmn:endEvent id=\"" + id + "\" />");
  }

  public TestBpmn userTask(String id, String name, String group) {
    return raw("<bpmn:userTask id=\"" + id + "\" name=\"" + name + "\"><bpmn:extensionElements><zeebe:userTask />"
        + "<zeebe:assignmentDefinition candidateGroups=\"" + group + "\" /></bpmn:extensionElements></bpmn:userTask>");
  }

  public TestBpmn serviceTask(String id, String name, String type) {
    return raw("<bpmn:serviceTask id=\"" + id + "\" name=\"" + name + "\"><bpmn:extensionElements>"
        + "<zeebe:taskDefinition type=\"" + type + "\" /></bpmn:extensionElements></bpmn:serviceTask>");
  }

  public TestBpmn xor(String id, String defaultFlow) {
    return raw("<bpmn:exclusiveGateway id=\"" + id + "\"" + (defaultFlow == null ? "" : " default=\"" + defaultFlow + "\"") + " />");
  }

  public TestBpmn and(String id) {
    return raw("<bpmn:parallelGateway id=\"" + id + "\" />");
  }

  public TestBpmn flow(String id, String source, String target) {
    return raw("<bpmn:sequenceFlow id=\"" + id + "\" sourceRef=\"" + source + "\" targetRef=\"" + target + "\" />");
  }

  public TestBpmn flow(String id, String source, String target, String condition) {
    return raw("<bpmn:sequenceFlow id=\"" + id + "\" sourceRef=\"" + source + "\" targetRef=\"" + target + "\">"
        + "<bpmn:conditionExpression>" + condition.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        + "</bpmn:conditionExpression></bpmn:sequenceFlow>");
  }

  public String xml() {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
        + "xmlns:zeebe=\"http://camunda.org/schema/zeebe/1.0\" id=\"d\" targetNamespace=\"t\">"
        + String.join("", definitions)
        + "<bpmn:process id=\"" + processId + "\" isExecutable=\"true\">" + String.join("", elements)
        + "</bpmn:process></bpmn:definitions>";
  }

  public ProcessModel parse() {
    return new BpmnParser().parse(xml().getBytes(StandardCharsets.UTF_8));
  }
}
