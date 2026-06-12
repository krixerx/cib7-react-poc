package com.poc.cib7.keycloak;

import org.cibseven.bpm.extension.keycloak.plugin.KeycloakIdentityProviderPlugin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Activates the CIB seven Keycloak Identity Provider Plugin as a Spring bean.
 *
 * <p>Configuration is bound from {@code plugin.identity.keycloak.*} in {@code application.yaml}.
 * The CIB seven Spring Boot starter discovers every {@code ProcessEnginePlugin} bean and wires it
 * into the engine, so this single declaration makes the engine query Keycloak for users and groups
 * via its Admin REST API.
 *
 * <p>Verbatim from the plugin's reference example ({@code examples/sso-kubernetes} on
 * cibseven-keycloak), repackaged under {@code com.poc.cib7.keycloak} to match this project's
 * package layout.
 */
@Component
@ConfigurationProperties(prefix = "plugin.identity.keycloak")
public class KeycloakIdentityProvider extends KeycloakIdentityProviderPlugin {}
