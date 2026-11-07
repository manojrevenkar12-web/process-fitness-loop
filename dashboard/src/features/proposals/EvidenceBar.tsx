import type { ProposalOutcome } from "../../shared/data/types";
import { share } from "../../shared/data/format";
import { evidenceBar } from "../../shared/theme/tokens";

const PARTS = [
  { key: "SAME_CASE", label: "this case's own history", color: "var(--same-case)" },
  { key: "SIMILAR_CASES", label: "similar cases", color: "var(--similar)" },
  { key: "ASSUMED", label: "assumptions", color: "var(--assumed)" },
] as const;

/** Where the simulated work time of a projection came from. */
export function EvidenceBar({ evidence }: { evidence: ProposalOutcome["evidence"] }) {
  const description = PARTS.map((p) => `${share(evidence[p.key] ?? 0)} ${p.label}`).join(", ");
  return (
    <figure style={{ margin: 0 }}>
      <svg width={evidenceBar.width} height={evidenceBar.height} role="img" aria-label={`Evidence: ${description}`}>
        {PARTS.reduce<{ x: number; parts: JSX.Element[] }>(
          (acc, p) => {
            const w = (evidence[p.key] ?? 0) * evidenceBar.width;
            acc.parts.push(<rect key={p.key} x={acc.x} y={0} width={w} height={evidenceBar.height} fill={p.color} />);
            acc.x += w;
            return acc;
          },
          { x: 0, parts: [] },
        ).parts}
      </svg>
      <figcaption className="small muted">{description}</figcaption>
    </figure>
  );
}
