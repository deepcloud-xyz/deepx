
package net.deepcloud.deepx.common.exceptions;

public class DeepXExecException extends RuntimeException {

  private static final long serialVersionUID = 1L;


  public DeepXExecException() {
  }

  public DeepXExecException(String message) {
    super(message);
  }

  public DeepXExecException(String message, Throwable cause) {
    super(message, cause);
  }

  public DeepXExecException(Throwable cause) {
    super(cause);
  }

  public DeepXExecException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
    super(message, cause, enableSuppression, writableStackTrace);
  }
}
