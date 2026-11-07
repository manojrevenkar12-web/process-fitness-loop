package io.github.mkp.pfl.bpmn;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Reads the executable process of a BPMN 2.0 file, including the Zeebe extension elements.
 *
 * <p>The parser is strict about XML (doctypes are refused, so no external entities) and lenient
 * about BPMN: elements it cannot model and references that do not resolve become {@link
 * ProcessModel.ParseFinding}s for the verifier rather than exceptions, because a generated file
 * with three problems should get three findings, not one stack trace.
 */
public final class BpmnParser {

  public static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
  public static final String ZEEBE_NS = "http://camunda.org/schema/zeebe/1.0";

  /** Elements that carry no execution semantics and are skipped without a finding. */
  private static final Set<String> IGNORED =
      Set.of(
          "documentation",
          "extensionElements",
          "laneSet",
          "textAnnotation",
          "association",
          "dataObject",
          "dataObjectReference",
          "dataStoreReference",
          "group",
          "category");

  public ProcessModel parse(Path file) throws IOException {
    return parse(Files.readAllBytes(file));
  }

  public ProcessModel parse(byte[] xml) {
    Document document = read(xml);
    Element definitions = document.getDocumentElement();
    if (!BPMN_NS.equals(definitions.getNamespaceURI())
        || !"definitions".equals(definitions.getLocalName())) {
      throw new BpmnParseException("not a BPMN 2.0 file: root element is " + definitions.getTagName());
    }

    Map<String, Message> messages = new HashMap<>();
    Map<String, String> errorCodes = new HashMap<>();
    Element process = null;
    int executableProcesses = 0;
    for (Element child : children(definitions)) {
      if (!BPMN_NS.equals(child.getNamespaceURI())) {
        continue;
      }
      switch (child.getLocalName()) {
        case "message" -> messages.put(child.getAttribute("id"), readMessage(child));
        case "error" -> errorCodes.put(child.getAttribute("id"), child.getAttribute("errorCode"));
        case "process" -> {
          if (!"false".equals(child.getAttribute("isExecutable"))) {
            executableProcesses++;
            if (process == null) {
              process = child;
            }
          }
        }
        default -> {
          // collaborations, diagrams and the like do not change execution
        }
      }
    }
    if (process == null) {
      throw new BpmnParseException("the file has no executable process");
    }

    List<ProcessModel.ParseFinding> findings = new ArrayList<>();
    if (executableProcesses > 1) {
      findings.add(
          new ProcessModel.ParseFinding(
              "MULTIPLE_PROCESSES",
              process.getAttribute("id"),
              "the file has " + executableProcesses + " executable processes; only the first is checked"));
    }

    Map<String, Flow> flows = new LinkedHashMap<>();
    Map<String, Element> nodeElements = new LinkedHashMap<>();
    Map<String, NodeKind> kinds = new HashMap<>();
    Set<String> seenIds = new java.util.HashSet<>();
    for (Element child : children(process)) {
      String local = child.getLocalName();
      String id = child.getAttribute("id");
      if (!BPMN_NS.equals(child.getNamespaceURI()) || IGNORED.contains(local)) {
        continue;
      }
      if (!id.isEmpty() && !seenIds.add(id)) {
        findings.add(new ProcessModel.ParseFinding("DUPLICATE_ID", id, "the id " + id + " is used twice"));
        continue;
      }
      if ("sequenceFlow".equals(local)) {
        flows.put(
            id,
            new Flow(
                id,
                child.getAttribute("name"),
                child.getAttribute("sourceRef"),
                child.getAttribute("targetRef"),
                textOf(child, BPMN_NS, "conditionExpression")));
        continue;
      }
      NodeKind kind = NodeKind.fromElement(local);
      if (kind == null) {
        findings.add(
            new ProcessModel.ParseFinding(
                "UNSUPPORTED_ELEMENT", id, "bpmn:" + local + " is not supported by this toolkit"));
        continue;
      }
      nodeElements.put(id, child);
      kinds.put(id, kind);
    }

    Map<String, List<String>> incoming = new HashMap<>();
    Map<String, List<String>> outgoing = new HashMap<>();
    for (Flow flow : flows.values()) {
      if (flow.source().isEmpty() || !nodeElements.containsKey(flow.source())) {
        findings.add(
            new ProcessModel.ParseFinding(
                "DANGLING_FLOW", flow.id(), "source '" + flow.source() + "' is not a node of the process"));
      } else {
        outgoing.computeIfAbsent(flow.source(), k -> new ArrayList<>()).add(flow.id());
      }
      if (flow.target().isEmpty() || !nodeElements.containsKey(flow.target())) {
        findings.add(
            new ProcessModel.ParseFinding(
                "DANGLING_FLOW", flow.id(), "target '" + flow.target() + "' is not a node of the process"));
      } else {
        incoming.computeIfAbsent(flow.target(), k -> new ArrayList<>()).add(flow.id());
      }
    }

    Map<String, io.github.mkp.pfl.bpmn.Node> nodes = new LinkedHashMap<>();
    for (Map.Entry<String, Element> entry : nodeElements.entrySet()) {
      String id = entry.getKey();
      Element element = entry.getValue();
      NodeKind kind = kinds.get(id);
      Element extensions = firstChild(element, BPMN_NS, "extensionElements");
      String taskType = null;
      List<String> groups = List.of();
      boolean nativeUserTask = false;
      Map<String, String> properties = new LinkedHashMap<>();
      if (extensions != null) {
        Element taskDefinition = firstChild(extensions, ZEEBE_NS, "taskDefinition");
        if (taskDefinition != null) {
          taskType = taskDefinition.getAttribute("type").strip();
          if (taskType.isEmpty()) {
            taskType = null;
          }
        }
        Element assignment = firstChild(extensions, ZEEBE_NS, "assignmentDefinition");
        if (assignment != null && !assignment.getAttribute("candidateGroups").isBlank()) {
          groups =
              List.of(assignment.getAttribute("candidateGroups").split(",")).stream()
                  .map(String::strip)
                  .filter(s -> !s.isEmpty())
                  .toList();
        }
        nativeUserTask = firstChild(extensions, ZEEBE_NS, "userTask") != null;
        Element calledDecision = firstChild(extensions, ZEEBE_NS, "calledDecision");
        if (calledDecision != null && !calledDecision.getAttribute("decisionId").isBlank()) {
          properties.put("zeebe:calledDecision", calledDecision.getAttribute("decisionId").strip());
        }
        Element props = firstChild(extensions, ZEEBE_NS, "properties");
        if (props != null) {
          for (Element p : children(props)) {
            if (ZEEBE_NS.equals(p.getNamespaceURI()) && "property".equals(p.getLocalName())) {
              properties.put(p.getAttribute("name"), p.getAttribute("value"));
            }
          }
        }
      }
      EventDefinition event = null;
      if (kind == NodeKind.START_EVENT
          || kind == NodeKind.END_EVENT
          || kind == NodeKind.INTERMEDIATE_CATCH_EVENT
          || kind == NodeKind.BOUNDARY_EVENT) {
        event = readEvent(element, messages, errorCodes, findings);
      }
      String attachedTo = kind == NodeKind.BOUNDARY_EVENT ? element.getAttribute("attachedToRef") : null;
      boolean cancelActivity = !"false".equals(element.getAttribute("cancelActivity"));
      String defaultFlow = element.hasAttribute("default") ? element.getAttribute("default") : null;
      nodes.put(
          id,
          new io.github.mkp.pfl.bpmn.Node(
              id,
              element.getAttribute("name"),
              kind,
              incoming.getOrDefault(id, List.of()),
              outgoing.getOrDefault(id, List.of()),
              taskType,
              groups,
              nativeUserTask,
              event,
              attachedTo,
              cancelActivity,
              defaultFlow,
              properties,
              textOf(element, BPMN_NS, "documentation")));
    }

    return new ProcessModel(
        process.getAttribute("id"),
        process.getAttribute("name"),
        !"false".equals(process.getAttribute("isExecutable")),
        nodes,
        flows,
        findings);
  }

