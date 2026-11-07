import { motion, useReducedMotion } from "framer-motion";
import { useMemo, useState } from "react";
import type { WorldResult } from "../../shared/data/types";
import { signed, title, verdictWords } from "../../shared/data/format";
import { chart, motion as timing } from "../../shared/theme/tokens";
import { useWidth } from "../../shared/ui/useWidth";
import { allValues, groupByProposal, scoreScale, type Row } from "./scale";

const AXIS_LABEL_OFFSET = 18;
const ROW_LABEL_INSET = 12;
const GROUP_LABEL_LIFT = 6;
const ZERO_LINE_WIDTH = 1.5;
const TEXT_BASELINE_NUDGE = 4;

/**
 * For every proposal and every world: the backtest's interval for the change in fitness score,
 * what really happened, and what a spreadsheet would have said.
 */
export function IntervalChart({ worlds }: { worlds: WorldResult[] }) {
  const { ref, width } = useWidth<HTMLDivElement>(chart.minWidth * 2);
  const reduce = useReducedMotion();
  const [focus, setFocus] = useState<Row | null>(null);

  const groups = useMemo(() => groupByProposal(worlds), [worlds]);
  const narrow = width < chart.labelWidth * 2;
  const labelWidth = narrow ? 0 : chart.labelWidth;
  const plotWidth = Math.max(chart.minWidth, width);
  const scale = useMemo(
    () => scoreScale(allValues(worlds), labelWidth, plotWidth - chart.axisInset, chart.domainPad),
    [worlds, labelWidth, plotWidth],
  );

  let y = chart.axisHeight;
  const placed = groups.map((g) => {
    // the proposal's name sits on its own line above its rows
    const top = y + chart.groupLabel;
    y = top + g.rows.length * (chart.rowHeight + chart.rowGap) + chart.groupGap;
    return { ...g, top };
  });
  const height = y;
  let rowIndex = 0;

  return (
    <div ref={ref} className="chart-scroll">
      <svg className="chart" width={plotWidth} height={height} role="img" aria-label="Projected change in fitness score per proposal and world, with the true change">
        {scale.ticks.map((t) => (
          <g key={t}>
            <line x1={scale.x(t)} x2={scale.x(t)} y1={chart.axisHeight - chart.rowGap} y2={height - chart.groupGap}
              stroke="var(--rule)" strokeDasharray={t === 0 ? undefined : "2 4"} strokeWidth={t === 0 ? ZERO_LINE_WIDTH : 1} />
            <text x={scale.x(t)} y={AXIS_LABEL_OFFSET} textAnchor="middle">{signed(t, 1)}</text>
          </g>
        ))}
        {placed.map((g) => {
          const verdict = g.rows[0].outcome.verdict;
          return (
            <g key={g.proposal}>
              <text className="group-label" x={0} y={g.top - GROUP_LABEL_LIFT}>
                {title(g.proposal)}
                <tspan className={`verdict-${verdict}`} dx={8} fontWeight={500} fill="currentColor">{verdictWords[verdict]}</tspan>
              </text>
              {g.rows.map((row, i) => {
                const s = row.outcome.score;
                const cy = g.top + i * (chart.rowHeight + chart.rowGap) + chart.rowHeight / 2;
                const delay = reduce ? 0 : rowIndex++ * timing.rowStagger;
                const lo = scale.x(s.projected.low);
                const hi = scale.x(s.projected.high);
                const est = scale.x(s.projected.estimate);
                const label = `${title(row.outcome.proposal)}, world ${row.world}: projected ${signed(s.projected.estimate)} (${signed(s.projected.low)} to ${signed(s.projected.high)}), true ${signed(s.true)}, spreadsheet ${signed(s.naive)}`;
                return (
                  <g key={row.world} tabIndex={0} aria-label={label}
                    onMouseEnter={() => setFocus(row)} onFocus={() => setFocus(row)} onMouseLeave={() => setFocus(null)} onBlur={() => setFocus(null)}>
                    <rect x={labelWidth} y={cy - chart.rowHeight / 2} width={plotWidth - labelWidth} height={chart.rowHeight} fill="transparent" />
                    {!narrow && <text x={labelWidth - ROW_LABEL_INSET} y={cy + TEXT_BASELINE_NUDGE} textAnchor="end">world {row.world}</text>}
                    <motion.rect
                      y={cy - chart.barHeight / 2} height={chart.barHeight} rx={chart.barHeight / 2}
                      fill="var(--projection)" fillOpacity={0.85}
                      initial={reduce ? { x: lo, width: hi - lo } : { x: est, width: 0 }}
                      animate={{ x: lo, width: Math.max(hi - lo, 1) }}
                      transition={{ duration: reduce ? 0 : timing.barOpen, ease: timing.ease, delay }}
                    />
                    <line x1={est} x2={est} y1={cy - chart.tickHeight / 2} y2={cy + chart.tickHeight / 2} stroke="var(--projection)" strokeWidth={2} />
                    <rect x={scale.x(s.naive) - chart.markRadius} y={cy - chart.markRadius} width={chart.markRadius * 2} height={chart.markRadius * 2}
                      transform={`rotate(45 ${scale.x(s.naive)} ${cy})`} fill="var(--naive)" />
                    <motion.circle cx={scale.x(s.true)} cy={cy} r={chart.markRadius} fill="var(--truth)" stroke="var(--paper)" strokeWidth={ZERO_LINE_WIDTH}
                      initial={{ opacity: reduce ? 1 : 0 }} animate={{ opacity: 1 }}
                      transition={{ duration: reduce ? 0 : timing.fade, delay: delay + (reduce ? 0 : timing.truthDelay) }} />
                  </g>
                );
              })}
            </g>
          );
        })}
      </svg>
      <p className="small muted" aria-live="polite" style={{ minHeight: "1.6em" }}>
        {focus
          ? `${title(focus.outcome.proposal)}, world ${focus.world}: projected ${signed(focus.outcome.score.projected.estimate)} (${signed(focus.outcome.score.projected.low)} to ${signed(focus.outcome.score.projected.high)}), true ${signed(focus.outcome.score.true)}, spreadsheet ${signed(focus.outcome.score.naive)}.`
          : "Point at a row for its numbers."}
      </p>
    </div>
  );
}
