package com.poc.backend.payment;

import com.poc.backend.engine.EngineClient;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The payment provider's server-to-server callback: the only way a fee becomes paid
 * (docs/security.md rule 4).
 *
 * <p>Public because a provider calls it from outside, so the proof is in the request: {@value
 * #SIGNATURE_HEADER} is the hex HMAC-SHA256 of the raw body under {@code PAYMENT_PROVIDER_SECRET},
 * compared in constant time before the body is even parsed. The reported amount and currency must
 * equal what the {@link PaymentSession} charged. A repeated PAID callback for a paid session is a
 * no-op, so provider retries are safe.
 *
 * <p>Only then is {@code PaymentReceived} correlated, with {@code paymentReceived}, {@code
 * paymentReference} and {@code paidAmount}.
 */
@RestController
@RequestMapping("/api/public/payments")
public class PaymentCallbackController {

  public static final String SIGNATURE_HEADER = "X-Provider-Signature";

  private static final Logger log = LoggerFactory.getLogger(PaymentCallbackController.class);
  private static final String HMAC = "HmacSHA256";

  private final PaymentSessionRepository sessions;
  private final EngineClient engine;
  private final byte[] secret;
  private final ObjectMapper mapper = new ObjectMapper();

  public PaymentCallbackController(
      PaymentSessionRepository sessions,
      EngineClient engine,
      @Value("${app.payment.provider-secret}") String secret) {
    if (secret == null || secret.isEmpty()) {
      throw new IllegalStateException(
          "app.payment.provider-secret (PAYMENT_PROVIDER_SECRET) must be set");
    }
    this.sessions = sessions;
    this.engine = engine;
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
  }

  @PostMapping(path = "/callback", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Transactional
  public ResponseEntity<?> callback(
      @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
      @RequestBody byte[] body) {
    if (!signatureValid(signature, body)) {
      log.warn("Payment callback refused: invalid signature");
      return error(HttpStatus.UNAUTHORIZED, "invalid_signature", "Signature check failed.");
    }
    JsonNode payload;
    try {
      payload = mapper.readTree(body);
    } catch (JacksonException e) {
      return error(HttpStatus.BAD_REQUEST, "bad_request", "Body is not JSON.");
    }
    Optional<PaymentSession> found = sessions.findById(payload.path("sessionId").asText(""));
    if (found.isEmpty()) {
      return error(HttpStatus.NOT_FOUND, "unknown_session", "Unknown payment session.");
    }
    PaymentSession session = found.get();
    if (!session.getReference().equals(payload.path("reference").asText(""))
        || !amountMatches(session, payload)) {
      log.warn(
          "Payment callback refused for session {}: amount or reference differs", session.getId());
      return error(
          HttpStatus.BAD_REQUEST,
          "amount_mismatch",
          "Reference, amount or currency differ from the payment session.");
    }

    String outcome = payload.path("status").asText("");
    if ("CANCELLED".equals(outcome)) {
      if (session.getStatus() == PaymentSession.Status.PENDING) {
        session.markCancelled();
        sessions.saveAndFlush(session);
      }
      return ResponseEntity.ok(new CallbackResult(session.getStatus().name()));
    }
    if (!"PAID".equals(outcome)) {
      return error(HttpStatus.BAD_REQUEST, "bad_request", "Unknown payment status.");
    }
    if (session.getStatus() == PaymentSession.Status.PAID) {
      return ResponseEntity.ok(new CallbackResult("PAID"));
    }
    if (session.getStatus() != PaymentSession.Status.PENDING) {
      return error(HttpStatus.CONFLICT, "not_pending", "The payment session is not pending.");
    }

    // Flush first: a concurrent duplicate callback fails on the version check here instead of
    // correlating the payment twice.
    session.markPaid(Instant.now());
    sessions.saveAndFlush(session);
    boolean correlated =
        engine.correlateMessage(
            "PaymentReceived",
            session.getProcessInstanceId(),
            Map.of(),
            Map.of(
                "paymentReceived", true,
                "paymentReference", session.getReference(),
                "paidAmount", session.getAmount().doubleValue()));
    if (!correlated) {
      // The money is taken either way; record it and leave the case for an operator.
      log.warn(
          "Payment {} recorded but case {} was not waiting for PaymentReceived",
          session.getId(),
          session.getProcessInstanceId());
    }
    return ResponseEntity.ok(new CallbackResult("PAID"));
  }

  private boolean signatureValid(String signature, byte[] body) {
    if (signature == null || signature.isBlank()) {
      return false;
    }
    byte[] provided;
    try {
      provided = HexFormat.of().parseHex(signature.trim());
    } catch (IllegalArgumentException e) {
      return false;
    }
    try {
      Mac mac = Mac.getInstance(HMAC);
      mac.init(new SecretKeySpec(secret, HMAC));
      return MessageDigest.isEqual(mac.doFinal(body), provided);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }

  private static boolean amountMatches(PaymentSession session, JsonNode payload) {
    BigDecimal reported;
    try {
      reported = new BigDecimal(payload.path("amount").asText(""));
    } catch (NumberFormatException e) {
      return false;
    }
    return reported.compareTo(session.getAmount()) == 0
        && session.getCurrency().equals(payload.path("currency").asText(""));
  }

  private static ResponseEntity<?> error(HttpStatus status, String code, String message) {
    return ResponseEntity.status(status).body(new PaymentController.ErrorResponse(code, message));
  }

  public record CallbackResult(String status) {}
}
