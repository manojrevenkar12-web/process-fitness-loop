package io.github.mkp.pfl.world;

import io.github.mkp.pfl.bpmn.BpmnParser;
import io.github.mkp.pfl.bpmn.Node;
import io.github.mkp.pfl.bpmn.ProcessModel;
import io.github.mkp.pfl.log.CaseRecord;
import io.github.mkp.pfl.log.EventLog;
import io.github.mkp.pfl.sim.Behaviour;
import io.github.mkp.pfl.sim.CaseSeed;
import io.github.mkp.pfl.sim.SimResult;
import io.github.mkp.pfl.sim.Simulator;
import io.github.mkp.pfl.sim.Staffing;
import io.github.mkp.pfl.sim.StepContext;
import io.github.mkp.pfl.util.Draws;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * One simulated bank. It runs the payment-investigation process the way the bank really works and
 * exports the history a case tool would record; it can also run any proposed process the way the
 * bank would really experience it. The second answer is what the backtester is scored against.
 */
public final class World {

  /** Attributes that drive the truth and never leave the world. */
  public static final Set<String> HIDDEN = Set.of("complexity", "skipApproval");

  private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

  private final WorldParams params;
  private final Staffing plannedStaffing;

  public World(WorldParams params, Staffing plannedStaffing) {
    this.params = params;
    this.plannedStaffing = plannedStaffing;
  }

  public WorldParams params() {
    return params;
  }

  /** Inquiries arriving over the given days, with their hidden attributes. */
  public List<CaseSeed> arrivals(LocalDate firstDay, int days) {
    List<CaseSeed> seeds = new ArrayList<>();
    SplittableRandom r = Draws.rng(params.seed(), "arrivals");
    int n = 0;
    for (int d = 0; d < days; d++) {
      LocalDate day = firstDay.plusDays(d);
      boolean weekend = day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
      double mean = weekend ? params.weekendCases() : params.weekdayCases();
      if (day.getDayOfWeek() == DayOfWeek.MONDAY) {
        mean *= 1.3;
      }
      int count = poisson(r, mean);
      for (int i = 0; i < count; i++) {
        // arrivals cluster in office hours of the sending banks, 06:00-20:00
        double hour = weekend ? 24 * r.nextDouble() : 6 + 14 * Math.pow(r.nextDouble(), 1.15);
        Instant at = day.atStartOfDay(ZONE).plusSeconds(Math.round(hour * 3600)).toInstant();
        String caseId = String.format("INV-%d-%05d", params.seed(), ++n);
        seeds.add(new CaseSeed(caseId, at, attributes(caseId)));
      }
    }
    seeds.sort(java.util.Comparator.comparing(CaseSeed::arrival));
    return seeds;
  }

