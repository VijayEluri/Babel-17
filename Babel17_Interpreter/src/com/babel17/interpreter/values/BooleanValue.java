package com.babel17.interpreter.values;

public final class BooleanValue extends Value {

  public final static BooleanValue TRUE =
          new BooleanValue(true);

  public final static BooleanValue FALSE =
          new BooleanValue(false);

  public final static BooleanValue create(boolean b) {
    if (b) return TRUE; else return FALSE;
  }

  private BooleanValue(boolean b) {
    this.value = b;
  }

  public boolean value() {
    return value;
  }

  private boolean value;

}
