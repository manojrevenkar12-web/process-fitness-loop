# 1. Backtest by paired replay of recorded cases, not by forecasting

## Context
A proposal must come with an expected effect a reviewer can trust. A forecast from a fitted model (regression on KPIs, a queueing formula) hides which part of the answer is evidence. A spreadsheet adds up average step times and has no queues, so it cannot see that automating one team's routine work shortens every other case's wait.

## Decision
Replay the recorded cases through both today's process and the candidate in a discrete-event simulation with shared teams and working hours. Draw each step from the case's own history, then from similar cases, then from a declared assumption with its source. Use the same keyed random draws in both runs, and report the change case by case (candidate minus replay), so the simulator's own error cancels instead of appearing as gain. Resample whole days for the interval, and vary each assumption within its stated uncertainty across replications.

## Consequences
Every projected number can be traced to a tier of evidence, and the result says how much came from assumptions. The replay of today's process is a calibration the reviewer can see. A change to work nobody has done yet cannot be projected well; that is reported, and the gate asks for a shadow run instead of pretending.
