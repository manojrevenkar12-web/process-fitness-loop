package io.github.mkp.pfl.conformance;

import java.util.List;

/**
 * How well a model describes recorded history.
 *
 * @param processId the model checked
 * @param cases how many cases were replayed
 * @param fitness token-replay fitness over the whole log, from 0 to 1 (1: every case fits)
 * @param perfectlyFitting share of cases that replay without a single missing or remaining token
 * @param deviations the differences, most frequent first
 */
public record ConformanceReport(
    String processId, int cases, double fitness, double perfectlyFitting, List<Deviation> deviations) {

  public ConformanceReport {
    deviations = List.copyOf(deviations);
  }
}
