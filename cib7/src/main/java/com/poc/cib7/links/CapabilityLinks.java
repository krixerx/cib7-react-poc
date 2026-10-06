package com.poc.cib7.links;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Mints the capability tokens that email templates put into public links (docs/security.md rule 3):
 * {@code ${frontendBaseUrl}/consent/owner/${links.consent(execution, "owner", partyId)}}.
 *
 * <p>A token is never stored. It is {@code base64url(payload) + "." + base64url(hmac)} where the
 * payload is {@code processInstanceId|partyId|purpose|round|expiresAtEpochSeconds} and the HMAC is
 * SHA-256 over the encoded payload segment, keyed with {@code LINK_SIGNING_SECRET}. The backend's
 * {@code CapabilityLinkVerifier} checks the same format; both modules carry the same fixed test
 * vector so a format change in one fails the other's build.
 *
 * <p>Consent tokens carry the case's current {@code consentRound}, which {@link
 * com.poc.cib7.consent.ConsentPartiesListener} replaces on every (re)submission, so a resubmission
 * invalidates every earlier link. Payment tokens carry round 0: the payment is not re-issued per
 * round, and the session state in the backend decides whether it is still payable.
 *
 * <p>Registered under the bean name {@code links} in {@link com.poc.cib7.ReservedBeansPlugin}, so a
 * process variable called {@code links} cannot replace it.
 */
@Component("links")
public class CapabilityLinks {

  private static final java.util.regex.Pattern CONSENT_PURPOSE =
      java.util.regex.Pattern.compile("^[a-z][a-z0-9-]{0,62}$");
  public static final String PURPOSE_PAYMENT = "payment";
  public static final String APPLICANT_PARTY = "applicant";
  public static final String ROUND_VARIABLE = "consentRound";

  private static final Pattern SAFE_FIELD = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final String HMAC = "HmacSHA256";

  private final byte[] secret;
  private final Duration consentTtl;
  private final Duration paymentTtl;
  private final Clock clock;

  @Autowired
  public CapabilityLinks(
      @Value("${app.links.secret}") String secret,
      @Value("${app.links.consent-ttl:P14D}") Duration consentTtl,
      @Value("${app.links.payment-ttl:P30D}") Duration paymentTtl) {
    this(secret, consentTtl, paymentTtl, Clock.systemUTC());
  }

  public CapabilityLinks(String secret, Duration consentTtl, Duration paymentTtl, Clock clock) {
    if (secret == null || secret.isEmpty()) {
      throw new IllegalStateException("app.links.secret (LINK_SIGNING_SECRET) must be set");
    }
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
    this.consentTtl = consentTtl;
    this.paymentTtl = paymentTtl;
    this.clock = clock;
  }

  /** State-fee payment link token for the case's applicant. */
  public String payment(DelegateExecution execution) {
    long expiresAt = clock.instant().plus(paymentTtl).getEpochSecond();
    return mint(execution.getProcessInstanceId(), APPLICANT_PARTY, PURPOSE_PAYMENT, 0L, expiresAt);
  }

  /**
   * Co-signing link token for {@code partyId} in the current consent round. {@code purpose} is a
   * consent purpose the pack declares ({@code backend/consent/<purpose>.yaml}); the backend refuses
   * a token whose purpose it does not serve, so the engine only checks the shape.
   */
  public String consent(DelegateExecution execution, String purpose, String partyId) {
    if (purpose == null
        || !CONSENT_PURPOSE.matcher(purpose).matches()
        || PURPOSE_PAYMENT.equals(purpose)) {
      throw new IllegalArgumentException("Not a consent purpose: " + purpose);
    }
    Object round = execution.getVariable(ROUND_VARIABLE);
    if (!(round instanceof Number number)) {
      throw new IllegalStateException(
          "consentRound is not set; ConsentPartiesListener must run before links are minted");
    }
    long expiresAt = clock.instant().plus(consentTtl).getEpochSecond();
    return mint(execution.getProcessInstanceId(), partyId, purpose, number.longValue(), expiresAt);
  }

  /** Builds and signs one token. Public for the cross-module test vector. */
  public String mint(
      String processInstanceId, String partyId, String purpose, long round, long expiresAt) {
    requireSafe("processInstanceId", processInstanceId);
    requireSafe("partyId", partyId);
    requireSafe("purpose", purpose);
    String payload =
        processInstanceId + "|" + partyId + "|" + purpose + "|" + round + "|" + expiresAt;
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    String encodedPayload = b64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    return encodedPayload + "." + b64.encodeToString(hmac(encodedPayload));
  }

  private byte[] hmac(String encodedPayload) {
    try {
      Mac mac = Mac.getInstance(HMAC);
      mac.init(new SecretKeySpec(secret, HMAC));
      return mac.doFinal(encodedPayload.getBytes(StandardCharsets.US_ASCII));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }

  private static void requireSafe(String field, String value) {
    if (value == null || !SAFE_FIELD.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " has an unexpected format");
    }
  }
}
