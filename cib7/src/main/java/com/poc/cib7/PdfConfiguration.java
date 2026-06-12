package com.poc.cib7;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the pdf-renderer base URL to the engine's JUEL expression context.
 *
 * <p>The BPMN's "Generate approval PDF" service task calls the renderer via the {@code
 * http-connector}:
 *
 * <pre>{@code
 * <camunda:inputParameter name="url">${pdfApiBaseUrl}/render</camunda:inputParameter>
 * }</pre>
 *
 * <p>Same pattern as {@link MailConfiguration}: a Spring {@code String} bean gets auto-injected
 * into the engine's expression manager by bean name.
 *
 * <p>The value is driven by the {@code PDF_API_URL} env var ({@code http://pdf-renderer:8088} in
 * Docker, {@code http://localhost:8088} for local runs).
 */
@Configuration
public class PdfConfiguration {

  @Bean(name = "pdfApiBaseUrl")
  public String pdfApiBaseUrl(@Value("${PDF_API_URL:http://localhost:8088}") String url) {
    return url;
  }
}
