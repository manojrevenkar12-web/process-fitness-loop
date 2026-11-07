import { describe, expect, it } from "vitest";
import { coverage, groupByProposal, scoreScale } from "./scale";
import type { WorldResult } from "../../shared/data/types";

const outcome = (proposal: string, est: number, truth: number, covered = true) => ({
  proposal,
  verdict: "IMPROVES" as const,
  reasons: [],
  score: { projected: { estimate: est, low: est - 0.02, high: est + 0.02 }, true: truth, naive: est / 2, covered },
  delta: {},
  evidence: {},
  trueBreaches: [],
  verdictHolds: true,
});

const worlds: WorldResult[] = [
  { seed: 1, cases: 10, observed: {}, conformance: {} as never, proposals: [outcome("a", 0.25, 0.25), outcome("b", 0.8, 0.9, false)] },
  { seed: 2, cases: 10, observed: {}, conformance: {} as never, proposals: [outcome("a", 0.3, 0.31), outcome("b", Number.NaN, 0.1)] },
];

describe("scoreScale", () => {
  it("always shows zero and lands ticks on round numbers", () => {
    const s = scoreScale([0.1, 0.92], 0, 500, 0.05);
    expect(s.min).toBeLessThanOrEqual(0);
    expect(s.max).toBeGreaterThanOrEqual(0.97);
    expect(s.ticks).toContain(0);
    expect(s.x(s.min)).toBe(0);
    expect(s.x(s.max)).toBe(500);
  });
});

describe("grouping and coverage", () => {
  it("groups rows by proposal and skips projections that were refused", () => {
    const groups = groupByProposal(worlds);
    expect(groups.map((g) => g.proposal)).toEqual(["a", "b"]);
    expect(groups[1].rows).toHaveLength(1);
  });
  it("counts how many intervals contain the truth", () => {
    expect(coverage(worlds)).toEqual({ covered: 2, total: 3 });
  });
});
