package com.poc.backend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Three Spring Security chains, one per trust level — the same layering the endpoints had inside
 * the engine module, minus the engine-specific chains ({@code /engine-rest}, {@code /camunda}) that
 * stayed behind:
 *
 * <ol>
 *   <li><b>Public</b> (@Order(0)) — {@code /api/public/**}. Unauthenticated; the per-participant
 *       UUID token (or the opaque process instance id for payments) embedded in the URL is the
 *       credential.
 *   <li><b>Internal</b> (@Order(1)) — the engine→backend endpoints ({@code /move-pending}, {@code
 *       /server-upload}, {@code /index-case}) that BPMN service tasks call via the http-connector.
 *       Auth is the shared {@code X-Internal-Token} header; no JWT because the caller is the
 *       engine, not a logged-in user.
 *   <li><b>JWT</b> (@Order(2)) — everything else under {@code /api/documents/**} plus {@code
 *       /api/cases/**} (case-card search), called by the SPA / MCP with the user's Keycloak Bearer.
 *       Validation (signature via the internal JWKS URL, issuer string-compare against the public
 *       URL, {@code cib7-rest-api} audience) is configured entirely through {@code
 *       spring.security.oauth2.resourceserver.jwt.*}.
 * </ol>
 *
 * <p>Earlier chains win on overlap, so the internal chain must come before the JWT chain —
 * otherwise the engine's own calls would be rejected for missing a Bearer token they shouldn't
 * have.
 */
@Configuration
public class SecurityConfig {

  private static final String INTERNAL_MOVE_PENDING = "/api/documents/move-pending";
  private static final String INTERNAL_SERVER_UPLOAD = "/api/documents/server-upload";
  private static final String INTERNAL_INDEX_CASE = "/api/documents/index-case";

  @Value("${app.internal-task-token}")
  private String internalTaskToken;

  @Bean
  @Order(0)
  public SecurityFilterChain publicApiSecurity(HttpSecurity http) throws Exception {
    return http.securityMatcher("/api/public/**")
        .csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
        .build();
  }

  @Bean
  @Order(1)
  public SecurityFilterChain internalApiSecurity(HttpSecurity http) throws Exception {
    return http.securityMatcher(INTERNAL_MOVE_PENDING, INTERNAL_SERVER_UPLOAD, INTERNAL_INDEX_CASE)
        .csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
        .addFilterBefore(
            new InternalTokenAuthenticationFilter(internalTaskToken),
            UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  @Bean
  @Order(2)
  public SecurityFilterChain documentsJwtSecurity(HttpSecurity http) throws Exception {
    return http.securityMatcher("/api/documents/**", "/api/cases/**")
        .csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
        .build();
  }
}
