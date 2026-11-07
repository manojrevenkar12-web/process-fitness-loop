import { useState } from "react";
import type { BacktestReport } from "../../shared/data/types";
import { share } from "../../shared/data/format";
import { IntervalChart } from "./IntervalChart";
import { coverage } from "./scale";

const SCORE_DIGITS = 3;

export function BacktestSection({ dev, heldout }: { dev: BacktestReport | null; heldout: BacktestReport | null }) {
  const [set, setSet] = useState<"heldout" | "dev">(heldout ? "heldout" : "dev");
  const report = set === "heldout" && heldout ? heldout : dev;
  if (!report) {
    return <p className="muted">The backtest evaluation has not been run yet.</p>;
  }
  const c = coverage(report.perWorld);
  const o = report.summary.overall;
  return (
    <section aria-labelledby="backtest-title">
      <h2 id="backtest-title" className="sr-only">Backtests against the truth</h2>
      {heldout && dev && (
        <div className="toggle" role="group" aria-label="Which worlds">
          <button type="button" aria-pressed={set === "heldout"} onClick={() => setSet("heldout")}>
            Held-out worlds ({heldout.worlds.length})
          </button>
          <button type="button" aria-pressed={set === "dev"} onClick={() => setSet("dev")}>
            Development worlds ({dev.worlds.length})
          </button>
        </div>
      )}
      <p>
        Across {report.worlds.length} simulated banks, the interval contains the true change for{" "}
        <span className="strong">{c.covered} of {c.total}</span> projections ({share(o.intervalCoverage)}). The projection misses
        the truth by <span className="strong">{o.scoreErrorOurs.toFixed(SCORE_DIGITS)}</span> on average; a spreadsheet that adds up average step
        times and ignores queues misses it by <span className="strong">{o.scoreErrorNaive.toFixed(SCORE_DIGITS)}</span>.
      </p>
      <div className="legend" aria-hidden="true">
        <span><i className="swatch-bar" /> backtest interval and estimate</span>
        <span><i className="swatch-dot" /> what really happened</span>
        <span><i className="swatch-naive" /> spreadsheet estimate</span>
      </div>
      <IntervalChart key={set} worlds={report.perWorld} />
    </section>
  );
}
