package com.poc.backend.links;

import com.poc.backend.engine.EngineClient;
import com.poc.backend.engine.EngineClient.ProcessInstanceRef;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Verifies the capability tokens the engine's {@code CapabilityLinks} bean signs into email links
 * (docs/security.md rule 3). The one place in the backend that knows the token format.
 *
 * <p>Token: {@code base64url(payload) + "." + base64url(HMAC-SHA256(secret, encodedPayload))},
 * payload {@code processInstanceId|partyId|purpose|round|expiresAtEpochSeconds}. The token carries
 * the case id, so lookup needs no scan over active instances, and nothing is stored to compare
 * against: a valid signature is the proof that the engine issued it.
 *
 * <p>The backend mints one kind of link itself: {@link #mintPayment}, for the signed-in applicant
 * who opens the payment page from the SPA instead of the email (the SPA never sees the email's
 * token). Same format, same secret.
 *
 * <p>Every failure (malformed, bad signature, expired, wrong purpose, ended case, wrong process,
 * earlier consent round) is reported the same way, as an empty result, so callers answer with one
 * 404 and a caller learns nothing about which check failed.
 */
@Component
public class CapabilityLinkVerifier {

  /** Engine variable holding the current consent round; the engine replaces it on resubmission. */
  public static final String ROUND_VARIABLE = "consentRound";

  private static final String HMAC = "HmacSHA256";
  private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_-]{1,512}");
  private static final Pattern FIELD = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  private final byte[] secret;
  private final EngineClient engine;
  private final Clock clock;
  private Duration paymentTtl = Duration.ofDays(30);

  @Autowired
  public CapabilityLinkVerifier(
      @Value("${app.links.secret}") String secret,
      @Value("${app.links.payment-ttl:P30D}") Duration paymentTtl,
      EngineClient engine) {
    this(secret, engine, Clock.systemUTC());
    this.paymentTtl = paymentTtl;
  }

  public CapabilityLinkVerifier(String secret, EngineClient engine, Clock clock) {
    if (secret == null || secret.isEmpty()) {
      throw new IllegalStateException("app.links.secret (LINK_SIGNING_SECRET) must be set");
    }
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
    this.engine = engine;
    this.clock = clock;
  }

  /** A link that passed every check. */
  public record CapabilityLink(
      String processInstanceId, String partyId, String purpose, long round, long expiresAt) {}

  /**
   * Signature, expiry and purpose only — no engine call. Use {@link #verifyConsent} for consent
   * links; payment links are additionally checked against the backend's payment sessions.
   */
  public Optional<CapabilityLink> verifySignature(String token, String expectedPurpose) {
    if (token == null) {
      return Optional.empty();
    }
    int dot = token.indexOf('.');
    if (dot < 0 || dot != token.lastIndexOf('.')) {
      return Optional.empty();
    }
    String encodedPayload = token.substring(0, dot);
    String encodedSignature = token.substring(dot + 1);
    if (!SEGMENT.matcher(encodedPayload).matches()
        || !SEGMENT.matcher(encodedSignature).matches()) {
      return Optional.empty();
    }
    byte[] provided;
    try {
      provided = Base64.getUrlDecoder().decode(encodedSignature);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    if (!MessageDigest.isEqual(hmac(encodedPayload), provided)) {
      return Optional.empty();
    }
    CapabilityLink link = parsePayload(encodedPayload);
    if (link == null
        || !link.purpose().equals(expectedPurpose)
        || link.expiresAt() <= clock.instant().getEpochSecond()) {
      return Optional.empty();
    }
    return Optional.of(link);
  }

  /**
   * A consent link (co-owner confirmation, founder signature): valid signature and expiry, the
   * expected purpose, an active instance of {@code processKey}, and the instance's current consent
   * round. A resubmission replaces the round, so links from earlier rounds stop here.
   */
  public Optional<CapabilityLink> verifyConsent(
      String token, String expectedPurpose, String processKey) {
    Optional<CapabilityLink> verified = verifySignature(token, expectedPurpose);
    if (verified.isEmpty()) {
      return Optional.empty();
    }
    CapabilityLink link = verified.get();
    ProcessInstanceRef instance = engine.findActiveById(link.processInstanceId());
    if (instance == null || !processKey.equals(instance.definitionKey())) {
      return Optional.empty();
    }
    Object round = engine.getRawVariable(link.processInstanceId(), ROUND_VARIABLE);
    if (!(round instanceof Number current) || current.longValue() != link.round()) {
      return Optional.empty();
    }
    return verified;
  }

  /** A payment link for the case's applicant, as the engine puts into the approval email. */
  public String mintPayment(String processInstanceId) {
    if (!FIELD.matcher(processInstanceId).matches()) {
      throw new IllegalArgumentException("unexpected process instance id format");
    }
    long expiresAt = clock.instant().plus(paymentTtl).getEpochSecond();
    String payload = processInstanceId + "|applicant|payment|0|" + expiresAt;
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    String encoded = b64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    return encoded + "." + b64.encodeToString(hmac(encoded));
  }

  private static CapabilityLink parsePayload(String encodedPayload) {
    String payload;
    try {
      payload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return null;
    }
    String[] parts = payload.split("\\|", -1);
    if (parts.length != 5
        || !FIELD.matcher(parts[0]).matches()
        || !FIELD.matcher(parts[1]).matches()
        || !FIELD.matcher(parts[2]).matches()) {
      return null;
    }
    try {
      return new CapabilityLink(
          parts[0], parts[1], parts[2], Long.parseLong(parts[3]), Long.parseLong(parts[4]));
    } catch (NumberFormatException e) {
      return null;
    }
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
}
