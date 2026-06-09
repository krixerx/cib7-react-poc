package com.poc.cib7.documents;

import jakarta.inject.Inject;

import org.cibseven.bpm.engine.IdentityService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.poc.cib7.keycloak.KeycloakAuthenticationFilter;

import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

/**
 * Two Spring Security chains for the new {@code /api/documents/**} surface:
 *
 * <ul>
 *   <li><b>Internal chain</b> (@Order(3)) matches only the two engine→backend
 *       endpoints ({@code /move-pending}, {@code /server-upload}). Auth is by
 *       shared header {@code X-Internal-Token}; no JWT is involved because the
 *       caller is the engine itself, not a logged-in user.</li>
 *   <li><b>JWT chain</b> (@Order(4)) matches everything else under
 *       {@code /api/documents/**}. Reuses the {@link JwtDecoder} bean and the
 *       {@link KeycloakAuthenticationFilter} from
 *       {@link com.poc.cib7.keycloak.RestApiSecurityConfig} so the engine sees
 *       the caller via {@link IdentityService} during permission checks.</li>
 * </ul>
 *
 * <p>Order with respect to the rest of the security stack:
 * <pre>
 *   0  PublicApiSecurityConfig     /api/public/**
 *   1  RestApiSecurityConfig       /engine-rest/**
 *   2  WebappSecurityConfig        /camunda/**
 *   3  DocumentsInternal           /api/documents/move-pending /server-upload
 *   4  DocumentsJwt                /api/documents/**
 * </pre>
 *
 * Earlier chains win when paths overlap, so the internal chain must come
 * before the JWT chain — otherwise the engine's own calls would be rejected
 * for missing a Bearer token they shouldn't have.
 */
@Configuration
public class DocumentsApiSecurityConfig {

    private static final String INTERNAL_PATHS = "/api/documents/move-pending";
    private static final String INTERNAL_PATHS_2 = "/api/documents/server-upload";

    @Inject
    private IdentityService identityService;

    @Inject
    private ApplicationContext applicationContext;

    @Value("${app.internal-task-token:internal-task-token-change-me}")
    private String internalTaskToken;

    @Bean
    @Order(3)
    public SecurityFilterChain documentsInternalSecurity(HttpSecurity http) throws Exception {
        return http
                // String varargs overload — `securityMatcher(RequestMatcher...)`
                // doesn't exist; pass paths directly.
                .securityMatcher(INTERNAL_PATHS, INTERNAL_PATHS_2)
                .csrf(csrf -> csrf.ignoringRequestMatchers(antMatcher(INTERNAL_PATHS), antMatcher(INTERNAL_PATHS_2)))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .addFilterBefore(
                        new InternalTokenAuthenticationFilter(internalTaskToken),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    @Order(4)
    public SecurityFilterChain documentsJwtSecurity(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        String jwkSetUri = applicationContext.getEnvironment().getRequiredProperty("app.keycloak.jwk-set-uri");
        return http
                .securityMatcher(antMatcher("/api/documents/**"))
                .csrf(csrf -> csrf.ignoringRequestMatchers(antMatcher("/api/documents/**")))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwkSetUri(jwkSetUri)))
                .addFilterBefore(keycloakAuthenticationFilter(), AuthorizationFilter.class)
                .build();
    }

    private KeycloakAuthenticationFilter keycloakAuthenticationFilter() {
        String userNameAttribute = applicationContext.getEnvironment()
                .getRequiredProperty("app.keycloak.user-name-attribute");
        return new KeycloakAuthenticationFilter(identityService, userNameAttribute);
    }
}
