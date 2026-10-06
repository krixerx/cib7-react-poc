package com.poc.backend.payment;

import com.poc.backend.engine.EngineClient;
import com.poc.backend.engine.EngineClient.ProcessInstanceRef;
import com.poc.backend.links.CapabilityLinkVerifier;
import com.poc.backend.links.CapabilityLinkVerifier.CapabilityLink;
import com.poc.backend.payment.FeeSchedule.Charge;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public payer-facing surface of the state-fee step shared by all four services.
 *
 * <p>The credential is the payment capability token in the pay link (purpose {@code payment}),
 * signed by the engine into the approval email; see {@link CapabilityLinkVerifier} and
 * docs/security.md rule 3. The browser can only ask to start paying: {@link #checkout} creates a
 * {@link PaymentSession} with a server-computed amount and hands the payer to the provider. Whether
 * the fee was paid is decided by the provider's signed callback ({@link PaymentCallbackController},
 * rule 4), never by this controller.
 *
 * <p>The status response holds only what the payer needs to recognise the bill: service, recipient,
 * amount and reference. No name, vehicle or VIN, since the link may be forwarded.
 */
@RestController
@RequestMapping("/api/public/payments")
public class PaymentController {

  private static final String PURPOSE = "payment";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final EngineClient engine;
  private final CapabilityLinkVerifier verifier;
  private final FeeSchedule fees;
  private final PaymentSessionRepository sessions;
  private final PaymentProvider provider;

  public PaymentController(
      EngineClient engine,
      CapabilityLinkVerifier verifier,
      FeeSchedule fees,
      PaymentSessionRepository sessions,
      PaymentProvider provider) {
    this.engine = engine;
    this.verifier = verifier;
    this.fees = fees;
    this.sessions = sessions;
    this.provider = provider;
  }

  @GetMapping("/{token}")
  public ResponseEntity<?> getStatus(@PathVariable String token) {
    Optional<CapabilityLink> link = verifier.verifySignature(token, PURPOSE);
    if (link.isEmpty()) {
      return unknown();
    }
    String pi = link.get().processInstanceId();
    Optional<PaymentSession> paid = paidSession(pi);
    Optional<Charge> charge = activeCharge(pi);
    if (charge.isPresent()) {
      boolean isPaid =
          paid.isPresent() || Boolean.TRUE.equals(engine.getBooleanVariable(pi, "paymentReceived"));
      return ResponseEntity.ok(status(charge.get(), reference(pi), isPaid));
    }
    // The case moves on (and may end) once paid; the session still answers for the link.
    return paid.<ResponseEntity<?>>map(
            s ->
                ResponseEntity.ok(
                    new PaymentStatus(
                        s.getProcessDefinitionKey(),
                        s.getServiceName(),
                        s.getRecipient(),
                        s.getAmount(),
                        s.getCurrency(),
                        s.getReference(),
                        "paid")))
        .orElseGet(PaymentController::unknown);
  }

  @PostMapping("/{token}/checkout")
  @Transactional
  public ResponseEntity<?> checkout(@PathVariable String token) {
    Optional<CapabilityLink> link = verifier.verifySignature(token, PURPOSE);
    if (link.isEmpty()) {
      return unknown();
    }
    String pi = link.get().processInstanceId();
    Optional<Charge> resolved = activeCharge(pi);
    if (resolved.isEmpty()) {
      return unknown();
    }
    if (paidSession(pi).isPresent()
        || Boolean.TRUE.equals(engine.getBooleanVariable(pi, "paymentReceived"))) {
      return error(HttpStatus.CONFLICT, "already_paid", "This fee has already been paid.");
    }
    Charge charge = resolved.get();
    if (charge.amount().signum() <= 0) {
      return error(HttpStatus.CONFLICT, "no_fee", "No fee is due for this case yet.");
    }
    PaymentSession session =
        sessions
            .findFirstByProcessInstanceIdAndStatusOrderByCreatedAtDesc(
                pi, PaymentSession.Status.PENDING.name())
            .filter(s -> s.getAmount().compareTo(charge.amount()) == 0)
            .filter(s -> s.getCurrency().equals(charge.currency()))
            .orElseGet(
                () ->
                    sessions.save(
                        new PaymentSession(
                            newSessionId(),
                            pi,
                            charge.processDefinitionKey(),
                            charge.serviceName(),
                            charge.recipient(),
                            charge.amount(),
                            charge.currency(),
                            reference(pi),
                            Instant.now())));
    String redirectUrl =
        provider.startCheckout(
            session.getId(),
            session.getReference(),
            session.getAmount(),
            session.getCurrency(),
            session.getRecipient(),
            "/pay/" + token);
    return ResponseEntity.ok(new CheckoutResponse(session.getId(), redirectUrl));
  }

  // --- helpers ---------------------------------------------------------

  private Optional<Charge> activeCharge(String pi) {
    ProcessInstanceRef instance = engine.findActiveById(pi);
    if (instance == null || !fees.isPayable(instance.definitionKey())) {
      return Optional.empty();
    }
    return fees.chargeFor(instance);
  }

  private Optional<PaymentSession> paidSession(String pi) {
    return sessions.findFirstByProcessInstanceIdAndStatusOrderByCreatedAtDesc(
        pi, PaymentSession.Status.PAID.name());
  }

  private static PaymentStatus status(Charge charge, String reference, boolean paid) {
    return new PaymentStatus(
        charge.processDefinitionKey(),
        charge.serviceName(),
        charge.recipient(),
        charge.amount(),
        charge.currency(),
        reference,
        paid ? "paid" : "pending");
  }

  /**
   * A stable, opaque payment reference for the case: derived from the instance id so every session
   * of one case shares it, but not reversible to the id.
   */
  static String reference(String processInstanceId) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(processInstanceId.getBytes(StandardCharsets.UTF_8));
      return "RF" + HexFormat.of().withUpperCase().formatHex(digest, 0, 6);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static String newSessionId() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static ResponseEntity<?> unknown() {
    return error(
        HttpStatus.NOT_FOUND, "unknown_link", "This payment link is unknown or has expired.");
  }

  private static ResponseEntity<?> error(HttpStatus status, String code, String message) {
    return ResponseEntity.status(status).body(new ErrorResponse(code, message));
  }

  // --- DTOs ------------------------------------------------------------

  public record PaymentStatus(
      String processDefinitionKey,
      String serviceName,
      String recipient,
      BigDecimal amount,
      String currency,
      String reference,
      String status) {}

  public record CheckoutResponse(String sessionId, String redirectUrl) {}

  public record ErrorResponse(String code, String message) {}
}
