# Engagement playbook: from the first process file to a process that improves itself

What I would do, in order, at a customer moving one process onto Camunda 8 with an improvement loop. Each step names its exit check and the command that runs it. Steps 1 to 4 need no production access.

## Week 1: the process as written and as lived

1. **Get the process file and the procedure document.** Whatever the customer has: a legacy BPM export, a Visio, a generated model. Run `pfl verify` on it. Fix deploy errors; read every behaviour issue aloud with the process owner, because a deadlock in a model is usually a misunderstanding in a meeting.
   *Exit:* the file deploys and every run can finish (`pfl verify` exits 0).
2. **Get twelve weeks of history.** A case-tool export with step start and end times and the variables the decisions read. Convert it to the JSON Lines format in `data/` and keep the conversion script with the engagement.
   *Exit:* every case has an arrival, its attributes, and READY/START/COMPLETE events for each step.
3. **Replay the history through both models.** `pfl conformance` against the documented process and the discovered one. Every deviation in the discovered model is a finding to bring to the process owner: skipped controls, undocumented loops, steps done twice.
   *Exit:* the process owner has seen the findings and decided which are the process and which are mistakes.
4. **Write the explanation down.** `pfl explain` on the agreed model, sent to the business lead, who signs off that the numbered steps are how the work should run.
   *Exit:* a named person signed the explanation, not the diagram.

## Week 2: what good means

5. **Agree the fitness function.** Which KPIs, what weight, which targets, and which limits no change may cross, in `config/fitness.json`. Hard limits are the controls the risk function cares about (four eyes, sanctions screening); they are never weights.
   *Exit:* the baseline score is 0 by construction; the risk officer has read the hard limits.
6. **Check the roster against the history.** The backtester reads team sizes from the history and says so when they differ from the plan. Ask why before going on; the answer changes every queueing number.

## Weeks 3 to 4: the first change

7. **Write proposals with their evidence.** One JSON file per change under `proposals/`: which history stands for each changed step, and for new work an assumption with its source and whether it was measured.
8. **Backtest.** `pfl backtest ID`. Read the evidence mix before the score.
9. **Decide through the gate.** A reviewer who did not write the proposal approves an *Improves*. A change resting on an unmeasured assumption runs in shadow first; measure the assumption there and update the proposal.
10. **Deploy and work.** Deploy the approved file through the API; start the worker with the hub connector. In-flight cases finish on the version they started.
    *Exit:* the first case completed on the new version, and its path is the one the simulator predicted for its data.

## Every week after: the loop

11. Export the new history, rerun conformance and the baseline, compare realised KPIs with the projection's interval. A realisation outside the interval is a finding about the simulator, not only about the process.
12. Add the next proposal.

## Hand-off checklist

- [ ] The customer's team ran steps 7 to 10 for one change without me.
- [ ] They know where the decision log is and how to check it (`DecisionLog.verify`).
- [ ] The fitness function and hard limits have an owner on the business side.
- [ ] The connector's error codes and retry behaviour are in their runbook.
- [ ] Every finding from step 3 is closed or has an owner.
