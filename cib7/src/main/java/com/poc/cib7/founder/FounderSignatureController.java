package com.poc.cib7.founder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.cibseven.bpm.engine.MismatchingMessageCorrelationException;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.spin.Spin;
import org.cibseven.spin.json.SpinJsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public REST surface for the co-founder signing flow in
 * {@code business-registration.bpmn}.
 *
 * <p>Mirrors {@link com.poc.cib7.owner.OwnerConfirmationController}
 * line-for-line; OÜ founder semantics swapped for vehicle-owner semantics.
 * Both controllers use the same pattern: per-participant UUID token in the
 * URL is the credential, message correlation on a local variable carries
 * the decision back into the multi-instance subprocess. PublicApiSecurityConfig
 * already opens {@code /api/public/**}.
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

    private static final Logger LOG = LoggerFactory.getLogger(FounderSignatureController.class);

    private static final String PROCESS_KEY = "businessRegistration";

    private final RuntimeService runtimeService;

    public FounderSignatureController(RuntimeService runtimeService) {
        this.runtimeService = runtimeService;
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
        SpinJsonNode signatures = vars.founderSignatures();
        if (hasStatus(signatures, token)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("already_signed",
                            "You have already signed this registration."));
        }

        // Persist the per-founder outcome BEFORE message correlation so the
        // status endpoint reflects it even if a sibling instance fires the
        // completion condition immediately after.
        writeSignature(signatures, token, decision, req.reason());
        runtimeService.setVariable(lookup.processInstanceId(), "founderSignatures", signatures);

        if ("reject".equals(decision)) {
            // Process-scope flag drives both the multi-instance completionCondition
            // and the post-subprocess gateway. setVariable on the PI puts it at
            // process scope; the correlate's localVariableEquals on the
            // receiveTask just identifies WHICH instance is being unblocked.
            runtimeService.setVariable(lookup.processInstanceId(), "rejectedByFounder", true);
            runtimeService.setVariable(lookup.processInstanceId(), "sendBackReason",
                    "Co-founder " + lookup.founderName()
                            + " rejected the registration: " + req.reason().trim());
        }

        try {
            runtimeService.createMessageCorrelation("FounderSignature")
                    .processInstanceId(lookup.processInstanceId())
                    .localVariableEquals("founderToken", token)
                    .correlate();
        } catch (MismatchingMessageCorrelationException e) {
            LOG.warn("FounderSignature message had no waiting receive task for token {}: {}",
                    token, e.getMessage());
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

        try {
            runtimeService.createMessageCorrelation("SubmitToRegister")
                    .processInstanceId(lookup.processInstanceId())
                    .setVariable("sentToRegister", true)
                    .correlate();
        } catch (MismatchingMessageCorrelationException e) {
            LOG.warn("SubmitToRegister had no waiting receive task for PI {}: {}",
                    lookup.processInstanceId(), e.getMessage());
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
        // Fast path for the applicant: variableValueEquals is indexed.
        List<ProcessInstance> applicantHits = runtimeService.createProcessInstanceQuery()
                .processDefinitionKey(PROCESS_KEY)
                .active()
                .variableValueEquals("applicantToken", token)
                .list();
        if (!applicantHits.isEmpty()) {
            ProcessInstance pi = applicantHits.get(0);
            String name = applicantName(pi.getId());
            return new TokenLookup(pi.getId(), name, true);
        }

        // Slow path: scan additionalFounders. CIB seven doesn't index inside
        // Spin Json lists, so we read each variable and walk it.
        List<ProcessInstance> all = runtimeService.createProcessInstanceQuery()
                .processDefinitionKey(PROCESS_KEY)
                .active()
                .list();
        for (ProcessInstance pi : all) {
            Object raw = runtimeService.getVariable(pi.getId(), "additionalFounders");
            if (!(raw instanceof SpinJsonNode)) continue;
            for (SpinJsonNode founder : ((SpinJsonNode) raw).elements()) {
                if (token.equals(founder.prop("token").stringValue())) {
                    return new TokenLookup(pi.getId(),
                            founder.prop("name").stringValue(), false);
                }
            }
        }
        return null;
    }

    private String applicantName(String piId) {
        String first = (String) runtimeService.getVariable(piId, "applicantFirstName");
        String last = (String) runtimeService.getVariable(piId, "applicantLastName");
        return ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
    }

    private ProcessVars readVars(String piId) {
        return new ProcessVars(
                (String) runtimeService.getVariable(piId, "applicantFirstName"),
                (String) runtimeService.getVariable(piId, "applicantLastName"),
                (String) runtimeService.getVariable(piId, "applicantEmail"),
                (String) runtimeService.getVariable(piId, "applicantToken"),
                (String) runtimeService.getVariable(piId, "companyName"),
                (SpinJsonNode) runtimeService.getVariable(piId, "additionalFounders"),
                ensureMap((SpinJsonNode) runtimeService.getVariable(piId, "founderSignatures")),
                (Boolean) runtimeService.getVariable(piId, "rejectedByFounder"),
                (Boolean) runtimeService.getVariable(piId, "sentToRegister"));
    }

    private static SpinJsonNode ensureMap(SpinJsonNode in) {
        return in != null ? in : Spin.JSON("{}");
    }

    private static boolean hasStatus(SpinJsonNode signatures, String token) {
        return signatures != null && signatures.hasProp(token);
    }

    /** Mutates {@code signatures} in place — caller must {@code setVariable} after. */
    private static void writeSignature(SpinJsonNode signatures, String token,
                                       String decision, String reason) {
        SpinJsonNode entry = Spin.JSON("{}");
        entry.prop("status", "approve".equals(decision) ? "approved" : "rejected");
        entry.prop("signedAt", Instant.now().toString());
        if (reason != null && !reason.isBlank()) {
            entry.prop("reason", reason.trim());
        }
        signatures.prop(token, entry);
    }

    private FounderStatus buildStatus(TokenLookup lookup, String requestToken) {
        ProcessVars vars = readVars(lookup.processInstanceId());

        String applicantName = (Objects.toString(vars.applicantFirstName(), "") + " "
                + Objects.toString(vars.applicantLastName(), "")).trim();

        List<FounderEntry> founders = new ArrayList<>();
        founders.add(buildFounderEntry(applicantName, vars.applicantEmail(),
                vars.applicantToken(), true, vars.founderSignatures()));
        if (vars.additionalFounders() != null) {
            for (SpinJsonNode founder : vars.additionalFounders().elements()) {
                founders.add(buildFounderEntry(
                        founder.prop("name").stringValue(),
                        founder.prop("email").stringValue(),
                        founder.prop("token").stringValue(),
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
                                                  boolean isApplicant, SpinJsonNode signatures) {
        String status = "pending";
        String signedAt = null;
        String reason = null;
        if (signatures != null && token != null && signatures.hasProp(token)) {
            SpinJsonNode entry = signatures.prop(token);
            if (entry.hasProp("status")) status = entry.prop("status").stringValue();
            if (entry.hasProp("signedAt")) signedAt = entry.prop("signedAt").stringValue();
            if (entry.hasProp("reason")) reason = entry.prop("reason").stringValue();
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
            SpinJsonNode additionalFounders,
            SpinJsonNode founderSignatures,
            Boolean rejectedByFounder,
            Boolean sentToRegister) {}
}
