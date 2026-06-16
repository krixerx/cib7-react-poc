package com.poc.backend.transport;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Learning-permit issuance for {@code transport-learning-permit.bpmn} — called by {@code
 * Task_TransportIssuePermit} strictly after the 6 EUR fee payment is correlated. Persists the permit
 * (validity one year from issue) and returns the permit number the PDF and email render.
 */
@RestController
@RequestMapping("/api/public/transport/learning-permits")
public class TransportLearningPermitController {

  private final LearningPermitRepository permits;

  public TransportLearningPermitController(LearningPermitRepository permits) {
    this.permits = permits;
  }

  @PostMapping("/issue")
  public ResponseEntity<?> issue(@RequestBody IssueRequest req) {
    if (isBlank(req.processInstanceId())
        || isBlank(req.civilId())
        || isBlank(req.applicantName())
        || isBlank(req.licenseCategory())) {
      return ResponseEntity.badRequest()
          .body(
              new ErrorResponse(
                  "missing_fields",
                  "processInstanceId, civilId, applicantName, and licenseCategory are required."));
    }

    Instant now = Instant.now();
    LocalDate validUntil = LocalDate.now().plusYears(1);
    String permitNumber = "LP-%d-%06d".formatted(Year.now().getValue(), permits.count() + 1);

    LearningPermitRecord saved =
        permits.save(
            new LearningPermitRecord(
                permitNumber,
                req.civilId().trim(),
                req.applicantName().trim(),
                req.licenseCategory().trim(),
                req.processInstanceId().trim(),
                now,
                validUntil));

    return ResponseEntity.ok(
        new IssueResponse(
            saved.getPermitNumber(), saved.getValidUntil().toString(), saved.getIssuedAt()));
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  public record IssueRequest(
      String processInstanceId, String civilId, String applicantName, String licenseCategory) {}

  public record IssueResponse(String permitNumber, String validUntil, Instant issuedAt) {}

  public record ErrorResponse(String code, String message) {}
}
