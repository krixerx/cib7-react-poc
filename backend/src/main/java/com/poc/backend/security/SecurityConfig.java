package com.poc.backend.security;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * One Spring Security chain per endpoint class (docs/security.md rule 5), selected by path prefix
 * so that the class of an endpoint is visible in its URL:
 *
 * <ol>
 *   <li><b>Public</b> (@Order(0)) — {@code /api/public/**}. Unauthenticated; either the capability
 *       token in the URL is the credential, or the endpoint serves reference data that holds no
 *       personal data (the vehicle catalog).
 *   <li><b>Internal</b> (@Order(1)) — {@code /api/internal/**}, called only by the engine through
 *       the ESB, which injects the shared {@code X-Internal-Token}. No JWT because the caller is
 *       the engine, not a logged-in user. The ingress never routes this prefix.
 *   <li><b>JWT</b> (@Order(2)) — {@code /api/documents/**}, {@code /api/cases/**} and {@code
 *       /api/statistics/**}, called by the SPA, the mobile app and MCP with the user's Keycloak
 *       Bearer. Validation (signature via the internal JWKS URL, issuer string-compare against the
 *       public URL, {@code cib7-rest-api} audience) is configured entirely through {@code
 *       spring.security.oauth2.resourceserver.jwt.*}. Statistics additionally need the {@code
 *       statistics-viewer} realm role, mapped from the token by {@link RealmRoleAuthorities}.
 *   <li><b>Deny</b> (@Order(3)) — everything else. A new controller outside the three prefixes is
 *       rejected until someone decides which class it belongs to, instead of being silently open.
 *       Error dispatches are let through so a 400/404 from a matched chain keeps its status.
 * </ol>
 */
@Configuration
public class SecurityConfig {

  /** Realm role that opens the statistics page; mapped onto the civil-servant group. */
  public static final String STATISTICS_VIEWER = "statistics-viewer";

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
    return http.securityMatcher("/api/internal/**")
        .csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
        .addFilterBefore(
            new InternalTokenAuthenticationFilter(internalTaskToken),
            UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  @Bean
  @Order(2)
  public SecurityFilterChain userApiSecurity(HttpSecurity http) throws Exception {
    JwtAuthenticationConverter jwtAuthentication = new JwtAuthenticationConverter();
    jwtAuthentication.setJwtGrantedAuthoritiesConverter(new RealmRoleAuthorities());
    return http.securityMatcher("/api/documents/**", "/api/cases/**", "/api/statistics/**")
        .csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers("/api/statistics/**")
                    .hasRole(STATISTICS_VIEWER)
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthentication)))
        .build();
  }

  @Bean
  @Order(3)
  public SecurityFilterChain denyEverythingElse(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .build();
  }
}
