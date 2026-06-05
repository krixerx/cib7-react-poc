package com.poc.cib7.keycloak;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

/**
 * Public, unauthenticated carve-out for {@code GET /engine-rest/process-definition}.
 *
 * <p>An applicant landing on the SPA for the first time needs to see the
 * catalogue of services ("what can I do here?") before deciding whether to
 * sign in. Process-definition metadata (key, name, version) is non-sensitive,
 * so this single list endpoint is exposed anonymously; every other
 * {@code /engine-rest/**} route still requires a Bearer JWT via
 * {@link RestApiSecurityConfig}.
 *
 * <p>Ordered ahead of {@link RestApiSecurityConfig} (order 1). The matcher is
 * pinned to GET + the exact collection path, so siblings like
 * {@code POST /engine-rest/process-definition/key/{key}/start} fall through
 * to the JWT-protected chain.
 *
 * <p>Because no user is set on the engine's {@code IdentityService} for the
 * anonymous request, the engine's authorization layer treats it as the
 * system user and returns the full list without per-user filtering — same
 * behaviour the engine uses for its own auto-deployment.
 */
@Configuration
public class PublicEngineRestSecurityConfig {

    @Bean
    @Order(0)
    public SecurityFilterChain publicEngineRestSecurity(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(antMatcher(HttpMethod.GET, "/engine-rest/process-definition"))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .build();
    }
}
