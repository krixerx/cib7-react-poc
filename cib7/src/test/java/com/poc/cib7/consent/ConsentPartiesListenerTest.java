package com.poc.cib7.consent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.cib7.keycloak.KeycloakIdentityProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.task.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Consent state is server-owned (docs/security.md rule 3): whatever tokens, party ids,
 * confirmations or flags the applicant's client submits, the complete listener on the submit task
 * replaces them. Runs against the real deployed vehicle and business BPMN.
 */
// Job execution off: the case parks at the async tracking-email task, so no connector fires.
@SpringBootTest(properties = "camunda.bpm.job-execution.enabled=false")
@ActiveProfiles("test")
class ConsentPartiesListenerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final long FORGED_ROUND = 99_999_999_999_999L;

  @MockitoBean private KeycloakIdentityProvider keycloakIdentityProvider;

  @Autowired private ProcessEngine processEngine;

  @Test
  void vehicleSubmitIgnoresClientSentTokensPartyIdsAndConfirmations() throws Exception {
    String pi = startAndSubmit("vehicleRegistration", vehicleVariables());
    RuntimeService runtime = processEngine.getRuntimeService();

    JsonNode owners = MAPPER.readTree(runtime.getVariable(pi, "additionalOwners").toString());
    assertEquals(2, owners.size(), "the entry without an email is dropped");
    assertEquals("p1", owners.get(0).path("partyId").asText());
    assertEquals("Olga Omanik", owners.get(0).path("name").asText());
    assertEquals("olga@example.com", owners.get(0).path("email").asText());
    assertEquals("p2", owners.get(1).path("partyId").asText());
    for (JsonNode owner : owners) {
      assertEquals(3, owner.size(), "only partyId, name and email survive: " + owner);
      assertFalse(owner.has("token"));
    }

    JsonNode confirmations =
        MAPPER.readTree(runtime.getVariable(pi, "ownerConfirmations").toString());
    assertEquals(1, confirmations.size(), "client-sent approvals are discarded: " + confirmations);
    assertEquals("approved", confirmations.path("applicant").path("status").asText());

    Object round = runtime.getVariable(pi, "consentRound");
    assertTrue(round instanceof Long, "consentRound is a server-written Long");
    assertNotEquals(FORGED_ROUND, round);
    assertEquals(false, runtime.getVariable(pi, "rejectedByOwner"));
    assertEquals(false, runtime.getVariable(pi, "sentToProcess"));
    assertFalse(runtime.getVariables(pi).containsKey("applicantToken"));
  }

  @Test
  void businessSubmitWithoutFoundersGetsAnEmptyServerList() throws Exception {
    Map<String, Object> vars = new HashMap<>();
    vars.put("companyName", "Acme OÜ");
    vars.put(
        "boardMembers",
        "[{\"firstName\":\"Bart\",\"lastName\":\"Simpson\",\"personalCode\":\"39912312345\"}]");
    vars.put("shareCapital", 2500.0);
    vars.put("applicantFirstName", "Bart");
    vars.put("applicantLastName", "Simpson");
    vars.put("applicantAge", 30);
    vars.put("founderSignatures", "{\"p1\":{\"status\":\"approved\"}}");
    String pi = startAndSubmit("businessRegistration", vars);

    RuntimeService runtime = processEngine.getRuntimeService();
    assertEquals(
        0, MAPPER.readTree(runtime.getVariable(pi, "additionalFounders").toString()).size());
    JsonNode signatures = MAPPER.readTree(runtime.getVariable(pi, "founderSignatures").toString());
    assertEquals(List.of("applicant"), fieldNames(signatures));
  }

  @Test
  void sanitizeAcceptsEveryShapeAClientCanSend() {
    String json = "[{\"name\":\" A \",\"email\":\"a@x.ee\",\"token\":\"t\"},{\"name\":5}]";
    assertEquals(1, ConsentPartiesListener.sanitize(json).size());
    assertEquals(
        1, ConsentPartiesListener.sanitize(List.of(Map.of("name", "A", "email", "a@x.ee"))).size());
    assertEquals(0, ConsentPartiesListener.sanitize("not json").size());
    assertEquals(0, ConsentPartiesListener.sanitize(null).size());
    assertEquals(0, ConsentPartiesListener.sanitize("{\"name\":\"A\"}").size());
  }

  private String startAndSubmit(String processKey, Map<String, Object> variables) {
    String pi =
        processEngine
            .getRuntimeService()
            .startProcessInstanceByKey(processKey, Map.of("initiator", "bart"))
            .getId();
    Task task =
        processEngine.getTaskService().createTaskQuery().processInstanceId(pi).singleResult();
    processEngine.getTaskService().complete(task.getId(), variables);
    return pi;
  }

  private static Map<String, Object> vehicleVariables() {
    Map<String, Object> vars = new HashMap<>();
    vars.put("firstName", "Bart");
    vars.put("lastName", "Simpson");
    vars.put("age", 30);
    vars.put("objectId", "VIN-1");
    vars.put("applicantEmail", "bart@example.com");
    vars.put("applicantToken", "client-chosen-token");
    vars.put(
        "additionalOwners",
        "[{\"name\":\"Olga Omanik\",\"email\":\"olga@example.com\",\"token\":\"t1\","
            + "\"partyId\":\"applicant\",\"isAdmin\":true},"
            + "{\"name\":\"Mari\",\"email\":\"mari@example.com\",\"partyId\":\"p9\"},"
            + "{\"name\":\"No Email\"}]");
    vars.put(
        "ownerConfirmations",
        "{\"t1\":{\"status\":\"approved\"},\"p1\":{\"status\":\"approved\"},"
            + "\"p2\":{\"status\":\"approved\"}}");
    vars.put("rejectedByOwner", true);
    vars.put("sentToProcess", true);
    vars.put("consentRound", FORGED_ROUND);
    return vars;
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new java.util.ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }
}
