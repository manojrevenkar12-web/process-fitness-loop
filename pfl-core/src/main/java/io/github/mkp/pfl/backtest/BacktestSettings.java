package io.github.mkp.pfl.backtest;

import java.time.ZoneId;
import java.util.List;

/**
 * How a backtest runs.
 *
 * @param replications independent simulations per variant; they differ in similar-case draws and
 *     in where each assumption's median lands within its stated uncertainty
 * @param bootstrap resamples of days for the interval, spread over the replications
 * @param seed makes the result reproducible
 * @param strata case attributes that define "similar cases"
 * @param minPool fewer similar cases than this and all cases are used
 * @param confidence two-sided interval width, e.g. 0.9
 * @param assumedShareLimit above this share of simulated minutes coming from assumptions, the
 *     verdict is "run it in shadow first" whatever the interval says
 * @param zone time zone that decides which day a case arrived on, for day resampling
 */
public record BacktestSettings(
    int replications, int bootstrap, long seed, List<String> strata, int minPool, double confidence,
    double assumedShareLimit, ZoneId zone) {

  public BacktestSettings {
    strata = List.copyOf(strata);
    if (replications < 1 || bootstrap < replications) {
      throw new IllegalArgumentException("need at least one replication and one resample per replication");
    }
  }

  public static BacktestSettings defaults() {
    return new BacktestSettings(4, 400, 20260707L, List.of("inquiryType", "channel"), 25, 0.9, 0.25, ZoneId.of("Europe/Berlin"));
  }

  public BacktestSettings withSeed(long newSeed) {
    return new BacktestSettings(replications, bootstrap, newSeed, strata, minPool, confidence, assumedShareLimit, zone);
  }
}
