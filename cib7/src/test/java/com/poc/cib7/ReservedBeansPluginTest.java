package com.poc.cib7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.poc.cib7.keycloak.KeycloakIdentityProvider;
import java.util.HashMap;
import java.util.Map;
import org.cibseven.bpm.engine.ProcessEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * A process variable named like a reserved configuration bean must not change what BPMN expressions
 * and FreeMarker templates resolve (docs/security.md rule 2). The test process resolves {@code
 * busBaseUrl} through JUEL and {@code busBaseUrl}/{@code frontendBaseUrl} through an inline
 * FreeMarker script after the start variables tried to overwrite both.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReservedBeansPluginTest {

  private static final String EVIL = "http://attacker.example";

  @MockitoBean private KeycloakIdentityProvider keycloakIdentityProvider;

  @Autowired private ProcessEngine processEngine;

  @Autowired
  @Qualifier("busBaseUrl")
  private String busBaseUrl;

  @Autowired
  @Qualifier("frontendBaseUrl")
  private String frontendBaseUrl;

  @Test
  void variablesCannotShadowReservedBeans() {
    if (processEngine
            .getRepositoryService()
            .createProcessDefinitionQuery()
            .processDefinitionKey("reservedBeansTest")
            .count()
        == 0) {
      processEngine
          .getRepositoryService()
          .createDeployment()
          .name("reserved-beans-test")
          .addClasspathResource("authz/reserved-beans-test.bpmn")
          .deploy();
    }

    Map<String, Object> hostile = new HashMap<>();
    hostile.put("busBaseUrl", EVIL);
    hostile.put("frontendBaseUrl", EVIL);
    String pi =
        processEngine
            .getRuntimeService()
            .startProcessInstanceByKey("reservedBeansTest", hostile)
            .getId();

    assertNotEquals(EVIL, busBaseUrl);
    assertEquals(busBaseUrl, processEngine.getRuntimeService().getVariable(pi, "viaJuel"));
    assertEquals(
        busBaseUrl + "|" + frontendBaseUrl,
        processEngine.getRuntimeService().getVariable(pi, "viaFreemarker"));
  }
}
