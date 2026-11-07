import { motion, useReducedMotion } from "framer-motion";
import { useReports } from "./shared/data/useReports";
import { Empty, LoadError, Loading } from "./shared/ui/States";
import { BacktestSection } from "./features/backtest/BacktestSection";
import { ConformanceSection } from "./features/conformance/ConformanceSection";
import { ProposalsSection } from "./features/proposals/ProposalsSection";
import { ReliabilitySection } from "./features/reliability/ReliabilitySection";
import { motion as timing } from "./shared/theme/tokens";

export function App() {
  const { state, retry } = useReports();
  const reduce = useReducedMotion();
  return (
    <main className="page">
      <h1>Backtest the change before anyone approves it</h1>
      <p className="lede">
        Payment investigations at a simulated bank: recalls, claims of non-receipt and queries. Each proposed change to the process is replayed
        over the bank's own history, then the bank runs it for real and we compare.
      </p>
      {state.status === "loading" && <Loading />}
      {state.status === "error" && <LoadError message={state.message} onRetry={retry} />}
      {state.status === "empty" && <Empty />}
      {state.status === "ready" && (
        <motion.div initial={{ opacity: reduce ? 1 : 0 }} animate={{ opacity: 1 }} transition={{ duration: reduce ? 0 : timing.fade }}>
          <BacktestSection dev={state.reports.dev} heldout={state.reports.heldout} />
          {state.reports.results.exampleWorld && <ConformanceSection world={state.reports.results.exampleWorld} />}
          {state.reports.results.exampleWorld && <ProposalsSection world={state.reports.results.exampleWorld} />}
          <ReliabilitySection results={state.reports.results} />
          <footer>
            Every number on this page is read from the files in <code>reports/</code>, which CI regenerates and compares on every push.
          </footer>
        </motion.div>
      )}
    </main>
  );
}
