package com.poc.cib7.payment;

import java.util.Objects;

import org.cibseven.bpm.engine.MismatchingMessageCorrelationException;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.repository.ProcessDefinition;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public REST surface for the shared state-fee payment step used by
 * {@code person-registration.bpmn} (vehicle registration) and
 * {@code business-registration.bpmn} (OÜ registration).
 *
 * <p>Endpoints under {@code /api/public/payments/**} are unauthenticated
 * (see {@link com.poc.cib7.owner.PublicApiSecurityConfig} — the matcher
 * already opens {@code /api/public/**}). The process instance id in the
 * URL is opaque enough for the POC; production would add a payment-side
 * token or session.
 *
 * <p>Both flows park on a {@code receiveTask} waiting for the
 * {@code PaymentReceived} message. {@link #confirm(String)} correlates
 * by process instance id, so only one waiter is unblocked. Status is
 * derived live from the process instance state — no separate payment
 * record table.
 *
 * <p>Fee schedule:
 * <pre>
 *   businessRegistration → €265 flat (Estonian fast-track OÜ fee)
 *   personRegistration   → €25 / €75 / €150 tiered by vehicle value
 *                          (mirrors the fee tiers rendered in the
 *                          state-fee-invoice PDF template)
 * </pre>
 */
@RestController
@RequestMapping("/api/public/payments")
public class PaymentController {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentController.class);

    private static final String VEHICLE_KEY = "personRegistration";
    private static final String OU_KEY = "businessRegistration";

    private static final String VEHICLE_IBAN = "EE89 3300 3334 1110 3007";
    private static final String OU_IBAN = "EE76 1010 2200 2401 4115";

    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;

    public PaymentController(RuntimeService runtimeService, RepositoryService repositoryService) {
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
    }

    @GetMapping("/{piId}/status")
    public ResponseEntity<?> getStatus(@PathVariable String piId) {
        PaymentContext ctx = resolve(piId);
        if (ctx == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("unknown_case",
                            "No active case found for this payment reference."));
        }
        return ResponseEntity.ok(buildStatus(ctx));
    }

    @PostMapping("/{piId}/confirm")
    public ResponseEntity<?> confirm(@PathVariable String piId) {
        PaymentContext ctx = resolve(piId);
        if (ctx == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("unknown_case",
                            "No active case found for this payment reference."));
        }
        if (Boolean.TRUE.equals(ctx.alreadyPaid())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("already_paid",
                            "This state fee has already been paid."));
        }

        try {
            runtimeService.createMessageCorrelation("PaymentReceived")
                    .processInstanceId(piId)
                    .setVariable("paymentReceived", true)
                    .correlate();
        } catch (MismatchingMessageCorrelationException e) {
            LOG.warn("PaymentReceived had no waiting receive task for PI {}: {}",
                    piId, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("not_waiting",
                            "This case is not waiting on a payment confirmation."));
        }

        // Re-resolve so the status response reflects paymentReceived=true.
        PaymentContext after = resolve(piId);
        return ResponseEntity.ok(buildStatus(after != null ? after : ctx));
    }

    // --- helpers ---------------------------------------------------------

    /**
     * Loads everything we need about a PI in one place. Returns null if no
     * active instance matches the id.
     */
    private PaymentContext resolve(String piId) {
        if (piId == null || piId.isBlank()) return null;
        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(piId)
                .active()
                .singleResult();
        if (pi == null) return null;

        ProcessDefinition def = repositoryService.getProcessDefinition(pi.getProcessDefinitionId());
        String key = def != null ? def.getKey() : "";

        Boolean alreadyPaid = (Boolean) runtimeService.getVariable(piId, "paymentReceived");

        if (VEHICLE_KEY.equals(key)) {
            String firstName = (String) runtimeService.getVariable(piId, "firstName");
            String lastName = (String) runtimeService.getVariable(piId, "lastName");
            String payerName = ((Objects.toString(firstName, "") + " "
                    + Objects.toString(lastName, "")).trim());
            String item = formatVehicleItem(piId);
            double amount = vehicleFee(piId);
            return new PaymentContext(piId, key, payerName, item,
                    amount, "Transpordiamet",
                    VEHICLE_IBAN, alreadyPaid);
        }
        if (OU_KEY.equals(key)) {
            String firstName = (String) runtimeService.getVariable(piId, "applicantFirstName");
            String lastName = (String) runtimeService.getVariable(piId, "applicantLastName");
            String payerName = ((Objects.toString(firstName, "") + " "
                    + Objects.toString(lastName, "")).trim());
            String company = (String) runtimeService.getVariable(piId, "companyName");
            return new PaymentContext(piId, key, payerName,
                    Objects.toString(company, "OÜ registration"),
                    265.0, "Äriregister (Justiitsministeerium)",
                    OU_IBAN, alreadyPaid);
        }
        return null;
    }

    /** Tiered vehicle fee — mirrors approval-pdf.json.ftl's if/elseif/else. */
    private double vehicleFee(String piId) {
        Object raw = runtimeService.getVariable(piId, "price");
        double value = parseAmount(raw);
        if (value < 5000) return 25.0;
        if (value < 20000) return 75.0;
        return 150.0;
    }

    /**
     * Coerces a process variable that should be numeric into a double. Some
     * engine→JUEL paths surface what should be a Double as a locale-
     * formatted String like "38,000" — same defensive pattern as the
     * FreeMarker numeric-vars memory entry.
     */
    private static double parseAmount(Object raw) {
        if (raw instanceof Number n) return n.doubleValue();
        if (raw instanceof String s) {
            String stripped = s.replace(",", "").replace(" ", "")
                    .replace(" ", "").replace("$", "").replace("€", "");
            try {
                return Double.parseDouble(stripped);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }

    private String formatVehicleItem(String piId) {
        String make = (String) runtimeService.getVariable(piId, "vehicleMake");
        String model = (String) runtimeService.getVariable(piId, "vehicleModel");
        Object year = runtimeService.getVariable(piId, "vehicleYear");
        StringBuilder sb = new StringBuilder();
        if (make != null) sb.append(make);
        if (model != null) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(model);
        }
        if (year != null) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(year);
        }
        return sb.length() > 0 ? sb.toString() : "Vehicle registration";
    }

    private PaymentStatus buildStatus(PaymentContext ctx) {
        return new PaymentStatus(
                ctx.piId(),
                ctx.processDefinitionKey(),
                ctx.payerName(),
                ctx.item(),
                ctx.amount(),
                "EUR",
                ctx.recipient(),
                ctx.iban(),
                ctx.piId(), // reference number = PI id
                Boolean.TRUE.equals(ctx.alreadyPaid()) ? "paid" : "pending");
    }

    // --- DTOs ------------------------------------------------------------

    public record PaymentStatus(
            String processInstanceId,
            String processDefinitionKey,
            String payerName,
            String item,
            double amount,
            String currency,
            String recipient,
            String iban,
            String reference,
            String status) {}

    public record ErrorResponse(String code, String message) {}

    private record PaymentContext(
            String piId,
            String processDefinitionKey,
            String payerName,
            String item,
            double amount,
            String recipient,
            String iban,
            Boolean alreadyPaid) {}
}
