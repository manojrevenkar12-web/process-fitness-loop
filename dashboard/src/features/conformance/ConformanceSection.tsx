import type { WorldResult } from "../../shared/data/types";
import { count, share } from "../../shared/data/format";

const SHOWN = 3;

export function ConformanceSection({ world }: { world: WorldResult }) {
  const { asIs, documented } = world.conformance;
  const skipped = asIs.deviations.find((d) => d.kind === "RAN_WHEN_NOT_ALLOWED");
  return (
    <section className="section" aria-labelledby="conformance-title">
      <h2 id="conformance-title">How the work really runs</h2>
      <p className="muted">
        Twelve weeks of case history from world {world.seed}, {count(world.cases)} inquiries, replayed through two models of the process. The
        replay reads each case's data at every decision, so a skipped control shows up even when the steps look normal.
      </p>
      <div className="compare">
        <div>
          <h3>The procedure document</h3>
          <div className="meter" aria-hidden="true"><div style={{ width: share(documented.perfectlyFitting) }} /></div>
          <p className="small">
            Describes <span className="strong">{share(documented.perfectlyFitting, 1)}</span> of cases exactly. It has no step for asking the sender for missing
            details, which happens in the rest.
          </p>
        </div>
        <div>
          <h3>The model rebuilt from history</h3>
          <div className="meter" aria-hidden="true"><div style={{ width: share(asIs.perfectlyFitting) }} /></div>
          <p className="small">
            Describes <span className="strong">{share(asIs.perfectlyFitting, 1)}</span> of cases exactly. What it does not describe is a finding, not noise:
          </p>
        </div>
      </div>
      <ul className="deviations">
        {skipped && <li><span className="strong">Four-eyes control. </span>{skipped.message}</li>}
        {documented.deviations.filter((d) => d.kind === "NOT_IN_MODEL").slice(0, SHOWN).map((d) => (
          <li key={d.element}>{d.message}</li>
        ))}
      </ul>
    </section>
  );
}
