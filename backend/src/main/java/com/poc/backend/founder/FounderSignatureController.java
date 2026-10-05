package com.poc.backend.founder;

import com.poc.backend.consent.ConsentFlow;
import com.poc.backend.consent.ConsentFlow.Outcome;
import com.poc.backend.consent.ConsentFlow.Party;
import com.poc.backend.consent.ConsentFlow.Snapshot;
import com.poc.backend.documents.Document;
import com.poc.backend.documents.DocumentDownloads;
import com.poc.backend.documents.DocumentRepository;
import com.poc.backend.engine.EngineClient;
import com.poc.backend.links.CapabilityLinkVerifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

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

  private static final String ARTICLES_CATEGORY = "founder-articles-of-association";

  private final ConsentFlow flow;
  private final EngineClient engine;
  private final DocumentRepository documents;
  private final DocumentDownloads downloads;

  public FounderSignatureController(
      EngineClient engine,
      CapabilityLinkVerifier verifier,
      DocumentRepository documents,
      DocumentDownloads downloads) {
    this.flow = new ConsentFlow(CONFIG, MESSAGES, engine, verifier);
    this.engine = engine;
    this.documents = documents;
    this.downloads = downloads;
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

  /**
   * Presigned GET for the Articles of Association the founder is asked to sign. The document is
   * looked up from the case the verified token names, never from an id the caller passes, and only
   * when it is that case's articles upload (docs/security.md rules 3 and 6).
   */
  @GetMapping("/{token}/articles/download-url")
  public ResponseEntity<?> articlesDownloadUrl(@PathVariable String token) {
    Outcome outcome = flow.status(token);
    if (outcome.refusal() != null) {
      return refusal(outcome);
    }
    return articles(outcome.snapshot().processInstanceId())
        .<ResponseEntity<?>>map(doc -> ResponseEntity.ok(downloads.mint(doc)))
        .orElseGet(
            () ->
                ResponseEntity.status(404)
                    .body(new ErrorResponse("not_found", "No Articles of Association on file.")));
  }

  private ResponseEntity<?> respond(Outcome outcome) {
    if (outcome.refusal() != null) {
      return refusal(outcome);
    }
    Snapshot s = outcome.snapshot();
    String pi = s.processInstanceId();
    return ResponseEntity.ok(
        new FounderStatus(
            pi,
            s.applicantName(),
            engine.getStringVariable(pi, "companyName"),
            shareCapital(pi),
            boardMembers(pi),
            articles(pi).map(Document::getFilename).orElse(null),
            s.current(),
            s.parties(),
            s.state(),
            s.rejectedBy(),
            s.rejectionReason()));
  }

  private static ResponseEntity<?> refusal(Outcome outcome) {
    return ResponseEntity.status(outcome.refusal().httpStatus())
        .body(new ErrorResponse(outcome.refusal().code(), outcome.refusal().message()));
  }

  private Optional<Document> articles(String pi) {
    String id = engine.getStringVariable(pi, "aoaDocumentAttachmentId");
    if (id == null || id.isBlank()) {
      return Optional.empty();
    }
    return documents
        .findById(id)
        .filter(d -> pi.equals(d.getProcessInstanceId()))
        .filter(d -> ARTICLES_CATEGORY.equals(d.getCategory()));
  }

  private Double shareCapital(String pi) {
    Object raw = engine.getRawVariable(pi, "shareCapital");
    if (raw instanceof Number n) {
      return n.doubleValue();
    }
    if (raw instanceof String str) {
      try {
        return Double.valueOf(str.trim());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  /**
   * Board members by name only. Personal codes stay off this unauthenticated page: a co-founder
   * needs to know who will run the company, not their national id numbers.
   */
  private List<BoardMember> boardMembers(String pi) {
    JsonNode rows;
    try {
      rows = engine.getJsonVariable(pi, "boardMembers");
    } catch (IllegalStateException e) {
      return List.of();
    }
    List<BoardMember> out = new ArrayList<>();
    if (rows != null && rows.isArray()) {
      for (JsonNode row : rows) {
        String name =
            (row.path("firstName").asText("") + " " + row.path("lastName").asText("")).trim();
        if (!name.isEmpty()) {
          out.add(new BoardMember(name));
        }
      }
    }
    return out;
  }

  private static ResponseEntity<?> badRequest(String message) {
    return ResponseEntity.badRequest().body(new ErrorResponse("bad_request", message));
  }

  // --- DTOs ------------------------------------------------------------

  public record SignRequest(String decision, String reason) {}

  /**
   * Founders carry display name and state only: no email, no token (docs/security.md rule 3). The
   * founding details are what the founder is signing; {@code articlesFilename} is null when no
   * articles were uploaded.
   */
  public record FounderStatus(
      String processInstanceId,
      String applicantName,
      String companyName,
      Double shareCapital,
      List<BoardMember> boardMembers,
      String articlesFilename,
      Party currentFounder,
      List<Party> founders,
      String state,
      String rejectedBy,
      String rejectionReason) {}

  public record BoardMember(String name) {}

  public record ErrorResponse(String code, String message) {}
}
