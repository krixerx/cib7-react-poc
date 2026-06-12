package com.poc.cib7;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the Mailpit API base URL to the engine's JUEL expression context.
 *
 * <p>The BPMN's "Send back" and "Review reminder" service tasks both POST to Mailpit through the
 * {@code http-connector}, e.g.:
 *
 * <pre>{@code
 * <camunda:inputParameter name="url">${mailApiBaseUrl}/api/v1/send</camunda:inputParameter>
 * }</pre>
 *
 * <p>Spring beans are auto-injected into the engine's expression manager by bean name. Declaring
 * this {@code String} bean called {@code mailApiBaseUrl} is enough — no engine-side wiring needed.
 *
 * <p>The value is driven by the {@code MAIL_API_URL} env var ({@code http://mailpit:8025} in
 * Docker, {@code http://localhost:8025} for {@code mvn spring-boot:run}).
 */
@Configuration
public class MailConfiguration {

  @Bean(name = "mailApiBaseUrl")
  public String mailApiBaseUrl(@Value("${MAIL_API_URL:http://localhost:8025}") String url) {
    return url;
  }
}
