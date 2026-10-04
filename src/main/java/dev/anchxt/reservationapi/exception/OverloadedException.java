package dev.anchxt.reservationapi.exception;

/** Admission refused (429): too many reserves already in flight. No stack trace, like conflicts. */
public class OverloadedException extends RuntimeException {

  public OverloadedException() {
    super("overloaded", null, false, false);
  }
}
