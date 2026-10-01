package com.poc.backend.links;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mints tokens in the engine's format for backend tests. {@code CapabilityLinkVerifierTest} pins
 * this format to the engine's own output through the shared test vector.
 */
public final class TestLinks {

  public static final String SECRET = "test-link-secret";

  private TestLinks() {}

  public static String mint(
      String secret, String pi, String partyId, String purpose, long round, long expiresAt) {
    try {
      String payload = pi + "|" + partyId + "|" + purpose + "|" + round + "|" + expiresAt;
      Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
      String encoded = b64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return encoded
          + "."
          + b64.encodeToString(mac.doFinal(encoded.getBytes(StandardCharsets.US_ASCII)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** A token valid for an hour, signed with {@link #SECRET}. */
  public static String mint(String pi, String partyId, String purpose, long round) {
    return mint(
        SECRET, pi, partyId, purpose, round, java.time.Instant.now().getEpochSecond() + 3600);
  }
}
