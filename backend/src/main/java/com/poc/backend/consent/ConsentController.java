package com.poc.backend.consent;

import com.poc.backend.consent.ConsentCatalog.Descriptor;
import com.poc.backend.consent.ConsentCatalog.Detail;
import com.poc.backend.consent.ConsentCatalog.DocumentRef;
import com.poc.backend.consent.ConsentFlow.Outcome;
import com.poc.backend.consent.ConsentFlow.Party;
import com.poc.backend.consent.ConsentFlow.Snapshot;
import com.poc.backend.documents.Document;
import com.poc.backend.documents.DocumentDownloads;
import com.poc.backend.documents.DocumentRepository;
import com.poc.backend.engine.EngineClient;
import com.poc.backend.links.CapabilityLinkVerifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The co-signing pages' API for every purpose the service pack declares (co-owner confirmation,
 * co-founder signature, ...), under {@code /api/public/consent/<purpose>/<token>}.
 *
 * <p>These endpoints have no login: the credential is the capability token in the path, verified by
 * {@link CapabilityLinkVerifier} for the purpose in the path (docs/security.md rules 3 and 5). A
 * token minted for one purpose does not open another, an unknown purpose answers like an unknown
 * link, and the response carries display names and states but never another party's email or token.
 * The case details and documents shown are exactly the ones the purpose's descriptor names; a
 * document is looked up from the case the verified token names, never from an id the caller passes,
 * and only when it belongs to that case and carries the declared category (rule 6).
 */
@RestController
@RequestMapping("/api/public/consent/{purpose}/{token}")
public class ConsentController {

  private final ConsentCatalog catalog;
  private final EngineClient engine;
  private final CapabilityLinkVerifier verifier;
  private final DocumentRepository documents;
  private final DocumentDownloads downloads;
  private final Map<String, ConsentFlow> flows = new ConcurrentHashMap<>();

  public ConsentController(
      ConsentCatalog catalog,
      EngineClient engine,
      CapabilityLinkVerifier verifier,
      DocumentRepository documents,
      DocumentDownloads downloads) {
    this.catalog = catalog;
    this.engine = engine;
    this.verifier = verifier;
    this.documents = documents;
    this.downloads = downloads;
  }

  @GetMapping("/status")
  public ResponseEntity<?> status(@PathVariable String purpose, @PathVariable String token) {
    return descriptor(purpose)
        .<ResponseEntity<?>>map(d -> respond(d, flow(d).status(token)))
        .orElseGet(ConsentController::unknownPurpose);
  }

  @PostMapping
  public ResponseEntity<?> sign(
      @PathVariable String purpose, @PathVariable String token, @RequestBody SignRequest req) {
    Optional<Descriptor> descriptor = descriptor(purpose);
    if (descriptor.isEmpty()) {
      return unknownPurpose();
    }
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
    Descriptor d = descriptor.get();
    return respond(d, flow(d).sign(token, decision, req.reason()));
  }

  @PostMapping("/send")
  public ResponseEntity<?> send(@PathVariable String purpose, @PathVariable String token) {
    return descriptor(purpose)
        .<ResponseEntity<?>>map(d -> respond(d, flow(d).send(token)))
        .orElseGet(ConsentController::unknownPurpose);
  }

  @GetMapping("/documents/{name}/download-url")
  public ResponseEntity<?> documentDownloadUrl(
      @PathVariable String purpose, @PathVariable String token, @PathVariable String name) {
    Optional<Descriptor> descriptor = descriptor(purpose);
    if (descriptor.isEmpty()) {
      return unknownPurpose();
    }
    Descriptor d = descriptor.get();
    Outcome outcome = flow(d).status(token);
    if (outcome.refusal() != null) {
      return refusal(outcome);
    }
    DocumentRef ref = d.documents().get(name);
    return Optional.ofNullable(ref)
        .flatMap(r -> document(outcome.snapshot().processInstanceId(), r))
        .<ResponseEntity<?>>map(doc -> ResponseEntity.ok(downloads.mint(doc)))
        .orElseGet(
            () ->
                ResponseEntity.status(404)
                    .body(new ErrorResponse("not_found", "No such document on file.")));
  }

