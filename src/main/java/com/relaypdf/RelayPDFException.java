package com.relaypdf;

public final class RelayPDFException extends RuntimeException {
  private final int status;
  private final String code;
  private final Integer retryAfter;
  private final Object details;

  public RelayPDFException(int status, String code, String message, Integer retryAfter, Object details) {
    super(message);
    this.status = status;
    this.code = code;
    this.retryAfter = retryAfter;
    this.details = details;
  }

  public int getStatus() { return status; }
  public String getCode() { return code; }
  public Integer getRetryAfter() { return retryAfter; }
  public Object getDetails() { return details; }
}
