import type { Results } from "../../shared/data/types";
import { count } from "../../shared/data/format";

export function ReliabilitySection({ results }: { results: Results }) {
  const hub = results["hub-reliability"]?.outcomes;
  const engine = results["engine-equivalence"];
  const verifier = results["verifier-vs-engine"];
  if (!hub && !engine && !verifier) {
    return null;
  }
  return (
    <section className="section" aria-labelledby="reliability-title">
      <h2 id="reliability-title">Where it touches real systems</h2>
      <div className="facts">
        {hub && (
          <div className="fact">
            <div className="figure">{count(hub.checkBeforeAct.recalledTwiceOrMore)} of {count(hub.checkBeforeAct.recalls)}</div>
            <p className="small">
              recalls sent twice through a hub that loses answers. Retrying blindly sent {count(hub.blindRetry.recalledTwiceOrMore)} twice.
            </p>
          </div>
        )}
        {engine && (
          <div className="fact">
            <div className="figure">{engine.samePath} of {engine.cases}</div>
            <p className="small">scripted cases where the simulator took the same path as Camunda {engine.camundaVersion}.</p>
          </div>
        )}
        {verifier && (
          <div className="fact">
            <div className="figure">{verifier.heldout.agree} of {verifier.heldout.mutants}</div>
            <p className="small">held-out generated models where the verifier predicted whether Camunda would deploy them.</p>
          </div>
        )}
      </div>
    </section>
  );
}
