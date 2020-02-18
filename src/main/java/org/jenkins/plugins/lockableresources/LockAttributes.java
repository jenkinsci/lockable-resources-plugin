package org.jenkins.plugins.lockableresources;

import java.util.List;

public class LockAttributes {
  private List<LockStepResource> extra = null;
  private String resource = null;
  private String label = null;
  private int quantity = 0;
  private String variable = null;
  private final boolean inversePrecedence;

  public LockAttributes(
      final List<LockStepResource> extra,
      final String resource,
      final String label,
      final int quantity,
      final String variable,
      final boolean inversePrecedence) {
    this.extra = extra;
    this.resource = resource;
    this.label = label;
    this.quantity = quantity;
    this.variable = variable;
    this.inversePrecedence = inversePrecedence;
  }

  public List<LockStepResource> getExtra() {
    return extra;
  }

  public String getResource() {
    return resource;
  }

  public String getLabel() {
    return label;
  }

  public int getQuantity() {
    return quantity;
  }

  public String getVariable() {
    return variable;
  }

  public boolean getInversePrecedence() {
    return inversePrecedence;
  }

  @Override
  public String toString() {
    return "LockAttributes [extra="
        + extra
        + ", resource="
        + resource
        + ", label="
        + label
        + ", quantity="
        + quantity
        + ", variable="
        + variable
        + ", inversePrecedence="
        + inversePrecedence
        + "]";
  }
}
