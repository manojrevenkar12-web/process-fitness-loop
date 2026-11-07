# 3. A plain REST job worker, and check before act for the legacy hub

## Context
The connector runs inside a bank. Each dependency is a security review, and the Camunda 8 Orchestration Cluster REST API (`/v2`) covers jobs, user tasks, messages, deployments and history. The legacy hub does not de-duplicate recalls and sometimes accepts one and loses the answer.

## Decision
The Camunda client and the job worker use JDK HTTP and Jackson only, and echo the 8.10 job lease token. The recall client asks the hub for a recall on file under the step's client reference (derived from process instance and element) before sending one, on every attempt, so retries, job re-activations and worker restarts cannot send a second recall.

## Consequences
About 2.8 hub calls per recall instead of 1.3. In exchange, 0 of 10,000 recalls went out twice where blind retries sent 822 twice (`reports/hub-reliability.json`). The same client reference scheme works for any non-idempotent legacy call that has a lookup.
