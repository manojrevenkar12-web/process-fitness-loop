import type { ProposalOutcome, WorldResult } from "../../shared/data/types";
import { niceSteps as NICE_STEPS } from "../../shared/theme/tokens";

export interface Scale {
  min: number;
  max: number;
  x: (value: number) => number;
  ticks: number[];
}

const TARGET_TICKS = 6;

/** A linear scale over every interval, truth and naive estimate, padded and with round ticks. */
export function scoreScale(values: number[], pixelStart: number, pixelEnd: number, pad: number): Scale {
  const finite = values.filter(Number.isFinite);
  const lo = Math.min(0, ...finite) - pad;
  const hi = Math.max(0, ...finite) + pad;
  const step = NICE_STEPS.find((s) => (hi - lo) / s <= TARGET_TICKS) ?? NICE_STEPS[NICE_STEPS.length - 1];
  const min = Math.floor(lo / step) * step;
  const max = Math.ceil(hi / step) * step;
  const ticks: number[] = [];
  for (let t = min; t <= max + step / 2; t += step) {
    ticks.push(Math.round(t / step) * step);
  }
  const x = (value: number) => pixelStart + ((value - min) / (max - min)) * (pixelEnd - pixelStart);
  return { min, max, x, ticks };
}

export interface Row {
  world: number;
  outcome: ProposalOutcome;
}

/** Rows grouped by proposal, in the order the proposals appear, skipping ones without a projection. */
export function groupByProposal(worlds: WorldResult[]): Array<{ proposal: string; rows: Row[] }> {
  const groups = new Map<string, Row[]>();
  for (const w of worlds) {
    for (const o of w.proposals) {
      if (!Number.isFinite(o.score.projected.estimate)) {
        continue;
      }
      const rows = groups.get(o.proposal) ?? [];
      rows.push({ world: w.seed, outcome: o });
      groups.set(o.proposal, rows);
    }
  }
  return [...groups.entries()].map(([proposal, rows]) => ({ proposal, rows }));
}

export function allValues(worlds: WorldResult[]): number[] {
  return worlds.flatMap((w) =>
    w.proposals.flatMap((o) => [o.score.projected.low, o.score.projected.high, o.score.true, o.score.naive]),
  );
}

/** How many projections' intervals contain the truth. */
export function coverage(worlds: WorldResult[]): { covered: number; total: number } {
  const outcomes = worlds.flatMap((w) => w.proposals).filter((o) => Number.isFinite(o.score.projected.estimate));
  return { covered: outcomes.filter((o) => o.score.covered).length, total: outcomes.length };
}
