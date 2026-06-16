package com.poc.cib7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.poc.cib7.keycloak.KeycloakIdentityProvider;
import org.cibseven.bpm.engine.ProcessEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Boots the full Spring context — engine, H2, BPMN/DMN auto-deployment, security filter chains,
 * AuthorizationBootstrap — without a running Keycloak.
 *
 * <p>The only bean that talks to Keycloak at startup is the {@link KeycloakIdentityProvider}
 * process-engine plugin (its {@code administratorGroupName} resolution queries the Admin REST API
 * while the engine is built). Replacing it with a Mockito no-op makes the engine fall back to the
 * default database identity provider, so {@code camunda.bpm.admin-user} and the authorization
 * bootstrap run against H2 instead. The JWT decoder and OAuth2 client beans are lazy — they only
 * fetch JWKS/token endpoints on the first request, never at startup — so they need no stubbing.
 *
 * <p>The {@code test} profile (src/test/resources/application-test.yaml) only quiets logging; all
 * functional configuration is inherited from the main application.yaml defaults.
 */
@SpringBootTest
@ActiveProfiles("test")
class Cib7ContextSmokeTest {

  @MockitoBean private KeycloakIdentityProvider keycloakIdentityProvider;

  @Autowired private ProcessEngine processEngine;

  @Test
  void contextLoadsAndDeploysProcesses() {
    assertNotNull(processEngine);
    // ServiceDeployments ran: the DMN decisions are on the engine.
    assertEquals(1, countDecision("vehicle-auto-approval"));
    assertEquals(1, countDecision("business-auto-approval"));
    assertEquals(1, countDecision("transport-vehicle-eligibility"));
    assertEquals(1, countDecision("transport-permit-eligibility"));
  }

  @Test
  void eachServiceGetsItsOwnDeployment() {
    // One named deployment per processes/<service>/ folder — NOT one
    // shared SpringAutoDeployment bundling everything.
    for (String service :
        new String[] {
          "vehicle-registration",
          "business-registration",
          "transport-vehicle-registration",
          "transport-learning-permit"
        }) {
      assertEquals(
          1,
          processEngine
              .getRepositoryService()
              .createDeploymentQuery()
              .deploymentName(service)
              .count(),
          "expected exactly one deployment named " + service);
    }
    assertEquals(
        0,
        processEngine
            .getRepositoryService()
            .createDeploymentQuery()
            .deploymentName("SpringAutoDeployment")
            .count(),
        "the starter's single-bundle auto-deploy must be off");
  }

  @Test
  void processAndItsDecisionsShareADeployment() {
    // decisionRefBinding="deployment" only resolves when the DMN ships in
    // the SAME deployment as the BPMN — assert the grouping holds.
    String deploymentId =
        processEngine
            .getRepositoryService()
            .createProcessDefinitionQuery()
            .processDefinitionKey("transportVehicleRegistration")
            .latestVersion()
            .singleResult()
            .getDeploymentId();
    assertEquals(
        2,
        processEngine
            .getRepositoryService()
            .createDecisionDefinitionQuery()
            .deploymentId(deploymentId)
            .count(),
        "transport-vehicle-eligibility + transport-vehicle-fee must ship with the process");
  }

  private long countDecision(String decisionDefinitionKey) {
    return processEngine
        .getRepositoryService()
        .createDecisionDefinitionQuery()
        .decisionDefinitionKey(decisionDefinitionKey)
        .latestVersion()
        .count();
  }
}
