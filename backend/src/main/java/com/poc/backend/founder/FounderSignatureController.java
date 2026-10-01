package com.poc.backend.founder;

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
 * Public REST surface for the co-founder signing flow in {@code business-registration.bpmn}.
 *
 * <p>Same {@link ConsentFlow} as {@link com.poc.backend.owner.OwnerConfirmationController}, with OÜ
 * founder semantics. The credential is the capability token in the URL (purpose {@code founder}),
 * signed by the engine into each founder's email; see docs/security.md rule 3.
 *
 * <p>State machine surfaced to the SPA via {@link FounderStatus#state}:
 *
 * <pre>
 *   pending           - not every founder has signed yet
 *   ready_to_send     - every co-founder signed; "Submit to register" available
 *   sent              - submit-to-register already fired
 *   rejected          - some co-founder rejected; case looped back to applicant
 * </pre>
 */
@RestController
@RequestMapping("/api/public/founder-signatures")
public class FounderSignatureController {

  private static final ConsentFlow.Config CONFIG =
      new ConsentFlow.Config(
          "businessRegistration",
          "founder",
          "applicantFirstName",
          "applicantLastName",
          "additionalFounders",
          "founderSignatures",
          "rejectedByFounder",
          "sentToRegister",
          "FounderSignature",
          "SubmitToRegister",
          "Co-founder",
          "rejected the registration");

  private static final ConsentFlow.Messages MESSAGES =
      new ConsentFlow.Messages(
          "This signing link is unknown or has expired.",
          "Another co-founder has already rejected this registration.",
          "This case has already been submitted to the Business Register.",
          "You have already signed this registration.",
          "This case is no longer waiting for co-founder signatures.",
          "This case was rejected by a co-founder and is back with the applicant.",
          "Not all co-founders have signed yet.",
          "This case is not waiting on a submit-to-register signal.");

  private final ConsentFlow flow;
  private final EngineClient engine;

  public FounderSignatureController(EngineClient engine, CapabilityLinkVerifier verifier) {
    this.flow = new ConsentFlow(CONFIG, MESSAGES, engine, verifier);
    this.engine = engine;
  }

  @GetMapping("/{token}/status")
  public ResponseEntity<?> getStatus(@PathVariable String token) {
    return respond(flow.status(token));
  }

  @PostMapping("/{token}")
  public ResponseEntity<?> sign(@PathVariable String token, @RequestBody SignRequest req) {
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
    return respond(flow.sign(token, decision, req.reason()));
  }

  @PostMapping("/{token}/submit-to-register")
  public ResponseEntity<?> submitToRegister(@PathVariable String token) {
    return respond(flow.send(token));
  }

  private ResponseEntity<?> respond(Outcome outcome) {
    if (outcome.refusal() != null) {
      return ResponseEntity.status(outcome.refusal().httpStatus())
          .body(new ErrorResponse(outcome.refusal().code(), outcome.refusal().message()));
    }
    Snapshot s = outcome.snapshot();
    return ResponseEntity.ok(
        new FounderStatus(
            s.processInstanceId(),
            s.applicantName(),
            engine.getStringVariable(s.processInstanceId(), "companyName"),
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

  public record SignRequest(String decision, String reason) {}

  /** Founders carry display name and state only: no email, no token (docs/security.md rule 3). */
  public record FounderStatus(
      String processInstanceId,
      String applicantName,
      String companyName,
      Party currentFounder,
      List<Party> founders,
      String state,
      String rejectedBy,
      String rejectionReason) {}

  public record ErrorResponse(String code, String message) {}
}
