# Working on process-fitness-loop

## Fast feedback

- `mvn -q verify` runs every unit test (about 20 s). `mvn -q -pl pfl-core test -Dtest=BacktesterTest` for one class.
- `cd dashboard && npm run lint -- --max-warnings 0 && npm test && npm run build`.
- Engine tests (`@Tag("engine")`) need a cluster: `-Pengine` with `CAMUNDA_URL` set; CI starts one.

## Invariants

1. **The truth stays in `pfl-world`.** Nothing in `pfl-core`, `pfl-hub` or `pfl-service` may import it or depend on it; `WorldTest` checks.
2. **Every simulated number has a source.** A candidate step without history or a declared assumption is refused (`EvidencePlan`). Do not add defaults that hide this.
3. **Hard limits block.** Constraints in `config/fitness.json` are never turned into weights.
4. **People decide.** The rules in `Engagement` (no self-approval, approval binds model and evidence hashes, deploy only what was approved) are not bypassed by any endpoint, test helper or script.
5. **Settings in config.** KPI weights, targets, limits, staffing and world seeds live in `config/`.
6. **Generated reports.** Never edit `reports/*.json` or the README results block by hand; run the generators and `pfl report`. CI fails if the dev-world reports differ from what the code produces, so keep them deterministic: `StrictMath`, keyed draws from `Draws`, sorted JSON keys.
7. **Verifier categories follow the engine.** A rule is a DEPLOY error only if Camunda refuses the file; the engine job checks every mutant.

## The held-out rule

The held-out worlds in `config/worlds.json` are pinned in `reports/heldout-pin.json`. Develop on the dev worlds only. Run the held-out set once, after a change is final, and report what it says, including when it is worse.