  private Optional<Descriptor> descriptor(String purpose) {
    return catalog.purpose(purpose);
  }

  private ConsentFlow flow(Descriptor d) {
    return flows.computeIfAbsent(
        d.purpose(), p -> new ConsentFlow(d.config(), d.messages(), engine, verifier));
  }

  private ResponseEntity<?> respond(Descriptor d, Outcome outcome) {
    if (outcome.refusal() != null) {
      return refusal(outcome);
    }
    Snapshot s = outcome.snapshot();
    String pi = s.processInstanceId();
    Map<String, Object> details = new LinkedHashMap<>();
    d.details().forEach((name, detail) -> details.put(name, detail(pi, detail)));
    Map<String, String> files = new LinkedHashMap<>();
    d.documents()
        .forEach(
            (name, ref) ->
                files.put(name, document(pi, ref).map(Document::getFilename).orElse(null)));
    return ResponseEntity.ok(
        new ConsentStatus(
            pi,
            s.applicantName(),
            s.current(),
            s.parties(),
            s.state(),
            s.rejectedBy(),
            s.rejectionReason(),
            details,
            files));
  }

  private Object detail(String pi, Detail detail) {
    return switch (detail.type()) {
      case STRING -> engine.getStringVariable(pi, detail.variable());
      case NUMBER -> number(engine.getRawVariable(pi, detail.variable()));
      case NAMES -> names(pi, detail.variable());
    };
  }

  private static Double number(Object raw) {
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
   * A list of people reduced to {@code {name}} entries. Anything else on the rows, such as personal
   * codes, stays off this unauthenticated page.
   */
  private List<Map<String, String>> names(String pi, String variable) {
    JsonNode rows;
    try {
      rows = engine.getJsonVariable(pi, variable);
    } catch (IllegalStateException e) {
      return List.of();
    }
    List<Map<String, String>> out = new ArrayList<>();
    if (rows != null && rows.isArray()) {
      for (JsonNode row : rows) {
        String name =
            (row.path("firstName").asText("") + " " + row.path("lastName").asText("")).trim();
        if (!name.isEmpty()) {
          out.add(Map.of("name", name));
        }
      }
    }
    return out;
  }

  private Optional<Document> document(String pi, DocumentRef ref) {
    String id = engine.getStringVariable(pi, ref.variable());
    if (id == null || id.isBlank()) {
      return Optional.empty();
    }
    return documents
        .findById(id)
        .filter(doc -> pi.equals(doc.getProcessInstanceId()))
        .filter(doc -> ref.category().equals(doc.getCategory()));
  }

  private static ResponseEntity<?> refusal(Outcome outcome) {
    return ResponseEntity.status(outcome.refusal().httpStatus())
        .body(new ErrorResponse(outcome.refusal().code(), outcome.refusal().message()));
  }

  /** Same answer as an unknown link, so the path reveals nothing about which purposes exist. */
  private static ResponseEntity<?> unknownPurpose() {
    return ResponseEntity.status(404)
        .body(new ErrorResponse("unknown_token", "This link is unknown or has expired."));
  }

  private static ResponseEntity<?> badRequest(String message) {
    return ResponseEntity.badRequest().body(new ErrorResponse("bad_request", message));
  }

  public record SignRequest(String decision, String reason) {}

  /**
   * Parties carry display name and state only: no email, no token (docs/security.md rule 3). {@code
   * details} holds what the descriptor declares, by name; {@code documents} maps each declared
   * document to its file name, or null when none is on file.
   */
  public record ConsentStatus(
      String processInstanceId,
      String applicantName,
      Party current,
      List<Party> parties,
      String state,
      String rejectedBy,
      String rejectionReason,
      Map<String, Object> details,
      Map<String, String> documents) {}

  public record ErrorResponse(String code, String message) {}
}
