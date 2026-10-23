package com.example.hotel.exception;

/** A stable business error code, also used as the exception message. */
public class ReservationRejectedException extends RuntimeException {
  public ReservationRejectedException(String code) {
    super(code);
  }
}
