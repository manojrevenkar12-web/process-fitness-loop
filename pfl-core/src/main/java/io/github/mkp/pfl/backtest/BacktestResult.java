package io.github.mkp.pfl.backtest;

import java.util.List;
import java.util.Map;

/**
 * What a backtest found: the projected effect of a proposal with its interval, how much of it is
 * evidence and how much assumption, and what the reviewer should do.
 *
 * @param proposal proposal id
 * @param title proposal title
 * @param verdict what to do
 * @param reasons why, in plain words
 * @param cases how many historical cases were replayed
 * @param observed KPI values in the history
 * @param replayed KPI values when today's process is re-simulated over the same cases; the
 *     difference to observed is the simulator's error before any change
 * @param delta per KPI: projected change with its interval (candidate minus replay, paired)
 * @param projected per KPI: observed plus the projected change; hard-limit KPIs are the
 *     candidate's simulated values
 * @param score projected change of the fitness score, with its interval
 * @param probabilityOfGain share of resamples in which the score improves
 * @param evidence share of simulated minutes by evidence tier
 * @param assumptions the assumptions used, with their sources
 * @param breaches hard limits the candidate crosses
 * @param incidents simulated cases that got stuck in the candidate
 * @param notes what the backtest learned about the history that a reviewer should know, e.g. that
 *     fewer people work than the roster lists
 */
public record BacktestResult(
    String proposal, String title, Verdict verdict, List<String> reasons, int cases,
    Map<String, Double> observed, Map<String, Double> replayed, Map<String, Interval> delta,
    Map<String, Double> projected, Interval score, double probabilityOfGain, Map<EvidenceTier, Double> evidence,
    List<String> assumptions, List<String> breaches, long incidents, List<String> notes) {

  /** What a reviewer should do with a proposal. */
  public enum Verdict {
    /** The interval of the score change lies above zero and the evidence is mostly history. */
    IMPROVES,
    /** The interval includes zero: no clear gain. */
    UNCLEAR,
    /** The interval lies below zero. */
    WORSE,
    /** Too much of the projection is assumption: run it in shadow on live cases first. */
    NEEDS_SHADOW_RUN,
    /** It crosses a hard limit, does not verify, or gets cases stuck. */
    BLOCKED
  }

  /** A point estimate with a two-sided interval. */
  public record Interval(double estimate, double low, double high) {
    public boolean excludesZero() {
      return low > 0 || high < 0;
    }
  }
}
