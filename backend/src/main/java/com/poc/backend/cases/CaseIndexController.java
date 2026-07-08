package com.poc.backend.cases;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingest endpoint for case summary cards — the engine's BPMN "Index case" milestone tasks POST here
 * through the integration bus, which injects {@code X-Internal-Token} (SecurityConfig's internal
 * chain, same trust level as {@code move-pending} / {@code server-upload}; the path lives under
 * {@code /api/documents} so it rides the bus's existing token-injection route).
 *
 * <p>The primary key is the process instance id, so every milestone re-post <em>replaces</em> the
 * previous card — the table always holds exactly one card per case reflecting its latest status.
 *
 * <p>Ingest is best-effort by design: a storage failure is logged and answered with {@code indexed:
 * false}, never an error status — a case card must never fail or retry the process that posted it.
 */
@RestController
@RequestMapping("/api/documents")
public class CaseIndexController {

  private static final Logger log = LoggerFactory.getLogger(CaseIndexController.class);

  private final CaseCardRepository repository;

  public CaseIndexController(CaseCardRepository repository) {
    this.repository = repository;
  }

  @PostMapping("/index-case")
  public ResponseEntity<?> indexCase(@RequestBody IndexCaseRequest req) {
    if (isBlank(req.processInstanceId())
        || isBlank(req.service())
        || isBlank(req.status())
        || isBlank(req.summary())) {
      return ResponseEntity.badRequest()
          .body(
              new ErrorResponse(
                  "bad_request",
                  "processInstanceId, service, status and summary are all required."));
    }

    try {
      repository.save(
          new CaseCard(req.processInstanceId(), req.service(), req.status(), req.summary()));
      log.info(
          "Stored case card {} ({} / {}).", req.processInstanceId(), req.service(), req.status());
      return ResponseEntity.ok(new IndexCaseResponse(true));
    } catch (Exception e) {
      log.warn("Case-card store failed for {}: {}", req.processInstanceId(), e.getMessage());
      return ResponseEntity.ok(new IndexCaseResponse(false));
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  public record IndexCaseRequest(
      String processInstanceId, String service, String status, String summary) {}

  public record IndexCaseResponse(boolean indexed) {}

  public record ErrorResponse(String code, String message) {}
}
