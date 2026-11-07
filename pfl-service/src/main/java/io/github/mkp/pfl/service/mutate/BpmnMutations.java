package io.github.mkp.pfl.service.mutate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Makes the mistakes generated models make, one at a time, so the verifier's predictions can be
 * checked against what a real cluster does with each file. Every operator is applied at every
 * place it fits, which turns one model into dozens of variants.
 */
public final class BpmnMutations {

  static final String BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL";
  static final String ZEEBE = "http://camunda.org/schema/zeebe/1.0";

  /**
   * One variant.
   *
   * @param name operator and the element it hit
   * @param operator the operator
   * @param bpmn the mutated file
   */
  public record Mutant(String name, String operator, byte[] bpmn) {}

  private BpmnMutations() {}

  public static List<Mutant> all(byte[] original) {
    List<Mutant> out = new ArrayList<>();
    Document doc = parse(original);

    forEach(doc, "serviceTask", e -> out.add(mutant(original, "drop-job-type", id(e), d ->
        remove(byId(d, id(e)), ZEEBE, "taskDefinition"))));
    forEach(doc, "sequenceFlow", f -> {
      Element gw = byId(doc, f.getAttribute("sourceRef"));
      boolean conditional = first(f, BPMN, "conditionExpression") != null;
      if (conditional && gw != null) {
        out.add(mutant(original, "drop-condition", id(f), d -> remove(byId(d, id(f)), BPMN, "conditionExpression")));
        out.add(mutant(original, "broken-feel", id(f), d -> first(byId(d, id(f)), BPMN, "conditionExpression").setTextContent("=amount >")));
        out.add(mutant(original, "condition-without-equals", id(f), d -> {
          Element c = first(byId(d, id(f)), BPMN, "conditionExpression");
          c.setTextContent(c.getTextContent().strip().substring(1));
        }));
        out.add(mutant(original, "unbalanced-paren", id(f), d -> {
          Element c = first(byId(d, id(f)), BPMN, "conditionExpression");
          c.setTextContent("=(" + c.getTextContent().strip().substring(1));
        }));
      }
      if (!conditional && gw != null && "serviceTask".equals(gw.getLocalName())) {
        out.add(mutant(original, "condition-outside-gateway", id(f), d -> {
          Element c = d.createElementNS(BPMN, "bpmn:conditionExpression");
          c.setTextContent("=true");
          byId(d, id(f)).appendChild(c);
        }));
      }
    });
    forEach(doc, "exclusiveGateway", g -> {
      if (g.hasAttribute("default")) {
        out.add(mutant(original, "default-not-outgoing", id(g), d -> byId(d, id(g)).setAttribute("default", "f_start")));
        out.add(mutant(original, "condition-on-default", id(g), d -> {
          Element c = d.createElementNS(BPMN, "bpmn:conditionExpression");
          c.setTextContent("=true");
          byId(d, g.getAttribute("default")).appendChild(c);
        }));
        out.add(mutant(original, "drop-default", id(g), d -> byId(d, id(g)).removeAttribute("default")));
      }
      if (outgoing(doc, id(g)) == 1 && incoming(doc, id(g)) > 1) {
        out.add(mutant(original, "merge-becomes-parallel-join", id(g), d -> rename(d, byId(d, id(g)), "parallelGateway")));
      }
      if (outgoing(doc, id(g)) > 1) {
        out.add(mutant(original, "split-becomes-parallel", id(g), d -> {
          Element e = rename(d, byId(d, id(g)), "parallelGateway");
          e.removeAttribute("default");
        }));
      }
    });
    forEach(doc, "message", m -> out.add(mutant(original, "drop-correlation-key", id(m), d ->
        remove(byId(d, id(m)), BPMN, "extensionElements"))));
    forEach(doc, "boundaryEvent", b -> {
      out.add(mutant(original, "boundary-detached", id(b), d -> byId(d, id(b)).setAttribute("attachedToRef", "no_such_task")));
      out.add(mutant(original, "error-ref-missing", id(b), d -> {
        Element def = first(byId(d, id(b)), BPMN, "errorEventDefinition");
        if (def != null) {
          def.setAttribute("errorRef", "no_such_error");
        }
      }));
    });
    forEach(doc, "startEvent", s -> out.add(mutant(original, "start-has-incoming", id(s), d ->
        flow(d, "f_into_start", lastTask(d), id(s)))));
    forEach(doc, "endEvent", e -> out.add(mutant(original, "end-has-outgoing", id(e), d ->
        flow(d, "f_out_of_end", id(e), lastTask(d)))));
    out.add(mutant(original, "dangling-flow", "f_start", d -> byId(d, "f_start").setAttribute("targetRef", "nowhere")));
    out.add(mutant(original, "duplicate-id", "f_start", d -> flow(d, "f_start", lastTask(d), lastTask(d))));
    out.add(mutant(original, "intermediate-timer-bad-duration", "wait", d -> insertTimer(d, "PT4 hours")));
    out.add(mutant(original, "intermediate-timer-ok", "wait", d -> insertTimer(d, "PT4H")));
    out.add(mutant(original, "rename-everything", "names", d -> forEach(d, "*", e -> {
      if (e.hasAttribute("name")) {
        e.setAttribute("name", e.getAttribute("name") + " (renamed)");
      }
    })));
    out.add(mutant(original, "add-documentation", "process", d -> {
      Element process = (Element) d.getElementsByTagNameNS(BPMN, "process").item(0);
      Element doc2 = d.createElementNS(BPMN, "bpmn:documentation");
      doc2.setTextContent("Reviewed by the process owner.");
      process.insertBefore(doc2, process.getFirstChild());
    }));
    return out;
  }

