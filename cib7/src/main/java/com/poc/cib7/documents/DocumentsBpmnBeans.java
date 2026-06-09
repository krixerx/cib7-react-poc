package com.poc.cib7.documents;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes two strings to the engine's JUEL/FreeMarker context so BPMN
 * service tasks can call the backend's internal endpoints without any
 * BPMN-side hardcoding:
 *
 * <ul>
 *   <li>{@code apiBaseUrl}        — the backend's own loopback URL,
 *       used in {@code <camunda:inputParameter name="url">}.</li>
 *   <li>{@code internalTaskToken} — shared secret put on every internal
 *       call as the {@code X-Internal-Token} header.</li>
 * </ul>
 *
 * <p>Same wiring trick as {@link com.poc.cib7.FrontendConfiguration} /
 * {@link com.poc.cib7.MailConfiguration} / {@link com.poc.cib7.PdfConfiguration}:
 * a Spring {@code String} bean is auto-injected into the engine's
 * expression manager by bean name.
 */
@Configuration
public class DocumentsBpmnBeans {

    @Bean(name = "apiBaseUrl")
    public String apiBaseUrl(@Value("${app.base-url:http://localhost:8080}") String url) {
        return url;
    }

    @Bean(name = "internalTaskToken")
    public String internalTaskToken(@Value("${app.internal-task-token:internal-task-token-change-me}") String token) {
        return token;
    }
}
