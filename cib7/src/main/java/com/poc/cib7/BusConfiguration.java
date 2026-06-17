package com.poc.cib7;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the single integration-bus base URL to the engine's JUEL/FreeMarker context.
 *
 * <p>This one bean replaces the former {@code MailConfiguration} / {@code PdfConfiguration} /
 * {@code BackendConfiguration} trio. The engine no longer knows the address of any individual
 * downstream system (Mailpit, pdf-renderer, the backend) — every outbound HTTP integration now goes
 * to the bus, and the bus (Apache Camel, see the {@code esb} service) routes each path to the real
 * system. BPMN connectors therefore all read {@code ${busBaseUrl}}:
 *
 * <pre>{@code
 * <camunda:inputParameter name="url">${busBaseUrl}/api/v1/send</camunda:inputParameter>          <!-- email   -->
 * <camunda:inputParameter name="url">${busBaseUrl}/render</camunda:inputParameter>               <!-- pdf     -->
 * <camunda:inputParameter name="url">${busBaseUrl}/api/public/...</camunda:inputParameter>       <!-- backend -->
 * <camunda:inputParameter name="url">${busBaseUrl}/api/documents/...</camunda:inputParameter>    <!-- backend -->
 * }</pre>
 *
 * <p>The internal {@code X-Internal-Token} secret moved to the bus too: it is no longer set by the
 * BPMN document tasks — the bus injects it on the {@code /api/documents} route — so the engine no
 * longer holds that credential.
 *
 * <p>Driven by the {@code BUS_URL} env var ({@code http://esb:8080} in Docker). The {@code
 * localhost} default is only a convenience for running the engine standalone via {@code mvn
 * spring-boot:run}; in that mode the bus must be reachable on the host (run it via Docker Compose,
 * which is the usual setup).
 */
@Configuration
public class BusConfiguration {

  @Bean(name = "busBaseUrl")
  public String busBaseUrl(@Value("${BUS_URL:http://localhost:8080}") String url) {
    return url;
  }
}
