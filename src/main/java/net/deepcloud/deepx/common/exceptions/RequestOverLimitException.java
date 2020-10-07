package net.deepcloud.deepx.common.exceptions;

public class RequestOverLimitException extends DeepXExecException {

  private static final long serialVersionUID = 1L;

  public RequestOverLimitException(String message) {
    super(message);
  }
}
