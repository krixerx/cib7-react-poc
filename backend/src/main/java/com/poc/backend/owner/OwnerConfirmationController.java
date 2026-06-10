package com.poc.backend.owner;

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
 * Public REST surface for the owner-confirmation flow in
 * {@code vehicle-registration.bpmn}.
 *
 * <p>Endpoints under {@code /api/public/**} are unauthenticated (see
 * {@link com.poc.backend.security.SecurityConfig}). Authorization is by
 * knowledge of the per-owner UUID token: the applicant's form generates a
 * token for the applicant and one for each additional owner, the engine
 * stores them as process variables, and the controller correlates back to
 * the waiting receive task in the multi-instance subprocess via the
 * message API's {@code localCorrelationKeys}.
 *
 * <p>All engine access goes through {@link EngineClient} over
 * {@code /engine-rest} — this service has no embedded engine. Spin JSON
 * process variables round-trip as engine type {@code Json} and are handled
 * here as Jackson trees.
 *
 * <p>State machine surfaced to the SPA via {@link OwnerStatus#state}:
 *
 * <pre>
 *   pending          - this owner has not signed yet
 *   confirmed_waiting - this owner signed, others still pending
 *   ready_to_send    - every owner signed; "Send to process" available
 *   sent             - send-to-process already fired
 *   rejected         - some owner rejected; case looped back to applicant
 * </pre>
 *
 * <p>Token-to-process lookup is intentionally O(active instances): the POC
 * scans active {@code vehicleRegistration} instances and matches against
 * {@code applicantToken} / {@code additionalOwners[].token}. Move to a
 * dedicated index table if the case load ever grows beyond demo scale.
 */
@RestController
@RequestMapping("/api/public/owner-confirmations")
public class OwnerConfirmationController {

    private static final String PROCESS_KEY = "vehicleRegistration";

    private final EngineClient engine;
    private final ObjectMapper mapper = new ObjectMapper();

    public OwnerConfirmationController(EngineClient engine) {
        this.engine = engine;
    }

    @GetMapping("/{token}/status")
    public ResponseEntity<?> getStatus(@PathVariable String token) {
        TokenLookup lookup = findByToken(token);
        if (lookup == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("unknown_token",
                            "This confirmation link is unknown or has expired."));
        }
        return ResponseEntity.ok(buildStatus(lookup, token));
    }

    @PostMapping("/{token}")
    public ResponseEntity<?> confirm(@PathVariable String token, @RequestBody ConfirmRequest req) {
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
                            "This confirmation link is unknown or has expired."));
        }
        if (lookup.isApplicant()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("applicant_auto_confirmed",
                            "The applicant's signature is recorded automatically."));
        }

        ProcessVars vars = readVars(lookup.processInstanceId());
        if (Boolean.TRUE.equals(vars.rejectedByOwner())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("already_rejected",
                            "Another owner has already rejected this application."));
        }
        if (Boolean.TRUE.equals(vars.sentToProcess())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("already_sent",
                            "This case has already been sent to the back office."));
        }
        ObjectNode confirmations = vars.ownerConfirmations();
        if (confirmations.has(token)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("already_signed",
                            "You have already signed this application."));
        }

        // Persist the per-owner outcome BEFORE message correlation so the
        // status endpoint reflects it even if a sibling instance fires the
        // completion condition immediately after.
        writeConfirmation(confirmations, token, decision, req.reason());
        engine.setJsonVariable(lookup.processInstanceId(), "ownerConfirmations", confirmations);

        if ("reject".equals(decision)) {
            // Process-scope flag drives both the multi-instance completionCondition
            // and the post-subprocess gateway. The correlate's localCorrelationKeys
            // on the receiveTask just identifies WHICH instance is being unblocked.
            engine.setBooleanVariable(lookup.processInstanceId(), "rejectedByOwner", true);
            engine.setStringVariable(lookup.processInstanceId(), "sendBackReason",
                    "Owner " + lookup.ownerName() + " rejected the application: " + req.reason().trim());
        }

        boolean correlated = engine.correlateMessage("OwnerConfirmation",
                lookup.processInstanceId(),
                Map.of("ownerToken", token),
                Map.of());
        if (!correlated) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("not_waiting",
                            "This case is no longer waiting for owner signatures."));
        }

        return ResponseEntity.ok(buildStatus(lookup, token));
    }

    @PostMapping("/{token}/send-to-process")
    public ResponseEntity<?> sendToProcess(@PathVariable String token) {
        TokenLookup lookup = findByToken(token);
        if (lookup == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("unknown_token",
                            "This confirmation link is unknown or has expired."));
        }
        ProcessVars vars = readVars(lookup.processInstanceId());
        if (Boolean.TRUE.equals(vars.sentToProcess())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("already_sent",
                            "This case has already been sent to the back office."));
        }
        if (Boolean.TRUE.equals(vars.rejectedByOwner())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("already_rejected",
                            "This case was rejected by an owner and is back with the applicant."));
        }
        OwnerStatus status = buildStatus(lookup, token);
        if (!"ready_to_send".equals(status.state())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("not_ready",
                            "Not all owners have signed yet."));
        }

        boolean correlated = engine.correlateMessage("SendToProcess",
                lookup.processInstanceId(),
                Map.of(),
                Map.of("sentToProcess", true));
        if (!correlated) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("not_waiting",
                            "This case is not waiting on a send-to-process signal."));
        }

        return ResponseEntity.ok(buildStatus(lookup, token));
    }

    // --- helpers ---------------------------------------------------------

    /**
     * Linear scan over active {@value #PROCESS_KEY} instances. Returns the
     * one whose {@code applicantToken} matches, or which has a matching
     * entry in {@code additionalOwners}. Linear is fine for a POC; add an
     * index table if traffic grows.
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

        // Slow path: scan additionalOwners. The engine doesn't index inside
        // Json-typed lists, so we read each variable and walk it.
        for (String piId : engine.findActive(PROCESS_KEY)) {
            JsonNode owners = engine.getJsonVariable(piId, "additionalOwners");
            if (owners == null || !owners.isArray()) continue;
            for (JsonNode owner : owners) {
                if (token.equals(owner.path("token").asText(null))) {
                    return new TokenLookup(piId, owner.path("name").asText(""), false);
                }
            }
        }
        return null;
    }

    private String applicantName(String piId) {
        String first = engine.getStringVariable(piId, "firstName");
        String last = engine.getStringVariable(piId, "lastName");
        return ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
    }

    private ProcessVars readVars(String piId) {
        return new ProcessVars(
                engine.getStringVariable(piId, "firstName"),
                engine.getStringVariable(piId, "lastName"),
                engine.getStringVariable(piId, "applicantEmail"),
                engine.getStringVariable(piId, "applicantToken"),
                engine.getJsonVariable(piId, "additionalOwners"),
                ensureMap(engine.getJsonVariable(piId, "ownerConfirmations")),
                engine.getBooleanVariable(piId, "rejectedByOwner"),
                engine.getBooleanVariable(piId, "sentToProcess"));
    }

    private ObjectNode ensureMap(JsonNode in) {
        return in instanceof ObjectNode obj ? obj : mapper.createObjectNode();
    }

    /** Mutates {@code confirmations} in place — caller must setJsonVariable after. */
    private void writeConfirmation(ObjectNode confirmations, String token,
                                   String decision, String reason) {
        ObjectNode entry = mapper.createObjectNode();
        entry.put("status", "approve".equals(decision) ? "approved" : "rejected");
        entry.put("signedAt", Instant.now().toString());
        if (reason != null && !reason.isBlank()) {
            entry.put("reason", reason.trim());
        }
        confirmations.set(token, entry);
    }

    private OwnerStatus buildStatus(TokenLookup lookup, String requestToken) {
        ProcessVars vars = readVars(lookup.processInstanceId());

        String applicantName = (Objects.toString(vars.firstName(), "") + " "
                + Objects.toString(vars.lastName(), "")).trim();

        List<OwnerEntry> owners = new ArrayList<>();
        owners.add(buildOwnerEntry(applicantName, vars.applicantEmail(),
                vars.applicantToken(), true, vars.ownerConfirmations()));
        if (vars.additionalOwners() != null && vars.additionalOwners().isArray()) {
            for (JsonNode owner : vars.additionalOwners()) {
                owners.add(buildOwnerEntry(
                        owner.path("name").asText(""),
                        owner.path("email").asText(""),
                        owner.path("token").asText(null),
                        false,
                        vars.ownerConfirmations()));
            }
        }

        boolean allApproved = owners.stream().allMatch(o -> "approved".equals(o.status()));
        String state;
        if (Boolean.TRUE.equals(vars.rejectedByOwner())) state = "rejected";
        else if (Boolean.TRUE.equals(vars.sentToProcess())) state = "sent";
        else if (allApproved) state = "ready_to_send";
        else state = "pending";

        OwnerEntry current = owners.stream()
                .filter(o -> Objects.equals(o.token(), requestToken))
                .findFirst()
                .orElse(null);

        OwnerEntry rejecter = owners.stream()
                .filter(o -> "rejected".equals(o.status()))
                .findFirst()
                .orElse(null);

        return new OwnerStatus(
                lookup.processInstanceId(),
                applicantName,
                current,
                owners,
                state,
                rejecter != null ? rejecter.name() : null,
                rejecter != null ? rejecter.reason() : null);
    }

    private static OwnerEntry buildOwnerEntry(String name, String email, String token,
                                              boolean isApplicant, JsonNode confirmations) {
        String status = "pending";
        String signedAt = null;
        String reason = null;
        if (confirmations != null && token != null && confirmations.has(token)) {
            JsonNode entry = confirmations.get(token);
            if (entry.hasNonNull("status")) status = entry.get("status").asText();
            if (entry.hasNonNull("signedAt")) signedAt = entry.get("signedAt").asText();
            if (entry.hasNonNull("reason")) reason = entry.get("reason").asText();
        }
        return new OwnerEntry(name, email, token, isApplicant, status, signedAt, reason);
    }

    private static ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("bad_request", message));
    }

    // --- DTOs ------------------------------------------------------------

    public record ConfirmRequest(String decision, String reason) {}

    public record OwnerEntry(
            String name,
            String email,
            String token,
            boolean isApplicant,
            String status,
            String signedAt,
            String reason) {}

    public record OwnerStatus(
            String processInstanceId,
            String applicantName,
            OwnerEntry currentOwner,
            List<OwnerEntry> owners,
            String state,
            String rejectedBy,
            String rejectionReason) {}

    public record ErrorResponse(String code, String message) {}

    private record TokenLookup(String processInstanceId, String ownerName, boolean isApplicant) {}

    private record ProcessVars(
            String firstName,
            String lastName,
            String applicantEmail,
            String applicantToken,
            JsonNode additionalOwners,
            ObjectNode ownerConfirmations,
            Boolean rejectedByOwner,
            Boolean sentToProcess) {}
}
