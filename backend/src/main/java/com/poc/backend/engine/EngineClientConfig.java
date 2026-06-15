package com.poc.backend.engine;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

/**
 * Wires the {@link RestClient} that {@link EngineClient} uses against {@code /engine-rest}.
 *
 * <p>Every request carries a Bearer token for the {@code cib7-business} Keycloak service account,
 * minted via the {@code client_credentials} grant and attached by {@link
 * OAuth2ClientHttpRequestInterceptor}. On the engine side the token passes the same validation as
 * any user token (issuer + {@code cib7-rest-api} audience via the realm's audience scope), and the
 * cibseven-keycloak identity plugin resolves the service-account user — a member of {@code
 * /cib7-admin} in the realm export — to an engine admin, so process-instance queries, variable
 * writes, and message correlation all pass engine authorization.
 *
 * <p>{@link AuthorizedClientServiceOAuth2AuthorizedClientManager} is used (not the request-bound
 * default manager) because it caches and refreshes tokens without needing an HTTP request context —
 * the same engine calls run from unauthenticated public endpoints.
 */
@Configuration
public class EngineClientConfig {

  /**
   * Constant principal for the client-credentials token cache. The engine client always acts as the
   * {@code cib7-business} service account, never as the inbound caller — and the default
   * SecurityContext-based resolver crashes with "principalName cannot be empty" when the inbound
   * request is authenticated by the documents JWT chain (the resolved authentication carries no
   * usable name for the authorized-client store).
   */
  private static final Authentication ENGINE_PRINCIPAL =
      new AnonymousAuthenticationToken(
          "engine-client",
          "cib7-business-engine-client",
          AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

  @Bean
  public RestClient engineRestClient(
      ClientRegistrationRepository registrations,
      OAuth2AuthorizedClientService authorizedClients,
      @Value("${app.engine-url}") String engineUrl) {
    AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
        new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClients);
    manager.setAuthorizedClientProvider(
        OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());

    OAuth2ClientHttpRequestInterceptor bearer = new OAuth2ClientHttpRequestInterceptor(manager);
    bearer.setClientRegistrationIdResolver(request -> "engine");
    bearer.setPrincipalResolver(request -> ENGINE_PRINCIPAL);

    // RestClient.builder() (not an injected RestClient.Builder bean):
    // Boot 4 moved the client auto-configuration into the separate
    // spring-boot-restclient module, which the webmvc starter doesn't
    // pull in. The static builder picks up the same classpath-detected
    // message converters, which is all this client needs.
    return RestClient.builder()
        .baseUrl(engineUrl + "/engine-rest")
        .requestInterceptor(bearer)
        .build();
  }
}
