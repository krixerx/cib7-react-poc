package com.poc.cib7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.poc.cib7.keycloak.KeycloakIdentityProvider;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.cibseven.bpm.engine.ProcessEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * A deployed payload template cannot construct classes with {@code ?new} or reach the Java API of a
 * value with {@code ?api} (design D17 of replace-esb-with-patchbay). The engine's stock FreeMarker
 * script engine allows {@code ?new} on any {@code TemplateModel} class, which is the first step of
 * most FreeMarker template escapes; a pack template that tried it must fail before its connector
 * sends anything. The probes run through a real http-connector with a {@code resource=} template,
 * the way a pack's service task does, against a loopback server that records every request.
 */
@SpringBootTest(
    properties = {"BACKEND_URL=http://localhost:8085", "PATCHBAY_URL=http://localhost:8090"})
@ActiveProfiles("test")
class PayloadTemplateHardeningTest {

  private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();
  private static HttpServer server;

  @MockitoBean private KeycloakIdentityProvider keycloakIdentityProvider;

  @Autowired private ProcessEngine processEngine;

  @BeforeAll
  static void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try (InputStream body = exchange.getRequestBody()) {
            RECEIVED.add(new String(body.readAllBytes(), StandardCharsets.UTF_8));
          }
          byte[] answer = "{}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, answer.length);
          exchange.getResponseBody().write(answer);
          exchange.close();
        });
    server.start();
  }

  @AfterAll
  static void stopServer() {
    server.stop(0);
  }

  @BeforeEach
  void deploy() {
    RECEIVED.clear();
    if (processEngine
            .getRepositoryService()
            .createProcessDefinitionQuery()
            .processDefinitionKey("payloadAllowed")
            .count()
        == 0) {
      processEngine
          .getRepositoryService()
          .createDeployment()
          .name("payload-template-hardening-test")
          .addClasspathResource("freemarker-hardening/payload-hardening-test.bpmn")
          .addClasspathResource("freemarker-hardening/allowed.json.ftl")
          .addClasspathResource("freemarker-hardening/object-constructor.json.ftl")
          .addClasspathResource("freemarker-hardening/template-model.json.ftl")
          .addClasspathResource("freemarker-hardening/api-builtin.json.ftl")
          .deploy();
    }
  }

  private Map<String, Object> probeUrl() {
    return Map.of("probeUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/probe");
  }

  @Test
  void anOrdinaryTemplateStillSendsItsRequest() {
    processEngine.getRuntimeService().startProcessInstanceByKey("payloadAllowed", probeUrl());

    assertEquals(1, RECEIVED.size(), "the control template sent no request");
    assertTrue(RECEIVED.get(0).contains("/probe"), RECEIVED.get(0));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "payloadObjectConstructor | not allowed in the template for security reasons",
        "payloadTemplateModel     | not allowed in the template for security reasons",
        "payloadApi               | api_builtin_enabled"
      })
  void aTemplateUsingNewOrApiFailsAndSendsNothing(String processKey, String refusal) {
    RuntimeException refused =
        assertThrows(
            RuntimeException.class,
            () ->
                processEngine.getRuntimeService().startProcessInstanceByKey(processKey, probeUrl()),
            processKey + " rendered its payload");

    String messages = causeChain(refused);
    assertTrue(messages.contains(refusal), messages);
    assertTrue(RECEIVED.isEmpty(), processKey + " sent " + RECEIVED);
  }

  private static String causeChain(Throwable failure) {
    StringBuilder messages = new StringBuilder();
    for (Throwable t = failure; t != null; t = t.getCause()) {
      messages.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
    }
    return messages.toString();
  }
}
