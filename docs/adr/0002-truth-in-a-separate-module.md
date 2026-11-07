# 2. Score the backtester against a hidden world it cannot import

## Context
A backtester that is only checked against its own history can be wrong in ways nobody sees, and one tuned on a single dataset will look better than it is.

## Decision
`pfl-world` simulates banks with hidden parameters and exports only what a case tool would record. It runs each proposal for real over the same inquiries. Nothing that ships depends on it; a test scans the other modules' sources and poms. Development happens on six dev worlds; ten held-out worlds were pinned by hash before the backtester was final and run once.

## Consequences
The published numbers include the held-out ones, which are worse than dev, as they should be. Changing the held-out seeds needs a new pin and a note in REPORT.md.
