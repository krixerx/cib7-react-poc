package com.poc.backend.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Plain-Mockito tests for the MDC bridge. Worth testing because the failure mode is silent: a
 * broken resolver produces log events that Graylog happily accepts with no {@code user_id} at all,
 * and a missing cleanup leaks one request's user onto the next request sharing the thread.
 */
class MdcUserFilterTest {

  private static final String USER_NAME_ATTRIBUTE = "preferred_username";

  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private final HttpServletResponse response = mock(HttpServletResponse.class);
  private final MdcUserFilter filter = new MdcUserFilter(USER_NAME_ATTRIBUTE);

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
    MDC.clear();
  }

  @Test
  void putsTheJwtUsernameInTheMdcForTheDurationOfTheRequest() throws Exception {
    SecurityContextHolder.getContext().setAuthentication(jwtToken("bart"));

    assertEquals("bart", userIdSeenBy(filter));
  }

  @Test
  void fallsBackToThePrincipalNameWhenTheClaimIsAbsent() throws Exception {
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("sub", "service-account-cib7-business")
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES));

    assertEquals("service-account-cib7-business", userIdSeenBy(filter));
  }

  @Test
  void logsNoUserForAnAnonymousRequest() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

    assertNull(userIdSeenBy(filter));
  }

  @Test
  void logsNoUserWhenThereIsNoAuthenticationAtAll() throws Exception {
    assertNull(userIdSeenBy(filter));
  }

  @Test
  void clearsTheMdcAfterTheRequest() throws Exception {
    SecurityContextHolder.getContext().setAuthentication(jwtToken("homer"));

    filter.doFilter(request, response, mock(FilterChain.class));

    assertNull(MDC.get(MdcUserFilter.USER_ID));
  }

  @Test
  void clearsTheMdcEvenWhenTheChainThrows() {
    SecurityContextHolder.getContext().setAuthentication(jwtToken("homer"));
    FilterChain failing = mock(FilterChain.class);
    try {
      doAnswer(
              invocation -> {
                throw new IllegalStateException("downstream blew up");
              })
          .when(failing)
          .doFilter(request, response);
      filter.doFilter(request, response, failing);
    } catch (Exception expected) {
      // The exception is the point of the test; only the cleanup is asserted.
    }

    assertNull(MDC.get(MdcUserFilter.USER_ID));
  }

  /** Runs the filter and reports what the MDC held while the chain was executing. */
  private String userIdSeenBy(MdcUserFilter subject) throws Exception {
    String[] seen = new String[1];
    FilterChain chain = mock(FilterChain.class);
    doAnswer(
            invocation -> {
              seen[0] = MDC.get(MdcUserFilter.USER_ID);
              return null;
            })
        .when(chain)
        .doFilter(request, response);

    subject.doFilter(request, response, chain);
    return seen[0];
  }

  private static JwtAuthenticationToken jwtToken(String username) {
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .claims(claims -> claims.putAll(Map.of(USER_NAME_ATTRIBUTE, username, "sub", "uuid")))
            .build();
    return new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES);
  }
}
