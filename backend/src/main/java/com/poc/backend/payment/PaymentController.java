package com.poc.backend.payment;

import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.poc.backend.engine.EngineClient;
import com.poc.backend.engine.EngineClient.ProcessInstanceRef;

/**
 * Public REST surface for the shared state-fee payment step used by
 * {@code vehicle-registration.bpmn} (vehicle registration) and
 * {@code business-registration.bpmn} (OÜ registration).
 *
 * <p>Endpoints under {@code /api/public/payments/**} are unauthenticated
 * (see {@link com.poc.backend.security.SecurityConfig} — the matcher
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
 *   vehicleRegistration  → €25 / €75 / €150 tiered by vehicle value
 *                          (mirrors the fee tiers rendered in the
 *                          state-fee-invoice PDF template)
 * </pre>
 */
@RestController
@RequestMapping("/api/public/payments")
public class PaymentController {

    private static final String VEHICLE_KEY = "vehicleRegistration";
    private static final String OU_KEY = "businessRegistration";

    private static final String VEHICLE_IBAN = "EE89 3300 3334 1110 3007";
    private static final String OU_IBAN = "EE76 1010 2200 2401 4115";

    private final EngineClient engine;

    public PaymentController(EngineClient engine) {
        this.engine = engine;
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

        boolean correlated = engine.correlateMessage("PaymentReceived", piId,
                Map.of(),
                Map.of("paymentReceived", true));
        if (!correlated) {
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
        ProcessInstanceRef pi = engine.findActiveById(piId);
        if (pi == null) return null;

        Boolean alreadyPaid = engine.getBooleanVariable(piId, "paymentReceived");

        if (VEHICLE_KEY.equals(pi.definitionKey())) {
            String firstName = engine.getStringVariable(piId, "firstName");
            String lastName = engine.getStringVariable(piId, "lastName");
            String payerName = ((Objects.toString(firstName, "") + " "
                    + Objects.toString(lastName, "")).trim());
            String item = formatVehicleItem(piId);
            double amount = vehicleFee(piId);
            return new PaymentContext(piId, pi.definitionKey(), payerName, item,
                    amount, "Transpordiamet",
                    VEHICLE_IBAN, alreadyPaid);
        }
        if (OU_KEY.equals(pi.definitionKey())) {
            String firstName = engine.getStringVariable(piId, "applicantFirstName");
            String lastName = engine.getStringVariable(piId, "applicantLastName");
            String payerName = ((Objects.toString(firstName, "") + " "
                    + Objects.toString(lastName, "")).trim());
            String company = engine.getStringVariable(piId, "companyName");
            return new PaymentContext(piId, pi.definitionKey(), payerName,
                    Objects.toString(company, "OÜ registration"),
                    265.0, "Äriregister (Justiitsministeerium)",
                    OU_IBAN, alreadyPaid);
        }
        return null;
    }

    /** Tiered vehicle fee — mirrors approval-pdf.json.ftl's if/elseif/else. */
    private double vehicleFee(String piId) {
        Object raw = engine.getRawVariable(piId, "price");
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
                    .replace(" ", "").replace("$", "").replace("€", "");
            try {
                return Double.parseDouble(stripped);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }

    private String formatVehicleItem(String piId) {
        String make = engine.getStringVariable(piId, "vehicleMake");
        String model = engine.getStringVariable(piId, "vehicleModel");
        Object year = engine.getRawVariable(piId, "vehicleYear");
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