  private static EventDefinition readEvent(
      Element element,
      Map<String, Message> messages,
      Map<String, String> errorCodes,
      List<ProcessModel.ParseFinding> findings) {
    for (Element child : children(element)) {
      if (!BPMN_NS.equals(child.getNamespaceURI()) || !child.getLocalName().endsWith("EventDefinition")) {
        continue;
      }
      String id = element.getAttribute("id");
      switch (child.getLocalName()) {
        case "timerEventDefinition" -> {
          String duration = textOf(child, BPMN_NS, "timeDuration");
          boolean other =
              firstChild(child, BPMN_NS, "timeDate") != null
                  || firstChild(child, BPMN_NS, "timeCycle") != null;
          if (duration == null && other) {
            findings.add(
                new ProcessModel.ParseFinding(
                    "UNSUPPORTED_TIMER", id, "only timeDuration timers are modelled"));
          }
          return new EventDefinition(EventDefinition.Type.TIMER, duration, null, null, null, false);
        }
        case "messageEventDefinition" -> {
          String ref = child.getAttribute("messageRef");
          Message message = messages.get(ref);
          return new EventDefinition(
              EventDefinition.Type.MESSAGE,
              null,
              message == null ? null : message.name(),
              message == null ? null : message.correlationKey(),
              null,
              message == null);
        }
        case "errorEventDefinition" -> {
          String ref = child.getAttribute("errorRef");
          if (ref.isEmpty()) {
            return new EventDefinition(EventDefinition.Type.ERROR, null, null, null, null, false);
          }
          String code = errorCodes.get(ref);
          return new EventDefinition(EventDefinition.Type.ERROR, null, null, null, code, code == null);
        }
        default -> {
          findings.add(
              new ProcessModel.ParseFinding(
                  "UNSUPPORTED_EVENT", id, "bpmn:" + child.getLocalName() + " is not supported"));
          return new EventDefinition(EventDefinition.Type.UNSUPPORTED, null, null, null, null, false);
        }
      }
    }
    return EventDefinition.NONE;
  }

