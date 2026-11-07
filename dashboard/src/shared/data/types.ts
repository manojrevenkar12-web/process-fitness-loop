/** Shapes of the generated reports in /reports, as the dashboard reads them. */

export type Verdict = "IMPROVES" | "UNCLEAR" | "WORSE" | "NEEDS_SHADOW_RUN" | "BLOCKED";

export interface Interval {
  estimate: number;
  low: number;
  high: number;
}

export interface KpiDelta {
  projected: Interval | Record<string, never>;
  true: number;
  naive: number;
  covered: boolean;
}

export interface ProposalOutcome {
  proposal: string;
  verdict: Verdict;
  reasons: string[];
  score: { projected: Interval; true: number; naive: number; covered: boolean };
  delta: Record<string, KpiDelta>;
  evidence: Partial<Record<"SAME_CASE" | "SIMILAR_CASES" | "ASSUMED", number>>;
  trueBreaches: string[];
  verdictHolds: boolean;
}

export interface Deviation {
  kind: "RAN_WHEN_NOT_ALLOWED" | "LEFT_UNDONE" | "NOT_IN_MODEL";
  element: string;
  cases: number;
  message: string;
}

export interface ConformanceSummary {
  fitness: number;
  perfectlyFitting: number;
  deviations: Deviation[];
}

export interface WorldResult {
  seed: number;
  cases: number;
  observed: Record<string, number>;
  conformance: { asIs: ConformanceSummary; documented: ConformanceSummary };
  proposals: ProposalOutcome[];
}

export interface BacktestSummary {
  overall: {
    scoreErrorOurs: number;
    scoreErrorNaive: number;
    intervalCoverage: number;
    verdictsThatHold: number;
    verdicts: number;
  };
  [proposal: string]: unknown;
}

export interface BacktestReport {
  set: "dev" | "heldout";
  worlds: number[];
  daysOfHistory: number;
  summary: BacktestSummary;
  perWorld: WorldResult[];
}

export interface HubOutcome {
  recalls: number;
  recalledTwiceOrMore: number;
  extraRecalls: number;
  neverRecalled: number;
  hubCallsPerRecall: number;
}

export interface Results {
  "backtest-dev"?: { worlds: number[]; summary: BacktestSummary };
  "backtest-heldout"?: { worlds: number[]; summary: BacktestSummary };
  exampleWorld?: WorldResult;
  "hub-reliability"?: { outcomes: { checkBeforeAct: HubOutcome; blindRetry: HubOutcome } };
  "engine-equivalence"?: { camundaVersion: string; cases: number; samePath: number; recallCases: number; recallsOnHub: number };
  "verifier-vs-engine"?: Record<"dev" | "heldout", { mutants: number; agree: number }> & { camundaVersion: string };
}

export interface Reports {
  results: Results;
  dev: BacktestReport | null;
  heldout: BacktestReport | null;
}
