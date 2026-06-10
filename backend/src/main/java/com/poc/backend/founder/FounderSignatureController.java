package com.poc.backend.founder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.poc.backend.engine.EngineClient;

/**
 * Public REST surface for the co-founder signing flow in
 * {@code business-registration.bpmn}.
 *
 * <p>Mirrors {@link com.poc.backend.owner.OwnerConfirmationController}
 * line-for-line; OÜ founder semantics swapped for vehicle-owner semantics.
 * Both controllers use the same pattern: per-participant UUID token in the
 * URL is the credential, message correlation on a local variable carries
 * the decision back into the multi-instance subprocess.
 *
 * <p>State machine surfaced to the SPA via {@link FounderStatus#state}:
 *
 * <pre>
 *   pending           - this co-founder has not signed yet
 *   confirmed_waiting - this co-founder signed, others still pending
 *   ready_to_send     - every co-founder signed; "Submit to register" available
 *   sent              - submit-to-register already fired
 *   rejected          - some co-founder rejected; case looped back to applicant
 * </pre>
 *
 * <p>Token-to-process lookup is intentionally O(active instances): scans
 * active {@code businessRegistration} instances and matches against
 * {@code applicantToken} / {@code additionalFounders[].token}. Move to a
 * dedicated index table if traffic grows. Two controllers doing this same
 * scan is the kind of duplication that earns its own abstraction at N=3 —
 * see redomain plan PR #3 notes.
 */
@RestController
@RequestMapping("/api/public/founder-signatures")
public class FounderSignatureController {

    private static final String PROCESS_KEY = "businessRegistration";

    private final EngineClient engine;
    private final ObjectMapper mapper = new ObjectMapper();

    public FounderSignatureController(EngineClient engine) {
        this.engine = engine;
    }

    @GetMapping("/{token}/status")
    public ResponseEntity<?> getStatus(@PathVariable String token) {
        TokenLookup lookup = findByToken(token);
        if (lookup == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("unknown_token",
                            "This signing link is unknown or has expired."));
        }
        return ResponseEntity.ok(buildStatus(lookup, token));
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

