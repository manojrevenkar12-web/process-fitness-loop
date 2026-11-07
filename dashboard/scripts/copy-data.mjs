// Copies the generated reports into public/data so the dashboard shows exactly what the code produced.
import { copyFileSync, existsSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const reports = join(here, "..", "..", "reports");
const target = join(here, "..", "public", "data");
mkdirSync(target, { recursive: true });
for (const name of ["results.json", "backtest-dev.json", "backtest-heldout.json"]) {
  const from = join(reports, name);
  if (existsSync(from)) {
    copyFileSync(from, join(target, name));
    console.log(`data: ${name}`);
  }
}
