package com.poc.backend.owner;

import com.poc.backend.consent.ConsentFlow;
import com.poc.backend.consent.ConsentFlow.Outcome;
import com.poc.backend.consent.ConsentFlow.Party;
import com.poc.backend.consent.ConsentFlow.Snapshot;
import com.poc.backend.engine.EngineClient;
import com.poc.backend.links.CapabilityLinkVerifier;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public REST surface for the co-owner confirmation flow in {@code vehicle-registration.bpmn}.
 *
 * <p>Endpoints under {@code /api/public/**} have no login; the credential is the capability token
 * in the URL, which the engine signs into each owner's email (purpose {@code owner}, see {@link
 * CapabilityLinkVerifier} and docs/security.md rule 3). The token names the case and the party, so
 * a link can only act for the owner it was sent to, and only in the current consent round.
 *
 * <p>State machine surfaced to the SPA via {@link OwnerStatus#state}:
 *
 * <pre>
 *   pending          - not every owner has signed yet
 *   ready_to_send    - every owner signed; "Send to process" available
 *   sent             - send-to-process already fired
 *   rejected         - some owner rejected; case looped back to applicant
 * </pre>
 */
@RestController
@RequestMapping("/api/public/owner-confirmations")
public class OwnerConfirmationController {

  private static final ConsentFlow.Config CONFIG =
      new ConsentFlow.Config(
          "vehicleRegistration",
          "owner",
          "firstName",
          "lastName",
          "additionalOwners",
          "ownerConfirmations",
          "rejectedByOwner",
          "sentToProcess",
          "OwnerConfirmation",
          "SendToProcess",
          "Owner",
          "rejected the application");

  private static final ConsentFlow.Messages MESSAGES =
      new ConsentFlow.Messages(
          "This confirmation link is unknown or has expired.",
          "Another owner has already rejected this application.",
          "This case has already been sent to the back office.",
          "You have already signed this application.",
          "This case is no longer waiting for owner signatures.",
          "This case was rejected by an owner and is back with the applicant.",
          "Not all owners have signed yet.",
          "This case is not waiting on a send-to-process signal.");

  private final ConsentFlow flow;

  public OwnerConfirmationController(EngineClient engine, CapabilityLinkVerifier verifier) {
    this.flow = new ConsentFlow(CONFIG, MESSAGES, engine, verifier);
  }

  @GetMapping("/{token}/status")
  public ResponseEntity<?> getStatus(@PathVariable String token) {
    return respond(flow.status(token));
  }

  @PostMapping("/{token}")
  public ResponseEntity<?> confirm(@PathVariable String token, @RequestBody ConfirmRequest req) {
    ResponseEntity<?> invalid = validate(req);
    if (invalid != null) {
      return invalid;
    }
    return respond(flow.sign(token, req.decision().toLowerCase(), req.reason()));
  }

  @PostMapping("/{token}/send-to-process")
  public ResponseEntity<?> sendToProcess(@PathVariable String token) {
    return respond(flow.send(token));
  }

  static ResponseEntity<?> validate(ConfirmRequest req) {
    if (req == null || req.decision() == null) {
      return badRequest("Decision is required.");
    }
    String decision = req.decision().toLowerCase();
    if (!"approve".equals(decision) && !"reject".equals(decision)) {
      return badRequest("Decision must be 'approve' or 'reject'.");
    }
    if ("reject".equals(decision) && (req.reason() == null || req.reason().isBlank())) {
      return badRequest("A reason is required when rejecting.");
    }
    return null;
  }

  private static ResponseEntity<?> respond(Outcome outcome) {
    if (outcome.refusal() != null) {
      return ResponseEntity.status(outcome.refusal().httpStatus())
          .body(new ErrorResponse(outcome.refusal().code(), outcome.refusal().message()));
    }
    Snapshot s = outcome.snapshot();
    return ResponseEntity.ok(
        new OwnerStatus(
            s.processInstanceId(),
            s.applicantName(),
            s.current(),
            s.parties(),
            s.state(),
            s.rejectedBy(),
            s.rejectionReason()));
  }

  private static ResponseEntity<?> badRequest(String message) {
    return ResponseEntity.badRequest().body(new ErrorResponse("bad_request", message));
  }

  // --- DTOs ------------------------------------------------------------

  public record ConfirmRequest(String decision, String reason) {}

  /** Owners carry display name and state only: no email, no token (docs/security.md rule 3). */
  public record OwnerStatus(
      String processInstanceId,
      String applicantName,
      Party currentOwner,
      List<Party> owners,
      String state,
      String rejectedBy,
      String rejectionReason) {}

  public record ErrorResponse(String code, String message) {}
}
