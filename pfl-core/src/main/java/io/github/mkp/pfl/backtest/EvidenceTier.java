package io.github.mkp.pfl.backtest;

/** Where a simulated number came from, from strongest to weakest. */
public enum EvidenceTier {
  /** This case's own history of the same work. */
  SAME_CASE,
  /** Other cases of the same kind that did the same work. */
  SIMILAR_CASES,
  /** A declared assumption: no history exists for this work. */
  ASSUMED
}
