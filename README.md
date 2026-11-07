# process-fitness-loop

From the first process file to a production workflow whose improvements are backtested before anyone approves them.

Work in progress; the full write-up follows.

<!-- results:start -->
| Measure | Result | Source |
|---|---|---|
| Backtest error on the fitness-score change, dev worlds | **0.014** (naive spreadsheet estimate: 0.173) | `reports/backtest-dev.json`, 6 worlds |
| 90% interval contains the true change, dev worlds | **93%** of proposals | `reports/backtest-dev.json` |
| Verdicts that hold against the truth, dev worlds | **30 of 30** | `reports/backtest-dev.json` |
| Recalls sent twice under hub timeouts | **0 of 10000** (blind retry: 822) | `reports/hub-reliability.json` |
<!-- results:end -->
