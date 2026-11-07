package io.github.mkp.pfl.log;

/** What happened to a step at a point in time. */
public enum Lifecycle {
  /** The step became due: a task was created, a wait began. */
  READY,
  /** Someone or something started working on it. */
  START,
  /** It finished normally. */
  COMPLETE,
  /** It was stopped before finishing, e.g. by an interrupting boundary event. */
  TERMINATE
}
