package com.poc.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Single-header check: every request that reaches this filter must carry {@code X-Internal-Token:
 * <configured-secret>}. Used by the {@code /api/documents/move-pending} and {@code
 * /api/documents/server-upload} endpoints called from BPMN service tasks via the cibseven
 * http-connector.
 *
 * <p>Replaces a service-account JWT roundtrip for engine→backend calls — same pattern as MinIO's
 * own service token: the secret travels with the request, the receiver does a constant-time
 * compare, no Keycloak involvement. Rotated by re-issuing the {@code INTERNAL_TASK_TOKEN} env var
 * on both the engine (BPMN expression) and backend (this filter).
 */
public class InternalTokenAuthenticationFilter extends OncePerRequestFilter {

  static final String HEADER = "X-Internal-Token";

  private final String expectedToken;

  public InternalTokenAuthenticationFilter(String expectedToken) {
    this.expectedToken = expectedToken;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String supplied = request.getHeader(HEADER);
    if (supplied == null || !constantTimeEquals(supplied, expectedToken)) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json");
      response
          .getWriter()
          .write(
              "{\"code\":\"invalid_internal_token\",\"message\":\"X-Internal-Token missing or invalid.\"}");
      return;
    }
    chain.doFilter(request, response);
  }

  /**
   * {@link MessageDigest#isEqual} instead of a hand-rolled loop: it is time-constant even across
   * different lengths, so the comparison leaks neither content nor token length.
   */
  private static boolean constantTimeEquals(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }
}
