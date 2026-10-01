package com.poc.backend.payment.mockprovider;

import com.poc.backend.payment.PaymentProvider;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The provider's side of the demo payment: holds the payments merchants registered and, when the
 * payer decides on the demo bank page, signs the outcome and delivers it to the merchant's
 * callback. See the package documentation for what replaces it in production.
 */
@Component
public class MockPaymentProvider implements PaymentProvider {

  private static final String HMAC = "HmacSHA256";
  private static final String OPEN = "OPEN";

  /** A payment as the provider records it. */
  public record ProviderPayment(
      String sessionId,
      String reference,
      BigDecimal amount,
      String currency,
      String merchantName,
      String returnPath,
      String status) {

    ProviderPayment withStatus(String next) {
      return new ProviderPayment(
          sessionId, reference, amount, currency, merchantName, returnPath, next);
    }
  }

  private final Map<String, ProviderPayment> payments = new ConcurrentHashMap<>();
  private final CallbackSender sender;
  private final byte[] secret;
  private final ObjectMapper mapper = new ObjectMapper();

  public MockPaymentProvider(
      CallbackSender sender, @Value("${app.payment.provider-secret}") String secret) {
    this.sender = sender;
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public String startCheckout(
      String sessionId,
      String reference,
      BigDecimal amount,
      String currency,
      String merchantName,
      String returnPath) {
    payments.compute(
        sessionId,
        (id, existing) ->
            existing != null && !OPEN.equals(existing.status())
                ? existing
                : new ProviderPayment(
                    id, reference, amount, currency, merchantName, returnPath, OPEN));
    return "/mock-bank/" + sessionId;
  }

  public Optional<ProviderPayment> find(String sessionId) {
    return Optional.ofNullable(payments.get(sessionId));
  }

  /**
   * Settles an open payment as {@code PAID} or {@code CANCELLED} and tells the merchant. The
   * provider's own record changes only when the merchant accepted the callback, so a failed
   * delivery can be retried from the page.
   */
  public Optional<ProviderPayment> settle(String sessionId, String outcome) {
    ProviderPayment payment = payments.get(sessionId);
    if (payment == null) {
      return Optional.empty();
    }
    if (!OPEN.equals(payment.status())) {
      return Optional.of(payment);
    }
    Map<String, Object> callback = new LinkedHashMap<>();
    callback.put("sessionId", payment.sessionId());
    callback.put("reference", payment.reference());
    callback.put("amount", payment.amount().setScale(2, RoundingMode.HALF_UP).toPlainString());
    callback.put("currency", payment.currency());
    callback.put("status", outcome);
    callback.put("paidAt", "PAID".equals(outcome) ? Instant.now().toString() : null);
    byte[] body = mapper.writeValueAsBytes(callback);
    if (!sender.send(body, sign(body))) {
      throw new CallbackFailedException();
    }
    ProviderPayment settled = payment.withStatus(outcome);
    payments.put(sessionId, settled);
    return Optional.of(settled);
  }

  /** Hex HMAC-SHA256 of the exact body bytes, sent as {@code X-Provider-Signature}. */
  String sign(byte[] body) {
    try {
      Mac mac = Mac.getInstance(HMAC);
      mac.init(new SecretKeySpec(secret, HMAC));
      return HexFormat.of().formatHex(mac.doFinal(body));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }

  /** The merchant did not accept the callback. */
  public static class CallbackFailedException extends RuntimeException {
    CallbackFailedException() {
      super("merchant callback was not accepted");
    }
  }
}
