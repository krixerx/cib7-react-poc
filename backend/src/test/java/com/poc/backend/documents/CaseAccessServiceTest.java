package com.poc.backend.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.poc.backend.engine.EngineClient;
import com.poc.backend.security.CaseAccessService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Plain Mockito unit test (no Spring context) for the documents API's per-case access rule. The
 * service reads the caller from {@link SecurityContextHolder}, so each test plants the
 * authentication it needs and the context is wiped afterwards.
 */
class CaseAccessServiceTest {

  private static final String PI = "pi-1";

  private final EngineClient engine = mock(EngineClient.class);
  private final CaseAccessService service = new CaseAccessService(engine);

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  private static void authenticateAs(String preferredUsername, List<String> realmRoles) {
    Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "none").claim("sub", "subject");
    if (preferredUsername != null) {
      jwt.claim("preferred_username", preferredUsername);
    }
    if (realmRoles != null) {
      jwt.claim("realm_access", Map.of("roles", realmRoles));
    }
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt.build()));
  }

  @Test
  void starterOfTheCaseIsAllowed() {
    authenticateAs("alice", List.of("applicant"));
    when(engine.getHistoricStartUserId(PI)).thenReturn("alice");

    assertThat(service.canAccessCase(PI)).isTrue();
  }

  @Test
  void nonStarterIsDenied() {
    authenticateAs("alice", List.of("applicant"));
    when(engine.getHistoricStartUserId(PI)).thenReturn("bob");

    assertThat(service.canAccessCase(PI)).isFalse();
  }

  @Test
  void unknownProcessInstanceResolvesToDenied() {
    authenticateAs("alice", List.of("applicant"));
    when(engine.getHistoricStartUserId(PI)).thenReturn(null);

    assertThat(service.canAccessCase(PI)).isFalse();
  }

  @Test
  void civilServantRoleBypassesStarterCheck() {
    authenticateAs("reviewer", List.of("civil-servant"));
    when(engine.historicProcessInstanceExists(PI)).thenReturn(true);

    assertThat(service.canAccessCase(PI)).isTrue();
    // Reviewers skip the starter lookup, but not the existence check.
    verify(engine, never()).getHistoricStartUserId(PI);
  }

  @Test
  void cib7AdminRoleBypassesStarterCheck() {
    authenticateAs("root", List.of("cib7-admin"));
    when(engine.historicProcessInstanceExists(PI)).thenReturn(true);

    assertThat(service.canAccessCase(PI)).isTrue();
  }

  @Test
  void reviewerIsDeniedAnUnknownProcessInstance() {
    authenticateAs("reviewer", List.of("civil-servant"));
    when(engine.historicProcessInstanceExists(PI)).thenReturn(false);

    assertThat(service.canAccessCase(PI)).isFalse();
  }

  @Test
  void noAuthenticationIsDenied() {
    SecurityContextHolder.clearContext();

    assertThat(service.canAccessCase(PI)).isFalse();
    verifyNoInteractions(engine);
  }

  @Test
  void nonJwtAuthenticationIsDenied() {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("alice", "pw"));

    assertThat(service.canAccessCase(PI)).isFalse();
    verifyNoInteractions(engine);
  }

  @Test
  void blankPreferredUsernameIsDenied() {
    authenticateAs("   ", List.of("applicant"));

    assertThat(service.canAccessCase(PI)).isFalse();
    verifyNoInteractions(engine);
  }

  @Test
  void missingPreferredUsernameClaimIsDenied() {
    authenticateAs(null, List.of("applicant"));

    assertThat(service.canAccessCase(PI)).isFalse();
    verifyNoInteractions(engine);
  }

  @Test
  void missingRealmAccessClaimFallsThroughToStarterCheck() {
    authenticateAs("alice", null);
    when(engine.getHistoricStartUserId(PI)).thenReturn("alice");

    assertThat(service.canAccessCase(PI)).isTrue();
  }

  @Test
  void ownCaseIdsAreTheCasesTheCallerStarted() {
    authenticateAs("alice", List.of());
    when(engine.instanceIdsStartedBy("alice", CaseAccessService.MAX_OWN_CASES))
        .thenReturn(List.of(PI));

    assertThat(service.ownCaseIds()).containsExactly(PI);
  }

  @Test
  void reviewerOwnCaseIdsAreStillOnlyTheirOwn() {
    authenticateAs("homer", List.of("civil-servant"));
    when(engine.instanceIdsStartedBy("homer", CaseAccessService.MAX_OWN_CASES))
        .thenReturn(List.of());

    assertThat(service.ownCaseIds()).isEmpty();
    verify(engine).instanceIdsStartedBy("homer", CaseAccessService.MAX_OWN_CASES);
  }

  @Test
  void ownCaseIdsWithoutAuthenticationAreEmpty() {
    assertThat(service.ownCaseIds()).isEmpty();
    verifyNoInteractions(engine);
  }
}
