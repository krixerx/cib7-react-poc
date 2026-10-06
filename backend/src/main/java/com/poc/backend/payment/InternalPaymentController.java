package com.poc.backend.payment;

import com.poc.backend.engine.EngineClient;
import com.poc.backend.engine.EngineClient.ProcessInstanceRef;
import com.poc.backend.payment.FeeSchedule.Charge;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The state fee of a case, for the engine only (through the ESB, {@code X-Internal-Token} on {@code
 * /api/internal/**}). A process asks for it before it renders the invoice, so the invoice prints
 * the amount the checkout will charge: both come from {@link FeeSchedule}, the one place a fee is
 * computed.
 */
@RestController
@RequestMapping("/api/internal/payments")
public class InternalPaymentController {

  private final EngineClient engine;
  private final FeeSchedule fees;

  public InternalPaymentController(EngineClient engine, FeeSchedule fees) {
    this.engine = engine;
    this.fees = fees;
  }

  /** What the fee is for, who receives it, how much, in which currency. */
  public record Quote(String service, String recipient, BigDecimal amount, String currency) {}

  @GetMapping("/quote/{processInstanceId}")
  public ResponseEntity<?> quote(@PathVariable String processInstanceId) {
    ProcessInstanceRef instance = engine.findActiveById(processInstanceId);
    if (instance == null) {
      return notFound("No active case " + processInstanceId + ".");
    }
    return fees.chargeFor(instance)
        .<ResponseEntity<?>>map(c -> ResponseEntity.ok(quoteOf(c)))
        .orElseGet(() -> notFound("Process " + instance.definitionKey() + " charges no fee."));
  }

  private static Quote quoteOf(Charge charge) {
    return new Quote(charge.serviceName(), charge.recipient(), charge.amount(), charge.currency());
  }

  private static ResponseEntity<?> notFound(String message) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new PaymentController.ErrorResponse("not_found", message));
  }
}
