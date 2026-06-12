package com.poc.cib7.keycloak.webapp;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.rest.security.auth.AuthenticationResult;
import org.cibseven.bpm.engine.rest.security.auth.impl.ContainerBasedAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.util.StringUtils;

/**
 * Bridges Spring Security's OAuth2 login into the engine's IdentityService for the legacy CIB seven
 * webapps (Cockpit / Tasklist / Admin).
 *
 * <p>Verbatim from the cibseven-keycloak plugin's {@code sso-kubernetes} example, repackaged under
 * {@code com.poc.cib7.keycloak.webapp}. The {@link ContainerBasedAuthenticationFilter} calls {@link
 * #extractAuthenticatedUser(HttpServletRequest, ProcessEngine)} for every webapp request — we read
 * the {@link OidcUser} from the security context, look up the user's groups via the engine's {@code
 * IdentityService} (backed by the cibseven-keycloak ReadOnlyIdentityProvider), and hand the pair to
 * the engine so candidateGroups / authorization checks behave the same as for the {@code
 * /engine-rest} path.
 */
public class KeycloakAuthenticationProvider extends ContainerBasedAuthenticationProvider {

  @Override
  public AuthenticationResult extractAuthenticatedUser(
      HttpServletRequest request, ProcessEngine engine) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof OAuth2AuthenticationToken)
        || !(authentication.getPrincipal() instanceof OidcUser)) {
      return AuthenticationResult.unsuccessful();
    }
    String userId = ((OidcUser) authentication.getPrincipal()).getName();
    if (!StringUtils.hasLength(userId)) {
      return AuthenticationResult.unsuccessful();
    }

    AuthenticationResult result = new AuthenticationResult(userId, true);
    result.setGroups(getUserGroups(userId, engine));
    return result;
  }

  private List<String> getUserGroups(String userId, ProcessEngine engine) {
    List<String> groupIds = new ArrayList<>();
    engine
        .getIdentityService()
        .createGroupQuery()
        .groupMember(userId)
        .list()
        .forEach(g -> groupIds.add(g.getId()));
    return groupIds;
  }
}
