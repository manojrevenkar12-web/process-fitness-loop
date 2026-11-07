/** Every size, duration and chart dimension the dashboard uses. Components never hard-code these. */

export const space = { xs: 4, sm: 8, md: 16, lg: 24, xl: 40, xxl: 64 } as const;

export const motion = {
  /** the one orchestrated reveal: interval bars open from their estimate */
  barOpen: 0.55,
  rowStagger: 0.025,
  truthDelay: 0.35,
  fade: 0.2,
  ease: [0.22, 1, 0.36, 1] as const,
} as const;

export const chart = {
  rowHeight: 18,
  rowGap: 6,
  groupGap: 28,
  labelWidth: 96,
  groupLabel: 22,
  axisInset: 22,
  axisHeight: 28,
  markRadius: 4.5,
  barHeight: 8,
  tickHeight: 12,
  minWidth: 320,
  /** the score axis runs from slightly below zero to just above the best change */
  domainPad: 0.05,
} as const;

export const layout = {
  maxWidth: 1080,
  gutter: 16,
  wideGutter: 40,
  narrowBreakpoint: 720,
} as const;

export const evidenceBar = { height: 10, width: 220 } as const;

export const percent = 100;
export const decimals = { score: 3, share: 0, kpi: 2 } as const;

/** Tick steps the score axis may use, smallest first. */
export const niceSteps = [0.05, 0.1, 0.2, 0.25, 0.5, 1] as const;
