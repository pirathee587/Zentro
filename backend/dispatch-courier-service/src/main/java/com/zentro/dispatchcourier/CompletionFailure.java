package com.zentro.dispatchcourier;

/** Only safe fixed error codes are retained; never dependency response bodies. */
public class CompletionFailure extends RuntimeException {
  private final String state;
  private final String code;
  public CompletionFailure(String state, String code) { super(code); this.state = state; this.code = code; }
  public String state() { return state; }
  public String code() { return code; }
}