  // ------------------------------------------------------------------ helpers

  private static Mutant mutant(byte[] original, String operator, String target, Consumer<Document> change) {
    Document d = parse(original);
    change.accept(d);
    return new Mutant(operator + ":" + target, operator, serialize(d));
  }

  private static void insertTimer(Document d, String duration) {
    Element process = (Element) d.getElementsByTagNameNS(BPMN, "process").item(0);
    Element flow = byId(d, "f_start");
    String target = flow.getAttribute("targetRef");
    Element timer = d.createElementNS(BPMN, "bpmn:intermediateCatchEvent");
    timer.setAttribute("id", "wait_a_little");
    Element def = d.createElementNS(BPMN, "bpmn:timerEventDefinition");
    Element dur = d.createElementNS(BPMN, "bpmn:timeDuration");
    dur.setTextContent(duration);
    def.appendChild(dur);
    timer.appendChild(def);
    process.appendChild(timer);
    flow.setAttribute("targetRef", "wait_a_little");
    flow(d, "f_after_wait", "wait_a_little", target);
  }

  private static void flow(Document d, String id, String source, String target) {
    Element process = (Element) d.getElementsByTagNameNS(BPMN, "process").item(0);
    Element f = d.createElementNS(BPMN, "bpmn:sequenceFlow");
    f.setAttribute("id", id);
    f.setAttribute("sourceRef", source);
    f.setAttribute("targetRef", target);
    process.appendChild(f);
  }

  private static String lastTask(Document d) {
    NodeList tasks = d.getElementsByTagNameNS(BPMN, "serviceTask");
    if (tasks.getLength() == 0) {
      tasks = d.getElementsByTagNameNS(BPMN, "userTask");
    }
    return ((Element) tasks.item(tasks.getLength() - 1)).getAttribute("id");
  }

  private static Element rename(Document d, Element e, String localName) {
    Element replacement = d.createElementNS(BPMN, "bpmn:" + localName);
    for (int i = 0; i < e.getAttributes().getLength(); i++) {
      replacement.setAttribute(e.getAttributes().item(i).getNodeName(), e.getAttributes().item(i).getNodeValue());
    }
    while (e.getFirstChild() != null) {
      replacement.appendChild(e.getFirstChild());
    }
    e.getParentNode().replaceChild(replacement, e);
    return replacement;
  }

  private static int outgoing(Document d, String id) {
    return count(d, "sourceRef", id);
  }

  private static int incoming(Document d, String id) {
    return count(d, "targetRef", id);
  }

  private static int count(Document d, String attr, String id) {
    NodeList flows = d.getElementsByTagNameNS(BPMN, "sequenceFlow");
    int n = 0;
    for (int i = 0; i < flows.getLength(); i++) {
      if (((Element) flows.item(i)).getAttribute(attr).equals(id)) {
        n++;
      }
    }
    return n;
  }

  private static void remove(Element parent, String ns, String localName) {
    if (parent == null) {
      return;
    }
    NodeList list = parent.getElementsByTagNameNS(ns, localName);
    for (int i = list.getLength() - 1; i >= 0; i--) {
      list.item(i).getParentNode().removeChild(list.item(i));
    }
  }

  private static Element first(Element parent, String ns, String localName) {
    NodeList list = parent.getElementsByTagNameNS(ns, localName);
    return list.getLength() == 0 ? null : (Element) list.item(0);
  }

  private static void forEach(Document d, String localName, Consumer<Element> action) {
    NodeList list = d.getElementsByTagNameNS(BPMN, localName);
    List<Element> copy = new ArrayList<>();
    for (int i = 0; i < list.getLength(); i++) {
      copy.add((Element) list.item(i));
    }
    copy.forEach(action);
  }

  private static Element byId(Document d, String id) {
    NodeList all = d.getElementsByTagNameNS("*", "*");
    for (int i = 0; i < all.getLength(); i++) {
      Element e = (Element) all.item(i);
      if (id.equals(e.getAttribute("id"))) {
        return e;
      }
    }
    return null;
  }

  private static String id(Element e) {
    return e.getAttribute("id");
  }

  private static Document parse(byte[] xml) {
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    } catch (Exception e) {
      throw new IllegalArgumentException("cannot parse BPMN for mutation", e);
    }
  }

  private static byte[] serialize(Document d) {
    try {
      var t = TransformerFactory.newInstance().newTransformer();
      t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      t.transform(new DOMSource(d), new StreamResult(out));
      return out.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
