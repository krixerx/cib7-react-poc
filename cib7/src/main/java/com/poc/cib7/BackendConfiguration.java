package com.poc.cib7;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes two strings to the engine's JUEL/FreeMarker context so BPMN service tasks can call the
 * business microservice (the {@code backend} module) through the http-connector without any
 * BPMN-side hardcoding:
 *
 * <ul>
 *   <li>{@code apiBaseUrl} — the backend's base URL, used in {@code <camunda:inputParameter
 *       name="url">} for the vehicle-registry lookup and the document move-pending / server-upload
 *       tasks.
 *   <li>{@code internalTaskToken} — shared secret put on every internal call as the {@code
 *       X-Internal-Token} header; the backend's internal security chain compares it.
 * </ul>
 *
 * <p>Same wiring trick as {@link FrontendConfiguration} / {@link MailConfiguration} / {@link
 * PdfConfiguration}: a Spring {@code String} bean is auto-injected into the engine's expression
 * manager by bean name. This is connector configuration, not business logic — the engine module
 * deliberately contains no {@code /api/**} endpoints of its own anymore.
 */
@Configuration
public class BackendConfiguration {

  @Bean(name = "apiBaseUrl")
  public String apiBaseUrl(@Value("${BACKEND_API_URL:http://localhost:8085}") String url) {
    return url;
  }

  @Bean(name = "internalTaskToken")
  public String internalTaskToken(
      @Value("${INTERNAL_TASK_TOKEN:internal-task-token-change-me}") String token) {
    return token;
  }
}
