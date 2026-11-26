package com.poc.cib7;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The two outbound addresses a BPMN connector may use (docs/platform-api.md, platform API 3.0).
 *
 * <p>The engine reaches external systems (mail, PDF rendering, a pack's own systems) only through
 * Patchbay integrations, at {@code ${patchbayBaseUrl}/api/v1/integrations/<slug>}, and the backend
 * only at its own address, {@code ${backendBaseUrl}/api/...}. Patchbay holds each external system's
 * address, mapping and secrets, so the engine knows none of them. Both names are in {@link
 * ReservedBeansPlugin#RESERVED_NAMES}, so a process variable cannot redirect a connector, and
 * {@link OutboundCredentialsInterceptor} attaches each credential only to its own destination.
 *
 * <p>The {@code localhost} defaults serve {@code mvn spring-boot:run}; Docker sets {@code
 * PATCHBAY_URL} and {@code BACKEND_URL}.
 */
@Configuration
public class OutboundConfiguration {

  @Bean(name = "patchbayBaseUrl")
  public String patchbayBaseUrl(@Value("${PATCHBAY_URL:http://localhost:8090}") String url) {
    return url;
  }

  @Bean(name = "backendBaseUrl")
  public String backendBaseUrl(@Value("${BACKEND_URL:http://localhost:8085}") String url) {
    return url;
  }
}
