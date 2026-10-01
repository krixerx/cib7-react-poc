package com.poc.cib7.links;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.Test;

/**
 * The engine mints and the backend verifies, so the token format is a contract between two modules.
 * {@link #TEST_VECTOR} is repeated verbatim in the backend's {@code CapabilityLinkVerifierTest}; a
 * change to the format on either side breaks that side's build.
 */
class CapabilityLinksTest {

  static final String SECRET = "test-vector-secret";
  static final String PI = "0f8fad5b-d9cb-469f-a165-70867728950e";

  /** owner link for party p1, round 7, expiring 2026-01-01T00:00:00Z (1767225600). */
  static final String TEST_VECTOR =
      "MGY4ZmFkNWItZDljYi00NjlmLWExNjUtNzA4Njc3Mjg5NTBlfHAxfG93bmVyfDd8MTc2NzIyNTYwMA"
          + ".h8zuf641sJqIleT8wN0KnPWRE3Srm69SxtMLQyeWd-A";

  private static CapabilityLinks links(String secret) {
    Clock clock = Clock.fixed(Instant.parse("2025-12-18T00:00:00Z"), ZoneOffset.UTC);
    return new CapabilityLinks(secret, Duration.ofDays(14), Duration.ofDays(30), clock);
  }

  private static DelegateExecution execution(Object round) {
    DelegateExecution execution = mock(DelegateExecution.class);
    when(execution.getProcessInstanceId()).thenReturn(PI);
    when(execution.getVariable(CapabilityLinks.ROUND_VARIABLE)).thenReturn(round);
    return execution;
  }

  @Test
  void ownerLinkMatchesTheSharedTestVector() {
    assertEquals(TEST_VECTOR, links(SECRET).owner(execution(7L), "p1"));
  }

  @Test
  void tokenDependsOnSecretRoundPartyAndPurpose() {
    CapabilityLinks links = links(SECRET);
    assertNotEquals(TEST_VECTOR, links("another-secret").owner(execution(7L), "p1"));
    assertNotEquals(TEST_VECTOR, links.owner(execution(8L), "p1"));
    assertNotEquals(TEST_VECTOR, links.owner(execution(7L), "p2"));
    assertNotEquals(TEST_VECTOR, links.founder(execution(7L), "p1"));
  }

  @Test
  void paymentLinkHasRoundZeroAndThirtyDayExpiry() {
    String token = links(SECRET).payment(execution(null));
    assertEquals(
        links(SECRET)
            .mint(
                PI,
                CapabilityLinks.APPLICANT_PARTY,
                CapabilityLinks.PURPOSE_PAYMENT,
                0L,
                Instant.parse("2026-01-17T00:00:00Z").getEpochSecond()),
        token);
    assertFalse(token.contains(PI), "the instance id is encoded, not readable in the URL");
  }

  @Test
  void consentLinkNeedsARound() {
    assertThrows(IllegalStateException.class, () -> links(SECRET).owner(execution(null), "p1"));
  }

  @Test
  void rejectsFieldsThatCouldBreakThePayloadFormat() {
    assertThrows(IllegalArgumentException.class, () -> links(SECRET).owner(execution(1L), "p1|x"));
    assertThrows(
        IllegalArgumentException.class, () -> links(SECRET).mint("a|b", "p1", "owner", 1, 1));
  }
}
