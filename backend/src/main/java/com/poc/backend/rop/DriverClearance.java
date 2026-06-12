package com.poc.backend.rop;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One applicant's driver clearance — the stand-in for the ROP ITS demo's approved-opticians eye
 * test linkage, the existing-license check, and the violations/circulars restrictions check. Keyed
 * by the Omani civil number. Civil IDs without a row are treated as all-clear with a passed eye
 * test; the seeded demo IDs exercise the branches of {@code rop-learning-permit.bpmn}.
 */
@Entity
@Table(name = "rop_driver_clearances")
public class DriverClearance {

  @Id private String civilId;

  /** {@code pass | weak | fail | missing}. */
  @Column(nullable = false)
  private String eyeTestResult;

  @Column(nullable = false)
  private boolean hasValidTemporaryLicense;

  @Column(nullable = false)
  private boolean restrictionsCleared;

  protected DriverClearance() {
    // JPA
  }

  public DriverClearance(
      String civilId,
      String eyeTestResult,
      boolean hasValidTemporaryLicense,
      boolean restrictionsCleared) {
    this.civilId = civilId;
    this.eyeTestResult = eyeTestResult;
    this.hasValidTemporaryLicense = hasValidTemporaryLicense;
    this.restrictionsCleared = restrictionsCleared;
  }

  public String getCivilId() {
    return civilId;
  }

  public String getEyeTestResult() {
    return eyeTestResult;
  }

  public boolean isHasValidTemporaryLicense() {
    return hasValidTemporaryLicense;
  }

  public boolean isRestrictionsCleared() {
    return restrictionsCleared;
  }
}
