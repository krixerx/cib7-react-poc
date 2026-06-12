package com.poc.backend.rop;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One vehicle's clearance status — the stand-in for three external linkages of the ROP ITS demo
 * (technical inspection systems, insurance companies, and the fines/circulars restrictions check).
 * Keyed by VIN. VINs without a row are treated as all-clear by the controller; the seeded demo VINs
 * exercise the rejection branches of {@code rop-vehicle-registration.bpmn}.
 */
@Entity
@Table(name = "rop_vehicle_clearances")
public class VehicleClearance {

  @Id private String vin;

  @Column(nullable = false)
  private boolean inspectionPassed;

  @Column(nullable = false)
  private boolean insured;

  @Column(nullable = false)
  private boolean restrictionsCleared;

  protected VehicleClearance() {
    // JPA
  }

  public VehicleClearance(
      String vin, boolean inspectionPassed, boolean insured, boolean restrictionsCleared) {
    this.vin = vin;
    this.inspectionPassed = inspectionPassed;
    this.insured = insured;
    this.restrictionsCleared = restrictionsCleared;
  }

  public String getVin() {
    return vin;
  }

  public boolean isInspectionPassed() {
    return inspectionPassed;
  }

  public boolean isInsured() {
    return insured;
  }

  public boolean isRestrictionsCleared() {
    return restrictionsCleared;
  }
}
