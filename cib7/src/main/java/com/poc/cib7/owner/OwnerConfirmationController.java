package com.poc.cib7.owner;

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
 * Public REST surface for the owner-confirmation flow in
 * {@code person-registration.bpmn}.
 *
 * <p>Endpoints under {@code /api/public/**} are unauthenticated (see
 * {@link PublicApiSecurityConfig}). Authorization is by knowledge of the
 * per-owner UUID token: the applicant's form generates a token for the
 * applicant and one for each additional owner, the engine stores them as
 * process variables, and the controller correlates back to the waiting
 * receive task in the multi-instance subprocess via
 * {@link org.cibseven.bpm.engine.runtime.MessageCorrelationBuilder#localVariableEquals(String, Object)}.
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
 * scans active {@code personRegistration} instances and matches against
 * {@code applicantToken} / {@code additionalOwners[].token}. Move to a
 * dedicated index table if the case load ever grows beyond demo scale.
 */
@RestController
@RequestMapping("/api/public/owner-confirmations")
public class OwnerConfirmationController {

    private static final Logger LOG = LoggerFactory.getLogger(OwnerConfirmationController.class);

    private static final String PROCESS_KEY = "personRegistration";

    private final RuntimeService runtimeService;

    public OwnerConfirmationController(RuntimeService runtimeService) {
        this.runtimeService = runtimeService;
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
        SpinJsonNode confirmations = vars.ownerConfirmations();
        if (hasStatus(confirmations, token)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("already_signed",
                            "You have already signed this application."));
        }

        // Persist the per-owner outcome BEFORE message correlation so the
        // status endpoint reflects it even if a sibling instance fires the
        // completion condition immediately after.
        writeConfirmation(confirmations, token, decision, req.reason());
        runtimeService.setVariable(lookup.processInstanceId(), "ownerConfirmations", confirmations);

        if ("reject".equals(decision)) {
            // Process-scope flag drives both the multi-instance completionCondition
            // and the post-subprocess gateway. setVariable on the PI puts it at
            // process scope; the correlate's localVariableEquals on the
            // receiveTask just identifies WHICH instance is being unblocked.
            runtimeService.setVariable(lookup.processInstanceId(), "rejectedByOwner", true);
            runtimeService.setVariable(lookup.processInstanceId(), "sendBackReason",
                    "Owner " + lookup.ownerName() + " rejected the application: " + req.reason().trim());
        }

        try {
            runtimeService.createMessageCorrelation("OwnerConfirmation")
                    .processInstanceId(lookup.processInstanceId())
                    .localVariableEquals("ownerToken", token)
                    .correlate();
        } catch (MismatchingMessageCorrelationException e) {
            LOG.warn("OwnerConfirmation message had no waiting receive task for token {}: {}",
                    token, e.getMessage());
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

        try {
            runtimeService.createMessageCorrelation("SendToProcess")
                    .processInstanceId(lookup.processInstanceId())
                    .setVariable("sentToProcess", true)
                    .correlate();
        } catch (MismatchingMessageCorrelationException e) {
            LOG.warn("SendToProcess had no waiting receive task for PI {}: {}",
                    lookup.processInstanceId(), e.getMessage());
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

        // Slow path: scan additionalOwners. CIB seven doesn't index inside
        // Spin Json lists, so we read each variable and walk it.
        List<ProcessInstance> all = runtimeService.createProcessInstanceQuery()
                .processDefinitionKey(PROCESS_KEY)
                .active()
                .list();
        for (ProcessInstance pi : all) {
            Object raw = runtimeService.getVariable(pi.getId(), "additionalOwners");
            if (!(raw instanceof SpinJsonNode)) continue;
            for (SpinJsonNode owner : ((SpinJsonNode) raw).elements()) {
                if (token.equals(owner.prop("token").stringValue())) {
                    return new TokenLookup(pi.getId(), owner.prop("name").stringValue(), false);
                }
            }
        }
        return null;
    }

    private String applicantName(String piId) {
        String first = (String) runtimeService.getVariable(piId, "firstName");
        String last = (String) runtimeService.getVariable(piId, "lastName");
        return ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
    }

    private ProcessVars readVars(String piId) {
        return new ProcessVars(
                (String) runtimeService.getVariable(piId, "firstName"),
                (String) runtimeService.getVariable(piId, "lastName"),
                (String) runtimeService.getVariable(piId, "applicantEmail"),
                (String) runtimeService.getVariable(piId, "applicantToken"),
                (SpinJsonNode) runtimeService.getVariable(piId, "additionalOwners"),
                ensureMap((SpinJsonNode) runtimeService.getVariable(piId, "ownerConfirmations")),
                (Boolean) runtimeService.getVariable(piId, "rejectedByOwner"),
                (Boolean) runtimeService.getVariable(piId, "sentToProcess"));
    }

    private static SpinJsonNode ensureMap(SpinJsonNode in) {
        return in != null ? in : Spin.JSON("{}");
    }

    private static boolean hasStatus(SpinJsonNode confirmations, String token) {
        return confirmations != null && confirmations.hasProp(token);
    }

    /** Mutates {@code confirmations} in place — caller must {@code setVariable} after. */
    private static void writeConfirmation(SpinJsonNode confirmations, String token,
                                          String decision, String reason) {
        SpinJsonNode entry = Spin.JSON("{}");
        entry.prop("status", "approve".equals(decision) ? "approved" : "rejected");
        entry.prop("signedAt", Instant.now().toString());
        if (reason != null && !reason.isBlank()) {
            entry.prop("reason", reason.trim());
        }
        confirmations.prop(token, entry);
    }

    private OwnerStatus buildStatus(TokenLookup lookup, String requestToken) {
        ProcessVars vars = readVars(lookup.processInstanceId());

        String applicantName = (Objects.toString(vars.firstName(), "") + " "
                + Objects.toString(vars.lastName(), "")).trim();

        List<OwnerEntry> owners = new ArrayList<>();
        owners.add(buildOwnerEntry(applicantName, vars.applicantEmail(),
                vars.applicantToken(), true, vars.ownerConfirmations()));
        if (vars.additionalOwners() != null) {
            for (SpinJsonNode owner : vars.additionalOwners().elements()) {
                owners.add(buildOwnerEntry(
                        owner.prop("name").stringValue(),
                        owner.prop("email").stringValue(),
                        owner.prop("token").stringValue(),
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
                                              boolean isApplicant, SpinJsonNode confirmations) {
        String status = "pending";
        String signedAt = null;
        String reason = null;
        if (confirmations != null && token != null && confirmations.hasProp(token)) {
            SpinJsonNode entry = confirmations.prop(token);
            if (entry.hasProp("status")) status = entry.prop("status").stringValue();
            if (entry.hasProp("signedAt")) signedAt = entry.prop("signedAt").stringValue();
            if (entry.hasProp("reason")) reason = entry.prop("reason").stringValue();
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
            SpinJsonNode additionalOwners,
            SpinJsonNode ownerConfirmations,
            Boolean rejectedByOwner,
            Boolean sentToProcess) {}
}
