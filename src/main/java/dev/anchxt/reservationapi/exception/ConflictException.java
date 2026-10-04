package dev.anchxt.reservationapi.exception;

/**
 * A domain decline (409). The message is the reason code. No stack trace: thousands are thrown per
 * second on a hot seat and none of them is a bug.
 */
public class ConflictException extends RuntimeException {

  public ConflictException(String reason) {
    super(reason, null, false, false);
  }
}
