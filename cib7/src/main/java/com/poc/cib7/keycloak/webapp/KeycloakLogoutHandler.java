package com.poc.cib7.keycloak.webapp;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Redirects the user to Keycloak's OIDC logout endpoint when they sign out of a webapp, so the SSO
 * session ends everywhere — not just locally.
 *
 * <p>Verbatim from the cibseven-keycloak plugin's {@code sso-kubernetes} example, repackaged under
 * {@code com.poc.cib7.keycloak.webapp}. The authorization endpoint is rewritten to the logout
 * endpoint by string replacement, and the current {@code id_token} is passed as {@code
 * id_token_hint} so Keycloak can identify the session.
 */
@Service
public class KeycloakLogoutHandler implements LogoutSuccessHandler {

  private static final Logger LOG = LoggerFactory.getLogger(KeycloakLogoutHandler.class);

  private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

  private final String oauth2UserLogoutUri;

  public KeycloakLogoutHandler(
      @Value("${spring.security.oauth2.client.provider.keycloak.authorization-uri:}")
          String authorizationUri) {
    if (StringUtils.hasLength(authorizationUri)) {
      this.oauth2UserLogoutUri =
          authorizationUri.replace("openid-connect/auth", "openid-connect/logout");
    } else {
      this.oauth2UserLogoutUri = null;
    }
  }

  @Override
  public void onLogoutSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException, ServletException {
    if (!StringUtils.hasLength(oauth2UserLogoutUri)) {
      return;
    }
    String requestUrl = request.getRequestURL().toString();
    int appIdx = requestUrl.indexOf("/app");
    String redirectUri = appIdx > 0 ? requestUrl.substring(0, appIdx) : requestUrl;
    String idTokenHint = ((OidcUser) authentication.getPrincipal()).getIdToken().getTokenValue();
    String logoutUrl =
        oauth2UserLogoutUri
            + "?post_logout_redirect_uri="
            + redirectUri
            + "&id_token_hint="
            + idTokenHint;
    LOG.debug("Redirecting to Keycloak logout URL {}", logoutUrl);
    redirectStrategy.sendRedirect(request, response, logoutUrl);
  }
}