  private Map<String, Object> attributes(String caseId) {
    SplittableRandom r = Draws.rng(params.seed(), "case", caseId);
    double u = r.nextDouble();
    String type = u < params.typeMix().get("RECALL") ? "RECALL"
        : u < params.typeMix().get("RECALL") + params.typeMix().get("NON_RECEIPT") ? "NON_RECEIPT" : "QUERY";
    double amount = Math.exp(Math.log(3_500) + 1.25 * Draws.normal(r));
    String channel = r.nextDouble() < params.emailShare() ? "EMAIL" : "SWIFT";
    String[] currencies = {"EUR", "EUR", "EUR", "USD", "GBP", "CHF"};
    double complexity = Math.exp(params.complexitySigma() * Draws.normal(r));
    boolean urgent = r.nextDouble() < params.urgentShare();
    boolean skip = urgent && r.nextDouble() < params.skipWhenUrgent();
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("inquiryType", type);
    a.put("amount", BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP));
    a.put("currency", currencies[r.nextInt(currencies.length)]);
    a.put("channel", channel);
    a.put("complexity", complexity);
    a.put("skipApproval", skip);
    return a;
  }

  /** The staffing the bank really has: the plan, minus absences, with the team leads' habits. */
  public Staffing staffing(Map<String, Integer> teamOverrides) {
    Map<String, Integer> teams = new HashMap<>(plannedStaffing.teams());
    teams.putAll(teamOverrides);
    int missing = plannedStaffing.teams().get("payments-ops") - params.opsAvailable();
    teams.put("payments-ops", Math.max(1, teams.get("payments-ops") - missing));
    return new Staffing(teams, plannedStaffing.calendar(), Map.of("team-leads", params.leadPickups()));
  }

  /** The process as the bank really runs it, shortcut included. */
  public static ProcessModel practisedModel() {
    try (InputStream in = World.class.getResourceAsStream("/world/as-practised.bpmn")) {
      return new BpmnParser().parse(in.readAllBytes());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Runs a process the way the bank would really experience it. */
  public SimResult run(ProcessModel model, List<CaseSeed> seeds, Map<String, Integer> teamOverrides) {
    Instant last = seeds.get(seeds.size() - 1).arrival();
    return new Simulator(model, staffing(teamOverrides)).run(seeds, new Truth(model), last.plus(Duration.ofDays(60)));
  }

  /** What the case tool would have recorded: no hidden attributes. */
  public static EventLog export(SimResult result) {
    List<CaseRecord> cases = new ArrayList<>();
    for (var c : result.cases()) {
      CaseRecord r = c.record();
      Map<String, Object> visible = new LinkedHashMap<>(r.attributes());
      HIDDEN.forEach(visible::remove);
      cases.add(new CaseRecord(r.caseId(), r.arrival(), visible, r.events()));
    }
    return new EventLog("payment-investigation", cases);
  }

  /** The hidden truth of how long things take and what they produce. */
  private final class Truth implements Behaviour {
    private final ProcessModel model;

    Truth(ProcessModel model) {
      this.model = model;
    }

    private String key(StepContext step) {
      Node node = model.node(step.nodeId());
      return step.nodeId() + "/" + node.actor();
    }

    @Override
    public Duration serviceTime(StepContext step) {
      String key = key(step);
      Double median = params.minutes().get(key);
      if (median == null) {
        throw new IllegalStateException("the world has no truth for " + key);
      }
      double complexity = ((Number) step.attributes().get("complexity")).doubleValue();
      boolean person = key.endsWith("/PERSON");
      double m = median * (person ? complexity : 1.0);
      if (key.equals("register_inquiry/PERSON") && "EMAIL".equals(step.attributes().get("channel"))) {
        m *= 1.7;
      }
      if (step.occurrence() > 1 && person) {
        m *= 0.6;
      }
      // the same random draw for the same step of the same case, whichever process variant runs it
      SplittableRandom r = Draws.rng(params.seed(), "service", step.caseId(), step.nodeId(), Integer.toString(step.occurrence()));
      double minutes = Draws.lognormal(r, m, m * params.spread().get(key));
      return Duration.ofMillis(Math.round(minutes * 60_000));
    }

    @Override
    public Map<String, Object> outputs(StepContext step) {
      String node = step.nodeId();
      if (node.equals("register_inquiry") || node.equals("classify_inquiry")) {
        boolean complete = trulyComplete(step);
        if (node.equals("classify_inquiry")) {
          SplittableRandom r = Draws.rng(params.seed(), "agent", step.caseId(), Integer.toString(step.occurrence()));
          double u = r.nextDouble();
          if (complete && u < params.agentFalseIncomplete()) {
            complete = false;
          } else if (!complete && u < params.agentFalseComplete()) {
            complete = true;
          }
        }
        return Map.of("infoComplete", complete);
      }
      if (node.equals("gpi_trace")) {
        SplittableRandom r = Draws.rng(params.seed(), "gpi", step.caseId());
        return Map.of("gpiStatus", r.nextDouble() < params.creditedRate() ? "CREDITED" : "PENDING");
      }
      return Map.of();
    }

    /** Whether the inquiry really carries all details at this contact. */
    private boolean trulyComplete(StepContext step) {
      SplittableRandom r = Draws.rng(params.seed(), "complete", step.caseId(), Integer.toString(step.occurrence()));
      double p = step.occurrence() == 1 ? params.completeFirstTime().get(String.valueOf(step.attributes().get("channel"))) : 0.93;
      return r.nextDouble() < p;
    }

    @Override
    public Duration messageDelay(StepContext step) {
      SplittableRandom r = Draws.rng(params.seed(), "reply", step.caseId(), Integer.toString(step.occurrence()));
      double hours = Draws.lognormal(r, params.replyMedianHours(), params.replyMedianHours() * 3.5);
      return Duration.ofMillis(Math.round(hours * 3_600_000));
    }

    @Override
    public String error(StepContext step) {
      if (step.nodeId().equals("hub_lookup")) {
        SplittableRandom r = Draws.rng(params.seed(), "hub", step.caseId());
        return r.nextDouble() < params.notFoundRate() ? "PAYMENT_NOT_FOUND" : null;
      }
      return null;
    }
  }

  private static int poisson(SplittableRandom r, double mean) {
    double l = Math.exp(-mean);
    int k = 0;
    double p = 1;
    do {
      k++;
      p *= r.nextDouble();
    } while (p > l);
    return k - 1;
  }

  static ZonedDateTime at(Instant t) {
    return t.atZone(ZONE);
  }
}