  private static Message readMessage(Element message) {
    String correlationKey = null;
    Element extensions = firstChild(message, BPMN_NS, "extensionElements");
    if (extensions != null) {
      Element subscription = firstChild(extensions, ZEEBE_NS, "subscription");
      if (subscription != null && !subscription.getAttribute("correlationKey").isBlank()) {
        correlationKey = subscription.getAttribute("correlationKey").strip();
      }
    }
    String name = message.getAttribute("name").strip();
    return new Message(name.isEmpty() ? null : name, correlationKey);
  }

  private record Message(String name, String correlationKey) {}

  private static Document read(byte[] xml) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      DocumentBuilder builder = factory.newDocumentBuilder();
      builder.setErrorHandler(null);
      return builder.parse(new ByteArrayInputStream(xml));
    } catch (ParserConfigurationException e) {
      throw new IllegalStateException("XML parser is misconfigured", e);
    } catch (SAXException | IOException e) {
      throw new BpmnParseException("not well-formed XML: " + e.getMessage(), e);
    }
  }

  static List<Element> children(Element parent) {
    List<Element> result = new ArrayList<>();
    NodeList list = parent.getChildNodes();
    for (int i = 0; i < list.getLength(); i++) {
      Node node = list.item(i);
      if (node instanceof Element element) {
        result.add(element);
      }
    }
    return result;
  }

  static Element firstChild(Element parent, String namespace, String localName) {
    for (Element child : children(parent)) {
      if (namespace.equals(child.getNamespaceURI()) && localName.equals(child.getLocalName())) {
        return child;
      }
    }
    return null;
  }

  static String textOf(Element parent, String namespace, String localName) {
    Element child = firstChild(parent, namespace, localName);
    if (child == null) {
      return null;
    }
    String text = child.getTextContent();
    return text == null || text.isBlank() ? null : text.strip();
  }
}
