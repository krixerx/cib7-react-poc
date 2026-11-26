package com.poc.cib7;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the public SPA base URL to the engine's JUEL/FreeMarker context.
 *
 * <p>Owner-confirmation emails embed a link of the form {@code
 * ${frontendBaseUrl}/consent/<purpose>/${token}} so each recipient can open the public confirmation
 * page without logging in. The value MUST be the browser-visible URL (not the internal docker
 * network alias), because the link is opened by humans, not by services.
 *
 * <p>Same pattern as {@link OutboundConfiguration}: a Spring {@code String} bean that BPMN and
 * templates read as {@code ${frontendBaseUrl}}. Expressions see only the names in {@link
 * ReservedBeansPlugin#RESERVED_NAMES}, so the name is listed there.
 *
 * <p>Driven by the {@code FRONTEND_BASE_URL} env var (defaults to {@code http://localhost:5173} for
 * the Vite dev server).
 */
@Configuration
public class FrontendConfiguration {

  @Bean(name = "frontendBaseUrl")
  public String frontendBaseUrl(@Value("${FRONTEND_BASE_URL:http://localhost:5173}") String url) {
    return url;
  }
}
