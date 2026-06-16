package com.poc.backend.transport;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The system-of-record row for one issued Transport Authority driving learning license — written by {@code
 * Task_TransportIssuePermit} after the 6 EUR fee is paid. Validity is one year from issue.
 */
@Entity
@Table(
    name = "transport_learning_permits",
    indexes = {@Index(name = "idx_transport_permits_pi", columnList = "processInstanceId")})
public class LearningPermitRecord {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private String permitNumber;

  @Column(nullable = false)
  private String civilId;

  @Column(nullable = false)
  private String applicantName;

  @Column(nullable = false)
  private String licenseCategory;

  @Column(nullable = false)
  private String processInstanceId;

  @Column(nullable = false)
  private Instant issuedAt;

  @Column(nullable = false)
  private LocalDate validUntil;

  protected LearningPermitRecord() {
    // JPA
  }

  public LearningPermitRecord(
      String permitNumber,
      String civilId,
      String applicantName,
      String licenseCategory,
      String processInstanceId,
      Instant issuedAt,
      LocalDate validUntil) {
    this.permitNumber = permitNumber;
    this.civilId = civilId;
    this.applicantName = applicantName;
    this.licenseCategory = licenseCategory;
    this.processInstanceId = processInstanceId;
    this.issuedAt = issuedAt;
    this.validUntil = validUntil;
  }

  public Long getId() {
    return id;
  }

  public String getPermitNumber() {
    return permitNumber;
  }

  public String getCivilId() {
    return civilId;
  }

  public String getApplicantName() {
    return applicantName;
  }

  public String getLicenseCategory() {
    return licenseCategory;
  }

  public String getProcessInstanceId() {
    return processInstanceId;
  }

  public Instant getIssuedAt() {
    return issuedAt;
  }

  public LocalDate getValidUntil() {
    return validUntil;
  }
}
