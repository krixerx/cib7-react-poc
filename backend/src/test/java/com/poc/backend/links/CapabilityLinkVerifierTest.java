package com.poc.backend.links;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.poc.backend.engine.EngineClient;
import com.poc.backend.engine.EngineClient.ProcessInstanceRef;
import com.poc.backend.links.CapabilityLinkVerifier.CapabilityLink;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Token checks of docs/security.md rule 3. {@link #TEST_VECTOR} is the engine's own output,
 * repeated verbatim from cib7's {@code CapabilityLinksTest}: if either module changes the format,
 * one of the two builds fails.
 */
class CapabilityLinkVerifierTest {

  static final String SECRET = "test-vector-secret";
  static final String PI = "0f8fad5b-d9cb-469f-a165-70867728950e";

  /** owner link for party p1, round 7, expiring 2026-01-01T00:00:00Z (1767225600). */
  static final String TEST_VECTOR =
      "MGY4ZmFkNWItZDljYi00NjlmLWExNjUtNzA4Njc3Mjg5NTBlfHAxfG93bmVyfDd8MTc2NzIyNTYwMA"
          + ".h8zuf641sJqIleT8wN0KnPWRE3Srm69SxtMLQyeWd-A";

  private final EngineClient engine = mock(EngineClient.class);

  private CapabilityLinkVerifier verifierAt(String instant) {
    return new CapabilityLinkVerifier(
        SECRET, engine, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
  }

  private void activeVehicleCase(long round) {
    when(engine.findActiveById(PI)).thenReturn(new ProcessInstanceRef(PI, "vehicleRegistration"));
    when(engine.getRawVariable(PI, "consentRound")).thenReturn(round);
  }

  @Test
  void engineTestVectorVerifies() {
    activeVehicleCase(7L);

    CapabilityLink link =
        verifierAt("2025-12-20T00:00:00Z")
            .verifyConsent(TEST_VECTOR, "owner", "vehicleRegistration")
            .orElseThrow();

    assertThat(link.processInstanceId()).isEqualTo(PI);
    assertThat(link.partyId()).isEqualTo("p1");
    assertThat(link.round()).isEqualTo(7L);
    assertThat(link.expiresAt()).isEqualTo(1767225600L);
  }

  @Test
  void testHelperMintsTheEngineFormat() {
    assertThat(TestLinks.mint(SECRET, PI, "p1", "owner", 7, 1767225600L)).isEqualTo(TEST_VECTOR);
  }

  @Test
  void tamperedPayloadIsRejected() {
    activeVehicleCase(7L);
    // Same signature, payload re-encoded for party p2.
    String forged =
        TestLinks.mint(SECRET, PI, "p2", "owner", 7, 1767225600L).split("[.]")[0]
            + "."
            + TEST_VECTOR.split("[.]")[1];

    assertThat(verifierAt("2025-12-20T00:00:00Z").verifySignature(forged, "owner")).isEmpty();
  }

  @Test
  void tokenSignedWithAnotherSecretIsRejected() {
    String foreign = TestLinks.mint("someone-elses-secret", PI, "p1", "owner", 7, 1767225600L);

    assertThat(verifierAt("2025-12-20T00:00:00Z").verifySignature(foreign, "owner")).isEmpty();
  }

  @Test
  void expiredTokenIsRejected() {
    activeVehicleCase(7L);

    assertThat(
            verifierAt("2026-01-01T00:00:00Z")
                .verifyConsent(TEST_VECTOR, "owner", "vehicleRegistration"))
        .isEmpty();
  }

  @Test
  void earlierRoundIsRejected() {
    activeVehicleCase(8L);

    assertThat(
            verifierAt("2025-12-20T00:00:00Z")
                .verifyConsent(TEST_VECTOR, "owner", "vehicleRegistration"))
        .isEmpty();
  }

  @Test
  void wrongPurposeOrProcessIsRejected() {
    activeVehicleCase(7L);
    CapabilityLinkVerifier verifier = verifierAt("2025-12-20T00:00:00Z");

    assertThat(verifier.verifySignature(TEST_VECTOR, "payment")).isEmpty();
    assertThat(verifier.verifyConsent(TEST_VECTOR, "owner", "businessRegistration")).isEmpty();
  }

  @Test
  void endedCaseIsRejected() {
    when(engine.findActiveById(PI)).thenReturn(null);

    assertThat(
            verifierAt("2025-12-20T00:00:00Z")
                .verifyConsent(TEST_VECTOR, "owner", "vehicleRegistration"))
        .isEmpty();
  }

  @Test
  void garbageIsRejected() {
    CapabilityLinkVerifier verifier = verifierAt("2025-12-20T00:00:00Z");
    for (String junk :
        new String[] {
          null, "", ".", "abc", "a.b.c", TEST_VECTOR + "x", "!!!." + TEST_VECTOR.split("[.]")[1]
        }) {
      assertThat(verifier.verifySignature(junk, "owner")).as(String.valueOf(junk)).isEmpty();
    }
  }
}
