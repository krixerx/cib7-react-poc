package com.poc.cib7.keycloak;

import jakarta.inject.Inject;

import org.cibseven.bpm.engine.IdentityService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

/**
 * Spring Security configuration for the {@code /engine-rest/**} surface.
 *
 * <p>Activated by {@code rest.security.enabled=true}. Validates every request
 * against Keycloak as an OAuth2 resource server (issuer + audience), then
 * delegates to {@link KeycloakAuthenticationFilter} to push the user into the
 * CIB seven {@link IdentityService}.
 *
 * <p>Verbatim from the cibseven-keycloak plugin's reference example,
 * repackaged under {@code com.poc.cib7.keycloak}.
 */
@Configuration
@ConditionalOnProperty(name = "rest.security.enabled", havingValue = "true", matchIfMissing = true)
public class RestApiSecurityConfig {

    @Inject
    private RestApiSecurityConfigurationProperties configProps;

    @Inject
    private IdentityService identityService;

    @Inject
    private ApplicationContext applicationContext;

    @Bean
    @Order(1)
    public SecurityFilterChain httpSecurityRest(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        String jwkSetUri = applicationContext.getEnvironment().getRequiredProperty("app.keycloak.jwk-set-uri");

        return http
                .securityMatcher(antMatcher("/engine-rest/**"))
                .csrf(csrf -> csrf.ignoringRequestMatchers(antMatcher("/engine-rest/**")))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2ResourceServer -> oauth2ResourceServer
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwkSetUri(jwkSetUri)))
                .addFilterBefore(keycloakAuthenticationFilter(), AuthorizationFilter.class)
                .build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        String issuerUri = applicationContext.getEnvironment().getRequiredProperty("app.keycloak.issuer-uri");
        String jwkSetUri = applicationContext.getEnvironment().getRequiredProperty("app.keycloak.jwk-set-uri");

        // Two deviations from the upstream plugin example, both forced by the
        // docker-compose split between internal and external Keycloak URLs:
        //
        // 1. Properties are read from `app.keycloak.*` instead of
        //    `spring.security.oauth2.client.provider.<provider>.*`. The latter
        //    triggers Spring Boot oauth2-client auto-config to fetch OIDC
        //    discovery from the issuer URL at startup, which fails because
        //    the backend can't reach the browser's public URL.
        //
        // 2. The JWT decoder uses NimbusJwtDecoder.withJwkSetUri (internal
        //    URL) instead of JwtDecoders.fromOidcIssuerLocation (which also
        //    fetches discovery). The `iss` claim is then validated against
        //    the public URL with JwtValidators.createDefaultWithIssuer — a
        //    plain string equality check, no HTTP call.
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();

        OAuth2TokenValidator<Jwt> audienceValidator = new AudienceValidator(configProps.getRequiredAudience());
        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuerUri);
        OAuth2TokenValidator<Jwt> withAudience = new DelegatingOAuth2TokenValidator<>(withIssuer, audienceValidator);

        jwtDecoder.setJwtValidator(withAudience);

        return jwtDecoder;
    }

    public KeycloakAuthenticationFilter keycloakAuthenticationFilter() {
        String userNameAttribute = applicationContext.getEnvironment()
                .getRequiredProperty("app.keycloak.user-name-attribute");

        return new KeycloakAuthenticationFilter(identityService, userNameAttribute);
    }
}
