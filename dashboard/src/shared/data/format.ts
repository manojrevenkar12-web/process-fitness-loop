import { decimals, percent } from "../theme/tokens";

const DECIMAL_BASE = 10;

export function signed(value: number, digits: number = decimals.score): string {
  const fixed = Math.abs(value).toFixed(digits);
  if (Number(fixed) === 0) {
    return (0).toFixed(digits);
  }
  return (value < 0 ? "−" : "+") + fixed;
}

export function share(value: number, digits: number = decimals.share): string {
  const text = (value * percent).toFixed(digits);
  if (value > 0 && Number(text) === 0) {
    return `under ${(1 / Math.pow(DECIMAL_BASE, digits)).toFixed(digits)}%`;
  }
  return `${text}%`;
}

export function sentence(text: string): string {
  return text.length === 0 ? text : text.charAt(0).toUpperCase() + text.slice(1) + (text.endsWith(".") ? "" : ".");
}

export function count(value: number): string {
  return value.toLocaleString("en-GB");
}

/** Proposal ids as people say them: "hub-connector" becomes "Hub connector". */
export function title(id: string): string {
  const words = id.replace(/-/g, " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export const verdictWords: Record<string, string> = {
  IMPROVES: "Improves",
  UNCLEAR: "No clear gain",
  WORSE: "Worse",
  NEEDS_SHADOW_RUN: "Shadow run first",
  BLOCKED: "Blocked",
};
