# Report: does the backtest tell the truth?

## Question

A ProcessOS-style improvement loop proposes changes to a running process and backtests them on historical cases before a person approves them. The question this repository answers for one process, inbound payment investigations at a bank, is how far a backtest's projection lands from what really happens when the change goes live, and whether its interval says honestly how far that may be.

## Setup

- **Worlds.** `pfl-world` builds a bank per seed with hidden parameters (`WorldParams`): volumes, inquiry mix, step durations with per-case complexity, completeness of first contact by channel, sender reply times, analysts actually at work, team-lead pickup times, and an undocumented habit of sending urgent recalls above 10,000 before the second approval (`world/as-practised.bpmn`). It runs today's process for 84 days and exports a case-tool history without the hidden attributes.
- **Proposals** (`proposals/`): add an analyst; look payments up through the hub connector; let an agent classify inquiries; raise the four-eyes limit to 25,000; the full re-engineered process.
- **Backtest** (`pfl-core/.../backtest`): paired replay with evidence tiers, team sizes read from history, four replications, 400 day-block resamples, 90% intervals. Defaults in `BacktestSettings.defaults()`.
- **Truth.** The world runs each proposal over the same inquiries with the same draws for the same steps; the true change is that run minus today's run.
- **Baseline to beat.** The naive estimate replays each case's recorded decisions through the candidate with average step times, unlimited people and no working hours.
- **Dev worlds** 1 to 6; **held-out worlds** 1009 to 1061, pinned in `reports/heldout-pin.json` before the backtester was final, run once.

## Results

Held-out, 10 worlds, 50 projections (`reports/backtest-heldout.json`):

- Mean absolute error of the projected fitness-score change: **0.029**; naive estimate: 0.165.
- 90% interval covers the truth in **78%** of projections overall, and in **19 of 20** for the two proposals the gate lets a reviewer approve.
- Verdicts that hold against the truth: **50 of 50**.

Dev, 6 worlds: error 0.014 (naive 0.173), coverage 93%, verdicts 30 of 30.

Where the held-out intervals miss:

- *Agent intake* (7 of 10 covered). The assumption that the agent decides as analysts did is declared unmeasured, with an agreement range of 88 to 100%. In some worlds the real agent was better than that range assumed. The verdict was *Shadow run first* in every world, which is the right call precisely because this number cannot be trusted yet.
- *Raise the four-eyes limit* (4 of 10). Blocked in every world by the hard limit. Its effect on the score is tiny, and the team leads' batching habit, invisible in the history, moves it outside narrow intervals.
- *Hub connector* (9 of 10) and *re-engineered* (9 of 10) miss once each, close to the boundary.

## Findings along the way

1. **Roster versus floor.** The first backtester simulated the staffing plan. In dev worlds where one of seven analysts was absent, it under-projected the gains, the hub connector's by half and an extra analyst's by four-fifths. Reading team sizes from the history (median daily peak of concurrent person steps) fixed it; the result now tells the reviewer when plan and history disagree.
2. **Decisions are assumptions too.** An agent that takes over a person's step takes over their decisions. Counting only assumed minutes, the agent proposal looked evidence-rich (under 1% assumed time). The gate now treats any unmeasured assumption as a reason for a shadow run, and the backtest varies decision agreement within its declared range.
3. **The engine is more permissive than the notation.** See the next section.

## Verifier against Camunda 8.10.9

`BpmnMutations` applies 22 operators to a model wherever they fit (missing job types, broken FEEL, conditions in odd places, detached boundaries, bad timers, merges turned into parallel joins, renames). CI deploys every mutant to a real cluster and compares the outcome with the verifier's prediction (`reports/verifier-vs-engine.json`).

The first run (`reports/verifier-vs-engine-first-run.json`) agreed on 42 of 64 dev and 60 of 87 held-out mutants. Every disagreement was the verifier refusing a file the engine deployed, in six rules. Those rules were reclassified as design or behaviour warnings; agreement is now 64 of 64 and 87 of 87. The held-out mutants come from two other models but the same operator families.

Mutants that turn an exclusive merge into a parallel join deploy, and the verifier flags them as runs that hang: the class of error a deploy-time check cannot catch.

## Engine equivalence

32 scripted cases cover every element of the re-engineered process (`Scenario`). CI runs them on Camunda 8.10.9 through the real job worker and a hub that loses a quarter of its answers, then compares each case's element-instance path with the simulator's: 32 of 32 equal, and each of the 8 recall cases reached the hub exactly once (`reports/engine-equivalence.json`).

## Reproduce

```bash
mvn -q install -DskipTests
mvn -q -pl pfl-world exec:java -Dexec.args="dev ."       # reports/backtest-dev.json, byte for byte
mvn -q -pl pfl-world exec:java -Dexec.args="heldout ."   # refuses to run if the pinned seeds changed
mvn -q -pl pfl-hub exec:java -Dexec.mainClass=io.github.mkp.pfl.hub.experiment.HubReliabilityExperiment
```

The engine reports need a cluster: see the `engine` job in `.github/workflows/ci.yml`.
