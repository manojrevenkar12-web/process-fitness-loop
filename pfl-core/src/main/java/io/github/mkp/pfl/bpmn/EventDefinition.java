package io.github.mkp.pfl.bpmn;

/**
 * The event definition of a start, catch or boundary event.
 *
 * @param type the kind of trigger
 * @param timerDuration ISO-8601 duration of a timer ({@code timeDuration}), or null
 * @param messageName name of the message, or null
 * @param correlationKey {@code zeebe:subscription correlationKey} expression, or null
 * @param errorCode the error code an error boundary catches, or null for a catch-all
 * @param definitionMissing true when the definition element refers to something that is not in the
 *     file (a message or error id that does not resolve)
 */
public record EventDefinition(
    Type type,
    String timerDuration,
    String messageName,
    String correlationKey,
    String errorCode,
    boolean definitionMissing) {

  /** Kinds of event trigger. */
  public enum Type {
    NONE,
    TIMER,
    MESSAGE,
    ERROR,
    UNSUPPORTED
  }

  public static final EventDefinition NONE = new EventDefinition(Type.NONE, null, null, null, null, false);
}
