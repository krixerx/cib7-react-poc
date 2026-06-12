package com.poc.backend.rop;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The system-of-record row for one completed ROP vehicle registration — written by {@code
 * Task_RopAllocatePlate} after the fee payment is correlated. Carries the allocated (or reserved)
 * plate number plus enough denormalised case data to read the table without the engine.
 */
@Entity
@Table(
    name = "rop_plate_registrations",
    indexes = {@Index(name = "idx_rop_plates_pi", columnList = "processInstanceId")})
public class PlateRegistration {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private String plateNumber;

  @Column(nullable = false)
  private String vin;

  @Column(nullable = false)
  private String ownerName;

  @Column(nullable = false)
  private String vehicleCategory;

  @Column(nullable = false)
  private String processInstanceId;

  @Column(nullable = false)
  private Instant issuedAt;

  protected PlateRegistration() {
    // JPA
  }

  public PlateRegistration(
      String plateNumber,
      String vin,
      String ownerName,
      String vehicleCategory,
      String processInstanceId,
      Instant issuedAt) {
    this.plateNumber = plateNumber;
    this.vin = vin;
    this.ownerName = ownerName;
    this.vehicleCategory = vehicleCategory;
    this.processInstanceId = processInstanceId;
    this.issuedAt = issuedAt;
  }

  public Long getId() {
    return id;
  }

  public String getPlateNumber() {
    return plateNumber;
  }

  public String getVin() {
    return vin;
  }

  public String getOwnerName() {
    return ownerName;
  }

  public String getVehicleCategory() {
    return vehicleCategory;
  }

  public String getProcessInstanceId() {
    return processInstanceId;
  }

  public Instant getIssuedAt() {
    return issuedAt;
  }
}
