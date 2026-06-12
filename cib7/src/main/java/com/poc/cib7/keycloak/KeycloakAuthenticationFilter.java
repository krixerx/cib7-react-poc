package com.poc.cib7.keycloak;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.cibseven.bpm.engine.IdentityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.StringUtils;

/**
 * Bridges Spring Security's authenticated principal to CIB seven's {@link IdentityService} for each
 * {@code /engine-rest/*} request.
 *
 * <p>The filter runs after {@code spring-boot-starter-oauth2-resource-server} has validated the
 * JWT. It pulls the configured user-name-attribute out of the token, queries the user's groups via
 * the {@link KeycloakIdentityProvider}-backed identity service, and calls {@code
 * identityService.setAuthentication(userId, groups)} so the engine enforces candidateGroups /
 * candidateUsers on the request. The {@code finally} clears authentication to avoid leaking it
 * across threads.
 *
 * <p>Verbatim from the cibseven-keycloak plugin's reference example, repackaged under {@code
 * com.poc.cib7.keycloak}.
 */
public class KeycloakAuthenticationFilter implements Filter {

  private static final Logger LOG = LoggerFactory.getLogger(KeycloakAuthenticationFilter.class);

  private final IdentityService identityService;
  private final String userNameAttribute;

  /**
   * Deviation from the upstream plugin example: the {@code clientService} parameter (an {@code
   * OAuth2AuthorizedClientService}) is dropped because we don't include the {@code
   * spring-boot-starter-oauth2-client} starter. Its only use in the example was the {@code
   * OidcUser} branch of {@link #doFilter} for the webapp SSO flow, which we don't have.
   */
  public KeycloakAuthenticationFilter(IdentityService identityService, String userNameAttribute) {
    this.identityService = identityService;
    this.userNameAttribute = userNameAttribute;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {

    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    String userId;
    if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
      // The claim can be absent (e.g. a client-credentials token without
      // preferred_username) — fall through to the AccessDeniedException
      // below instead of NPE-ing into a 500.
      Object attribute = jwtAuthentication.getTokenAttributes().get(userNameAttribute);
      userId = attribute == null ? null : attribute.toString();
    } else {
      throw new AccessDeniedException("Invalid authentication request token");
    }
    if (!StringUtils.hasLength(userId)) {
      throw new AccessDeniedException("Unable to extract user-name-attribute from token");
    }

    LOG.debug("Extracted userId from bearer token: {}", userId);

    try {
      identityService.setAuthentication(userId, getUserGroups(userId));
      chain.doFilter(request, response);
    } finally {
      identityService.clearAuthentication();
    }
  }

  private List<String> getUserGroups(String userId) {
    List<String> groupIds = new ArrayList<>();
    identityService
        .createGroupQuery()
        .groupMember(userId)
        .list()
        .forEach(g -> groupIds.add(g.getId()));
    return groupIds;
  }
}
