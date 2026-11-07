package io.github.mkp.pfl.camunda;

/** The cluster refused or failed a request. */
public class CamundaException extends RuntimeException {

  private final int status;

  public CamundaException(int status, String message) {
    super(message);
    this.status = status;
  }

  public int status() {
    return status;
  }
}
