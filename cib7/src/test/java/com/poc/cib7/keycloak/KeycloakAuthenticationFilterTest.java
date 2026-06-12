package com.poc.cib7.keycloak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.GroupQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Plain-Mockito tests for the JWT → engine IdentityService bridge. No Spring context: the filter
 * reads the already-validated authentication straight off {@link SecurityContextHolder}.
 */
class KeycloakAuthenticationFilterTest {

  private static final String USER_NAME_ATTRIBUTE = "preferred_username";

  private final IdentityService identityService = mock(IdentityService.class);
  private final FilterChain chain = mock(FilterChain.class);
  private final ServletRequest request = mock(ServletRequest.class);
  private final ServletResponse response = mock(ServletResponse.class);

  private final KeycloakAuthenticationFilter filter =
      new KeycloakAuthenticationFilter(identityService, USER_NAME_ATTRIBUTE);

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  private static void authenticateWithClaims(Map<String, Object> claims) {
    Jwt.Builder jwt = Jwt.withTokenValue("test-token").header("alg", "RS256").subject("subject");
    claims.forEach(jwt::claim);
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt.build()));
  }

  /** Stubs the group query so {@code userId} is a member of exactly {@code groupIds}. */
  private void givenGroups(String userId, String... groupIds) {
    GroupQuery query = mock(GroupQuery.class);
    when(identityService.createGroupQuery()).thenReturn(query);
    when(query.groupMember(userId)).thenReturn(query);
    List<Group> groups =
        Arrays.stream(groupIds)
            .map(
                id -> {
                  Group group = mock(Group.class);
                  when(group.getId()).thenReturn(id);
                  return group;
                })
            .toList();
    when(query.list()).thenReturn(groups);
  }

  @Test
  void bridgesUserIdAndGroupsToEngineAuthenticationAroundTheChain() throws Exception {
    authenticateWithClaims(Map.of(USER_NAME_ATTRIBUTE, "lisa"));
    // Slash-less group ids — the cibseven-keycloak provider strips the
    // Keycloak group-path slash before the ids reach this filter.
    givenGroups("lisa", "applicant", "civil-servant");

    filter.doFilter(request, response, chain);

    InOrder order = inOrder(identityService, chain);
    order.verify(identityService).setAuthentication("lisa", List.of("applicant", "civil-servant"));
    order.verify(chain).doFilter(request, response);
    order.verify(identityService).clearAuthentication();
  }

  @Test
  void clearsEngineAuthenticationEvenWhenTheChainThrows() throws Exception {
    authenticateWithClaims(Map.of(USER_NAME_ATTRIBUTE, "lisa"));
    givenGroups("lisa");
    doThrow(new ServletException("downstream blew up")).when(chain).doFilter(request, response);

    assertThrows(ServletException.class, () -> filter.doFilter(request, response, chain));

    verify(identityService).clearAuthentication();
  }

  /**
   * Regression for 61d23e3: a token without the user-name-attribute claim (e.g. a
   * client-credentials token without preferred_username) must surface as an AccessDeniedException →
   * 403, not an NPE → 500.
   */
  @Test
  void deniesTokenMissingTheUserNameClaimInsteadOfThrowingNpe() {
    authenticateWithClaims(Map.of("azp", "some-service-client"));

    AccessDeniedException denied =
        assertThrows(AccessDeniedException.class, () -> filter.doFilter(request, response, chain));

    assertEquals("Unable to extract user-name-attribute from token", denied.getMessage());
    verifyNoInteractions(identityService, chain);
  }

  @Test
  void deniesTokenWithEmptyUserNameClaim() {
    authenticateWithClaims(Map.of(USER_NAME_ATTRIBUTE, ""));

    assertThrows(AccessDeniedException.class, () -> filter.doFilter(request, response, chain));
    verifyNoInteractions(identityService, chain);
  }

  @Test
  void deniesWhenNoAuthenticationIsPresent() {
    assertThrows(AccessDeniedException.class, () -> filter.doFilter(request, response, chain));
    verifyNoInteractions(identityService, chain);
  }

  @Test
  void deniesNonJwtAuthentication() {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("lisa", "n/a"));

    assertThrows(AccessDeniedException.class, () -> filter.doFilter(request, response, chain));
    verifyNoInteractions(identityService, chain);
  }

  @Test
  void coercesNonStringClaimValuesViaToString() throws Exception {
    authenticateWithClaims(Map.of(USER_NAME_ATTRIBUTE, 12345L));
    givenGroups("12345", "applicant");

    filter.doFilter(request, response, chain);

    verify(identityService).setAuthentication("12345", List.of("applicant"));
  }
}
