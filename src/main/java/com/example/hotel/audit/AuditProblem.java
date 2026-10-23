package com.example.hotel.audit;

public class AuditProblem extends RuntimeException {
  private final int status;

  public AuditProblem(int status, String code) {
    super(code);
    this.status = status;
  }

  public int status() {
    return status;
  }
}
