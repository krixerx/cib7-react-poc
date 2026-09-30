package com.poc.backend.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes every log line a request produces attributable to the Keycloak user who caused it.
 *
 * <p>Graylog can only filter on fields that arrive with the message, and a log statement inside a
 * controller or repository has no idea who is calling. Putting the username in the MDC at the edge
 * of the request is the cheapest way to get {@code user_id} onto all of them: the GELF encoder
 * copies MDC entries into additional fields (see {@code resources/logback-spring.xml}).
 *
 * <p>Registered <em>after</em> Spring Security's filter chain (see {@link LoggingConfiguration}),
 * so the {@code SecurityContext} is already populated. Only the JWT chain produces a user: {@code
 * /api/public/**} is unauthenticated by design (the URL token is the credential) and the internal
 * engine→backend endpoints authenticate a machine via {@code X-Internal-Token} without setting an
 * {@code Authentication} at all. Those requests are logged without a {@code user_id}, which is the
 * honest answer — there is no user behind them.
 *
 * <p>The MDC is cleared in a {@code finally} block — Tomcat pools its request threads, so a
 * leftover entry would mislabel the next request handled by the same thread.
 */
public class MdcUserFilter extends OncePerRequestFilter {

  /** GELF additional-field name. Also the MDC key, since the encoder copies keys verbatim. */
  static final String USER_ID = "user_id";

  private final String userNameAttribute;

  /**
   * @param userNameAttribute the JWT claim holding the username, so the id in the backend's logs
   *     matches the id the engine records against a task.
   */
  public MdcUserFilter(String userNameAttribute) {
    this.userNameAttribute = userNameAttribute;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String userId = resolveUserId(SecurityContextHolder.getContext().getAuthentication());
    if (userId != null) {
      MDC.put(USER_ID, userId);
    }
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(USER_ID);
    }
  }

  private String resolveUserId(Authentication authentication) {
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken) {
      return null;
    }
    if (authentication instanceof JwtAuthenticationToken jwt) {
      Object claim = jwt.getTokenAttributes().get(userNameAttribute);
      if (claim != null) {
        return claim.toString();
      }
    }
    // Last resort: whatever Spring Security calls the principal. For a
    // client-credentials token that is the client id, which is still the most
    // useful thing we can say about the caller.
    return authentication.getName();
  }
}
