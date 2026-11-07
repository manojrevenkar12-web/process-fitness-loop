import { space } from "../theme/tokens";

const SKELETON_ROWS = 6;
const SKELETON_HEIGHT = 18;
const SKELETON_TAPER = 9;

export function Loading() {
  return (
    <div aria-busy="true" aria-label="Loading reports">
      {Array.from({ length: SKELETON_ROWS }, (_, i) => (
        <div
          key={i}
          className="skeleton"
          style={{ height: SKELETON_HEIGHT, marginBottom: space.sm, width: `${100 - i * SKELETON_TAPER}%` }}
        />
      ))}
    </div>
  );
}

export function LoadError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="notice" role="alert">
      <p className="strong">The reports could not be loaded.</p>
      <p className="muted small">{message}</p>
      <button type="button" onClick={onRetry}>
        Load again
      </button>
    </div>
  );
}

export function Empty() {
  return (
    <div className="notice">
      <p className="strong">No reports yet.</p>
      <p className="muted">
        Generate them with <code>mvn -pl pfl-world exec:java -Dexec.args=dev</code>, then <code>pfl report</code>, and reload.
      </p>
    </div>
  );
}
