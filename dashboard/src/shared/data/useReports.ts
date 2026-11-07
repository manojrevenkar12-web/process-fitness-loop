import { useCallback, useEffect, useState } from "react";
import type { BacktestReport, Reports, Results } from "./types";

export type LoadState =
  | { status: "loading" }
  | { status: "error"; message: string }
  | { status: "empty" }
  | { status: "ready"; reports: Reports };

const DATA = "./data/";
const NOT_FOUND = 404;

async function optional<T>(name: string): Promise<T | null> {
  const res = await fetch(DATA + name, { cache: "no-store" });
  // static hosts answer a missing file with 404, dev servers with their index page
  if (res.status === NOT_FOUND || !(res.headers.get("content-type") ?? "").includes("json")) {
    return null;
  }
  if (!res.ok) {
    throw new Error(`${name}: HTTP ${res.status}`);
  }
  return (await res.json()) as T;
}

/** Loads the generated reports; a missing results file is the empty state, not an error. */
export function useReports(): { state: LoadState; retry: () => void } {
  const [state, setState] = useState<LoadState>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setState({ status: "loading" });
    Promise.all([optional<Results>("results.json"), optional<BacktestReport>("backtest-dev.json"), optional<BacktestReport>("backtest-heldout.json")])
      .then(([results, dev, heldout]) => {
        if (cancelled) {
          return;
        }
        if (!results) {
          setState({ status: "empty" });
          return;
        }
        setState({ status: "ready", reports: { results, dev, heldout } });
      })
      .catch((e: unknown) => {
        if (!cancelled) {
          setState({ status: "error", message: e instanceof Error ? e.message : String(e) });
        }
      });
    return () => {
      cancelled = true;
    };
  }, [attempt]);

  const retry = useCallback(() => setAttempt((a) => a + 1), []);
  return { state, retry };
}
