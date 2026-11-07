package io.github.mkp.pfl.bpmn;

/** The file is not something we can read as a BPMN process at all. */
public class BpmnParseException extends RuntimeException {

  public BpmnParseException(String message) {
    super(message);
  }

  public BpmnParseException(String message, Throwable cause) {
    super(message, cause);
  }
}
