package com.poc.cib7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.poc.cib7.keycloak.KeycloakIdentityProvider;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * A deployed BPMN reaches only the reserved beans (docs/security.md rule 2): an expression or
 * payload template that names any other Spring bean must fail instead of reading the engine's
 * configuration. Without the restriction a pack's connector input could read {@code
 * ${environment.getProperty('INTERNAL_TASK_TOKEN')}} and post the secret to its own integration.
 * Both resolution paths are probed, JUEL (connector inputs) and FreeMarker (payload templates),
 * because the engine resolves beans for them through different mechanisms.
 */
@SpringBootTest(
    properties = {
      "INTERNAL_TASK_TOKEN=" + BeanExposureTest.SENTINEL,
      "BACKEND_URL=http://localhost:8085",
      "PATCHBAY_URL=http://localhost:8090"
    })
@ActiveProfiles("test")
class BeanExposureTest {

  static final String SENTINEL = "internal-token-sentinel-for-bean-exposure-test";

  @MockitoBean private KeycloakIdentityProvider keycloakIdentityProvider;

  @Autowired private ProcessEngine processEngine;

  @BeforeEach
  void deploy() {
    if (processEngine
            .getRepositoryService()
            .createProcessDefinitionQuery()
            .processDefinitionKey("beanProbeReserved")
            .count()
        == 0) {
      processEngine
          .getRepositoryService()
          .createDeployment()
          .name("bean-exposure-test")
          .addClasspathResource("authz/bean-exposure-test.bpmn")
          .deploy();
    }
  }

  @Test
  void reservedBeansStillResolve() {
    String pi =
        processEngine.getRuntimeService().startProcessInstanceByKey("beanProbeReserved").getId();
    String expected = "http://localhost:8085|http://localhost:8090|true";
    assertEquals(expected, processEngine.getRuntimeService().getVariable(pi, "viaJuel"));
    assertEquals(expected, processEngine.getRuntimeService().getVariable(pi, "viaFreemarker"));
  }

  @ParameterizedTest
  @CsvSource({
    "beanProbeJuelEnvironment, environment",
    "beanProbeJuelInterceptor, outboundCredentialsInterceptor",
    "beanProbeFreemarkerEnvironment, environment",
    "beanProbeFreemarkerInterceptor, outboundCredentialsInterceptor"
  })
  void otherBeansDoNotResolve(String processKey, String beanName) {
    ProcessEngineException refused =
        assertThrows(
            ProcessEngineException.class,
            () -> processEngine.getRuntimeService().startProcessInstanceByKey(processKey),
            processKey + " resolved the bean '" + beanName + "'");
    String message = String.valueOf(refused.getMessage());
    assertTrue(message.contains(beanName), message);
    assertTrue(!message.contains(SENTINEL), message);
  }
}
