package com.poc.cib7.keycloak;

import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

import com.poc.cib7.policy.FormSchemaRegistry;
import com.poc.cib7.policy.VariablePolicyRegistry;
import com.poc.cib7.policy.VariableWritePolicyFilter;
import jakarta.inject.Inject;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.TaskService;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Spring Security configuration for the {@code /engine-rest/**} surface.
 *
 * <p>Validates every request against Keycloak as an OAuth2 resource server, with the {@code
 * JwtDecoder} Spring Boot builds from {@code spring.security.oauth2.resourceserver.jwt.*} (keys
 * from the internal JWKS URL, {@code iss} and {@code aud} checked against the configured values
 * without any discovery call), then delegates to {@link KeycloakAuthenticationFilter} to push the
 * user into the CIB seven {@link IdentityService}, and then to {@link VariableWritePolicyFilter},
 * which needs that user and their groups to decide which variables the request may write
 * (docs/security.md rule 2).
 *
 * <p>From the cibseven-keycloak plugin's reference example, repackaged under {@code
 * com.poc.cib7.keycloak}, with the example's own decoder and audience validator replaced by Boot's
 * properties.
 */
@Configuration
public class RestApiSecurityConfig {

  @Inject private IdentityService identityService;

  @Inject private ApplicationContext applicationContext;

  @Inject private TaskService taskService;

  @Inject private RepositoryService repositoryService;

  @Inject private VariablePolicyRegistry variablePolicyRegistry;

  @Inject private FormSchemaRegistry formSchemaRegistry;

  @Bean
  @Order(1)
  public SecurityFilterChain httpSecurityRest(HttpSecurity http, JwtDecoder jwtDecoder)
      throws Exception {
    // Only decoder(...): the upstream example also called jwkSetUri(...), which replaces Boot's
    // decoder with a bare one and silently drops its issuer and audience validators.
    return http.securityMatcher(antMatcher("/engine-rest/**"))
        .csrf(csrf -> csrf.ignoringRequestMatchers(antMatcher("/engine-rest/**")))
        .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
        .oauth2ResourceServer(
            oauth2ResourceServer -> oauth2ResourceServer.jwt(jwt -> jwt.decoder(jwtDecoder)))
        .addFilterBefore(keycloakAuthenticationFilter(), AuthorizationFilter.class)
        .addFilterAfter(variableWritePolicyFilter(), KeycloakAuthenticationFilter.class)
        .build();
  }

  public KeycloakAuthenticationFilter keycloakAuthenticationFilter() {
    String userNameAttribute =
        applicationContext.getEnvironment().getRequiredProperty("app.keycloak.user-name-attribute");

    return new KeycloakAuthenticationFilter(identityService, userNameAttribute);
  }

  /**
   * Not a bean on purpose: a {@code Filter} bean would also be registered by Spring Boot as a
   * servlet filter for every path, outside the security chain and before the user is known.
   */
  public VariableWritePolicyFilter variableWritePolicyFilter() {
    String adminGroup =
        applicationContext
            .getEnvironment()
            .getProperty("plugin.identity.keycloak.administratorGroupName", "cib7-admin");
    return new VariableWritePolicyFilter(
        variablePolicyRegistry,
        formSchemaRegistry,
        identityService,
        taskService,
        repositoryService,
        adminGroup);
  }
}
