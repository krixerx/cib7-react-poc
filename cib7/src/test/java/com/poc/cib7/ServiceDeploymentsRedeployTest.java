package com.poc.cib7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.poc.cib7.keycloak.KeycloakIdentityProvider;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.repository.Deployment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Redeploying a service on a persistent database: a changed BPMN must take its DMN along, because
 * business rule tasks bind decisions by deployment. Regression for the incident "no decision
 * definition deployed with key 'vehicle-auto-approval' in deployment ..." after the BPMN alone was
 * redeployed. Own context (and dirtied after), because it adds deployments and process versions.
 */
@SpringBootTest(properties = "test.context=service-deployments-redeploy")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServiceDeploymentsRedeployTest {

  private static final String BPMN = "processes/vehicle-registration/vehicle-registration.bpmn";
  private static final String DMN = "processes/vehicle-registration/vehicle-auto-approval.dmn";

  @MockitoBean private KeycloakIdentityProvider keycloakIdentityProvider;

  @Autowired private ProcessEngine processEngine;
  @Autowired private ServiceDeployments serviceDeployments;

  @Test
  void changedBpmnIsDeployedTogetherWithItsDmn() throws Exception {
    Deployment first = serviceDeployments.deploy("redeploy-changed", resources(bpmn(""), dmn()));
    Deployment second =
        serviceDeployments.deploy("redeploy-changed", resources(bpmn("<!-- changed -->"), dmn()));

    assertNotEquals(first.getId(), second.getId());
    assertHoldsBoth(second);
  }

  @Test
  void unchangedServiceIsNotRedeployed() throws Exception {
    serviceDeployments.deploy("redeploy-unchanged", resources(bpmn(""), dmn()));
    serviceDeployments.deploy("redeploy-unchanged", resources(bpmn(""), dmn()));

    assertEquals(1, deployments("redeploy-unchanged").size());
  }

  @Test
  void deploymentMissingTheDmnIsRepaired() throws Exception {
    // What the old deployChangedOnly=true left behind: the BPMN without its DMN.
    repository()
        .createDeployment()
        .name("redeploy-broken")
        .addInputStream(BPMN, bpmn("").getInputStream())
        .deploy();

    Deployment repaired = serviceDeployments.deploy("redeploy-broken", resources(bpmn(""), dmn()));

    assertEquals(2, deployments("redeploy-broken").size());
    assertHoldsBoth(repaired);
  }

  private void assertHoldsBoth(Deployment deployment) {
    assertEquals(
        List.of(BPMN, DMN).stream().sorted().toList(),
        repository().getDeploymentResourceNames(deployment.getId()).stream().sorted().toList());
    assertEquals(
        1,
        repository()
            .createDecisionDefinitionQuery()
            .deploymentId(deployment.getId())
            .decisionDefinitionKey("vehicle-auto-approval")
            .count());
  }

  private List<Deployment> deployments(String name) {
    return repository().createDeploymentQuery().deploymentName(name).list();
  }

  private RepositoryService repository() {
    return processEngine.getRepositoryService();
  }

  private static Map<String, Resource> resources(Resource bpmn, Resource dmn) {
    Map<String, Resource> resources = new LinkedHashMap<>();
    resources.put(BPMN, bpmn);
    resources.put(DMN, dmn);
    return resources;
  }

  private static Resource bpmn(String suffix) throws Exception {
    byte[] original = new ClassPathResource(BPMN).getContentAsByteArray();
    return new ByteArrayResource(
        (new String(original, StandardCharsets.UTF_8) + suffix).getBytes(StandardCharsets.UTF_8));
  }

  private static Resource dmn() {
    return new ClassPathResource(DMN);
  }
}
