import type { ProposalOutcome, WorldResult } from "../../shared/data/types";
import { sentence, signed, title, verdictWords } from "../../shared/data/format";
import { EvidenceBar } from "./EvidenceBar";

const NEXT: Record<ProposalOutcome["verdict"], string> = {
  IMPROVES: "A reviewer who did not write it may approve it; the approval binds this exact model file and this result.",
  NEEDS_SHADOW_RUN: "It may only run in shadow next to today's process until the assumption is measured.",
  BLOCKED: "Nobody can approve it.",
  UNCLEAR: "Nothing to approve: the evidence does not show a gain.",
  WORSE: "Rejected.",
};

const HOURS_DIGITS = 1;

export function ProposalsSection({ world }: { world: WorldResult }) {
  return (
    <section className="section" aria-labelledby="proposals-title">
      <h2 id="proposals-title">What the gate decided</h2>
      <p className="muted">
        Five proposed changes, each backtested on world {world.seed}'s history before anyone looked at it. The service, not a person and not a
        model, enforces what may happen next.
      </p>
      {world.proposals.length === 0 && <p className="muted">No proposals in this report.</p>}
      {world.proposals.map((p) => {
        const cycle = p.delta["cycle_p90_hours"];
        const cycleText = cycle && "estimate" in cycle.projected
          ? `90th-percentile time to resolve ${signed(cycle.projected.estimate, HOURS_DIGITS)} hours`
          : null;
        return (
          <article className="proposal" key={p.proposal}>
            <div>
              <div className="proposal-head">
                <h3>{title(p.proposal)}</h3>
                <span className={`verdict verdict-${p.verdict}`}>{verdictWords[p.verdict]}</span>
              </div>
              <p className="small reason">{sentence(p.reasons[0] ?? "")}</p>
              <p className="small muted">{NEXT[p.verdict]}</p>
            </div>
            <div>
              {Number.isFinite(p.score.projected.estimate) && (
                <p className="small">
                  Score {signed(p.score.projected.estimate)} projected, {signed(p.score.true)} real.
                  {cycleText ? ` ${cycleText}.` : ""}
                </p>
              )}
              <EvidenceBar evidence={p.evidence} />
            </div>
          </article>
        );
      })}
    </section>
  );
}
