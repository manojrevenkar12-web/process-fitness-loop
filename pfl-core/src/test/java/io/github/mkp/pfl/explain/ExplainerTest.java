package io.github.mkp.pfl.explain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mkp.pfl.bpmn.BpmnParser;
import io.github.mkp.pfl.bpmn.ProcessModel;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ExplainerTest {

  private static ProcessModel toBe() throws Exception {
    return new BpmnParser().parse(Path.of("../processes/payment-investigation-to-be.bpmn"));
  }

  @Test
  void explainsEveryElementOnceInReadingOrder() throws Exception {
    ProcessModel model = toBe();
    Explanation explanation = new Explainer().explain(model);
    long boundaries = model.nodes().values().stream().filter(n -> n.attachedTo() != null).count();
    assertEquals(model.nodes().size() - boundaries, explanation.steps().size());
    assertEquals("inquiry_received", explanation.steps().get(0).elementId());
    for (int i = 0; i < explanation.steps().size(); i++) {
      assertEquals(i + 1, explanation.steps().get(i).number());
    }
  }

  @Test
  void usesBusinessWordsNotBpmnWords() throws Exception {
    String md = new Explainer().explain(toBe()).toMarkdown();
    assertTrue(md.contains("if amount is more than 10000, go to step"), md);
    assertTrue(md.contains("Done by a person from team-leads"), md);
    assertTrue(md.contains("Done by an AI agent (pfl.intake-agent); its output is checked by rules"), md);
    assertTrue(md.contains("If it reports 'PAYMENT_NOT_FOUND', the step is stopped and the case goes to step"), md);
    assertTrue(md.contains("matched to this case by case id"), md);
    for (String jargon : new String[] {"gateway", "token", "sequence flow", "BPMN"}) {
      assertTrue(!md.toLowerCase().contains(jargon.toLowerCase()), "jargon '" + jargon + "' in: " + md);
    }
  }

  @Test
  void sameFileAlwaysGivesTheSameWords() throws Exception {
    assertEquals(new Explainer().explain(toBe()).toMarkdown(), new Explainer().explain(toBe()).toMarkdown());
  }

  @Test
  void summaryCountsWhoDoesTheWork() throws Exception {
    String summary = new Explainer().explain(toBe()).summary();
    assertTrue(summary.contains("10 steps: 4 done by people, 1 by AI agents and 5 by systems or rule tables. It makes 4 decisions"), summary);
  }

  @Test
  void durationsReadNaturally() {
    assertEquals("4 hours", Explainer.durationInWords("PT4H"));
    assertEquals("1 day and 12 hours", Explainer.durationInWords("P1DT12H"));
    assertEquals("2 weeks, 3 days and 30 minutes", Explainer.durationInWords("P2W3DT30M"));
  }
}