        TokenLookup lookup = findByToken(token);
        if (lookup == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("unknown_token",
                            "This signing link is unknown or has expired."));
        }
        if (lookup.isApplicant()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("applicant_auto_confirmed",
                            "The applicant's signature is recorded automatically."));
        }

        ProcessVars vars = readVars(lookup.processInstanceId());
        if (Boolean.TRUE.equals(vars.rejectedByFounder())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("already_rejected",
                            "Another co-founder has already rejected this registration."));
        }
        if (Boolean.TRUE.equals(vars.sentToRegister())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("already_sent",
                            "This case has already been submitted to the Business Register."));
        }
        ObjectNode signatures = vars.founderSignatures();
        if (signatures.has(token)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("already_signed",
                            "You have already signed this registration."));
        }

        // Persist the per-founder outcome BEFORE message correlation so the
        // status endpoint reflects it even if a sibling instance fires the
        // completion condition immediately after.
        writeSignature(signatures, token, decision, req.reason());
        engine.setJsonVariable(lookup.processInstanceId(), "founderSignatures", signatures);

        if ("reject".equals(decision)) {
            // Process-scope flag drives both the multi-instance completionCondition
            // and the post-subprocess gateway. The correlate's localCorrelationKeys
            // on the receiveTask just identifies WHICH instance is being unblocked.
            engine.setBooleanVariable(lookup.processInstanceId(), "rejectedByFounder", true);
            engine.setStringVariable(lookup.processInstanceId(), "sendBackReason",
                    "Co-founder " + lookup.founderName()
                            + " rejected the registration: " + req.reason().trim());
        }

        boolean correlated = engine.correlateMessage("FounderSignature",
                lookup.processInstanceId(),
                Map.of("founderToken", token),
                Map.of());
        if (!correlated) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("not_waiting",
                            "This case is no longer waiting for co-founder signatures."));
        }

        return ResponseEntity.ok(buildStatus(lookup, token));
    }

    @PostMapping("/{token}/submit-to-register")
    public ResponseEntity<?> submitToRegister(@PathVariable String token) {
        TokenLookup lookup = findByToken(token);
        if (lookup == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("unknown_token",
                            "This signing link is unknown or has expired."));
        }
        ProcessVars vars = readVars(lookup.processInstanceId());
        if (Boolean.TRUE.equals(vars.sentToRegister())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("already_sent",
                            "This case has already been submitted to the Business Register."));
        }
        if (Boolean.TRUE.equals(vars.rejectedByFounder())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("already_rejected",
                            "This case was rejected by a co-founder and is back with the applicant."));
        }
        FounderStatus status = buildStatus(lookup, token);
        if (!"ready_to_send".equals(status.state())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("not_ready",
                            "Not all co-founders have signed yet."));
        }

        boolean correlated = engine.correlateMessage("SubmitToRegister",
                lookup.processInstanceId(),
                Map.of(),
                Map.of("sentToRegister", true));
        if (!correlated) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("not_waiting",
                            "This case is not waiting on a submit-to-register signal."));
        }

        return ResponseEntity.ok(buildStatus(lookup, token));
    }

    // --- helpers ---------------------------------------------------------

    /**
     * Linear scan over active {@value #PROCESS_KEY} instances. Returns the
     * one whose {@code applicantToken} matches, or which has a matching
     * entry in {@code additionalFounders}. Linear is fine for a POC; add
     * an index table if traffic grows.
     */
    private TokenLookup findByToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        // Fast path for the applicant: variable equality is indexed engine-side.
        List<String> applicantHits = engine.findActiveByVariable(PROCESS_KEY, "applicantToken", token);
        if (!applicantHits.isEmpty()) {
            String piId = applicantHits.get(0);
            return new TokenLookup(piId, applicantName(piId), true);
        }

        // Slow path: scan additionalFounders. The engine doesn't index inside
        // Json-typed lists, so we read each variable and walk it.
        for (String piId : engine.findActive(PROCESS_KEY)) {
            JsonNode founders = engine.getJsonVariable(piId, "additionalFounders");
            if (founders == null || !founders.isArray()) continue;
            for (JsonNode founder : founders) {
                if (token.equals(founder.path("token").asText(null))) {
                    return new TokenLookup(piId, founder.path("name").asText(""), false);
                }
            }
        }
        return null;
    }

    private String applicantName(String piId) {
        String first = engine.getStringVariable(piId, "applicantFirstName");
        String last = engine.getStringVariable(piId, "applicantLastName");
        return ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
    }

    private ProcessVars readVars(String piId) {
        return new ProcessVars(
                engine.getStringVariable(piId, "applicantFirstName"),
                engine.getStringVariable(piId, "applicantLastName"),
                engine.getStringVariable(piId, "applicantEmail"),
                engine.getStringVariable(piId, "applicantToken"),
                engine.getStringVariable(piId, "companyName"),
                engine.getJsonVariable(piId, "additionalFounders"),
                ensureMap(engine.getJsonVariable(piId, "founderSignatures")),
                engine.getBooleanVariable(piId, "rejectedByFounder"),
                engine.getBooleanVariable(piId, "sentToRegister"));
    }

    private ObjectNode ensureMap(JsonNode in) {
        return in instanceof ObjectNode obj ? obj : mapper.createObjectNode();
    }

    /** Mutates {@code signatures} in place — caller must setJsonVariable after. */
    private void writeSignature(ObjectNode signatures, String token,
                                String decision, String reason) {
        ObjectNode entry = mapper.createObjectNode();
        entry.put("status", "approve".equals(decision) ? "approved" : "rejected");
        entry.put("signedAt", Instant.now().toString());
        if (reason != null && !reason.isBlank()) {
            entry.put("reason", reason.trim());
        }
        signatures.set(token, entry);
    }

    private FounderStatus buildStatus(TokenLookup lookup, String requestToken) {
        ProcessVars vars = readVars(lookup.processInstanceId());

        String applicantName = (Objects.toString(vars.applicantFirstName(), "") + " "
                + Objects.toString(vars.applicantLastName(), "")).trim();

        List<FounderEntry> founders = new ArrayList<>();
        founders.add(buildFounderEntry(applicantName, vars.applicantEmail(),
                vars.applicantToken(), true, vars.founderSignatures()));
        if (vars.additionalFounders() != null && vars.additionalFounders().isArray()) {
            for (JsonNode founder : vars.additionalFounders()) {
                founders.add(buildFounderEntry(
                        founder.path("name").asText(""),
                        founder.path("email").asText(""),
                        founder.path("token").asText(null),
                        false,
                        vars.founderSignatures()));
            }
        }

        boolean allApproved = founders.stream().allMatch(f -> "approved".equals(f.status()));
        String state;
        if (Boolean.TRUE.equals(vars.rejectedByFounder())) state = "rejected";
        else if (Boolean.TRUE.equals(vars.sentToRegister())) state = "sent";
        else if (allApproved) state = "ready_to_send";
        else state = "pending";

        FounderEntry current = founders.stream()
                .filter(f -> Objects.equals(f.token(), requestToken))
                .findFirst()
                .orElse(null);

        FounderEntry rejecter = founders.stream()
                .filter(f -> "rejected".equals(f.status()))
                .findFirst()
                .orElse(null);

        return new FounderStatus(
                lookup.processInstanceId(),
                applicantName,
                vars.companyName(),
                current,
                founders,
                state,
                rejecter != null ? rejecter.name() : null,
                rejecter != null ? rejecter.reason() : null);
    }

    private static FounderEntry buildFounderEntry(String name, String email, String token,
                                                  boolean isApplicant, JsonNode signatures) {
        String status = "pending";
        String signedAt = null;
        String reason = null;
        if (signatures != null && token != null && signatures.has(token)) {
            JsonNode entry = signatures.get(token);
            if (entry.hasNonNull("status")) status = entry.get("status").asText();
            if (entry.hasNonNull("signedAt")) signedAt = entry.get("signedAt").asText();
            if (entry.hasNonNull("reason")) reason = entry.get("reason").asText();
        }
        return new FounderEntry(name, email, token, isApplicant, status, signedAt, reason);
    }

    private static ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("bad_request", message));
    }

    // --- DTOs ------------------------------------------------------------

    public record SignRequest(String decision, String reason) {}

    public record FounderEntry(
            String name,
            String email,
            String token,
            boolean isApplicant,
            String status,
            String signedAt,
            String reason) {}

    public record FounderStatus(
            String processInstanceId,
            String applicantName,
            String companyName,
            FounderEntry currentFounder,
            List<FounderEntry> founders,
            String state,
            String rejectedBy,
            String rejectionReason) {}

    public record ErrorResponse(String code, String message) {}

    private record TokenLookup(String processInstanceId, String founderName, boolean isApplicant) {}

    private record ProcessVars(
            String applicantFirstName,
            String applicantLastName,
            String applicantEmail,
            String applicantToken,
            String companyName,
            JsonNode additionalFounders,
            ObjectNode founderSignatures,
            Boolean rejectedByFounder,
            Boolean sentToRegister) {}
}
