package com.poc.cib7.keycloak;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Closes the {@code /engine-rest} paths that the CIB seven security starter leaves open.
 *
 * <p>The starter's Bearer-JWT chain (order 1) skips the engine's own whitelist ({@code /engine},
 * {@code /identity/verify}, {@code /identity/password-policy}, {@code /setup/*}, {@code
 * /configuration}), which a stock deployment serves without a token. Here {@code /identity/verify}
 * would check passwords against Keycloak for anyone, and no client of ours calls any of them, so
 * they are denied (docs/security.md: unmatched paths are denied).
 */
@Configuration
public class EngineRestSecurityConfig {

  @Bean
  @Order(3)
  public SecurityFilterChain engineRestWhitelistDenied(HttpSecurity http) throws Exception {
    return http.securityMatcher(pathPattern("/engine-rest/**"))
        .authorizeHttpRequests(authorize -> authorize.anyRequest().denyAll())
        .build();
  }
}
