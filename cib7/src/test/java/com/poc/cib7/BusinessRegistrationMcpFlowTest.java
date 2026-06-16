package com.poc.cib7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.poc.cib7.keycloak.KeycloakIdentityProvider;
import java.util.HashMap;
import java.util.Map;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Regression test for the MCP-driven businessRegistration path, against the real deployed BPMN +
 * DMN on a full (H2-backed) engine.
 *
 * <p>The MCP manifest (docs/business/services/business-registration/build/mcp-service.json) exposes
 * a deliberately minimal six-field surface with {@code additionalProperties:false}, so a process
 * completed via MCP never sets the form-internal variables that the SPA business-details form always
 * writes (additionalFounders, applicantResidency, founderSignatures, rejectedByFounder, ...). Two
 * downstream expressions used to assume the rich SPA variable set:
 *
 * <ul>
 *   <li>{@code Gateway_NeedsSignatures} branched on a bare {@code additionalFounders} identifier.
 *       CIB seven JUEL throws PropertyNotFoundException for an UNSET variable rather than resolving
 *       to null, so completing the applicant task without it surfaced as ENGINE-REST HTTP500 — the
 *       exact failure reported from Claude Desktop.
 *   <li>{@code Task_AutoDecide} fed {@code applicantResidency} straight into the DMN; absent on the
 *       MCP path it fell through to {@code Rule_DefaultReview} ("review") instead of auto-approving.
 * </ul>
 *
 * <p>This test completes the applicant task with ONLY the six manifest fields and pins both fixes:
 * the complete call must not throw, and the case must auto-approve (skip the civil-servant queue).
 */
// Job execution off: completing the applicant task parks at the asyncBefore
// B-card service task. We assert on the synchronous result (autoDecision,
// routing) only — letting the job executor fire the downstream PDF/email
// connectors against unreachable sidecars would just spew misleading noise.
@SpringBootTest(properties = "camunda.bpm.job-execution.enabled=false")
@ActiveProfiles("test")
class BusinessRegistrationMcpFlowTest {

  @MockitoBean private KeycloakIdentityProvider keycloakIdentityProvider;

  @Autowired private ProcessEngine processEngine;

  @Test
  void soleFounderCompletedWithMcpVariablesAutoApproves() {
    var runtimeService = processEngine.getRuntimeService();
    var taskService = processEngine.getTaskService();

    ProcessInstance pi =
        runtimeService.startProcessInstanceByKey(
            "businessRegistration", Map.of("initiator", "bart"));

    Task task = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
    assertNotNull(task, "applicant task should exist");
    assertEquals("Task_SubmitBusinessDetails", task.getTaskDefinitionKey());

    // Exactly the six variables the MCP manifest allows — no additionalFounders,
    // no applicantResidency. HashMap (not Map.of) only because it reads clearer
    // for the mixed value types here.
    Map<String, Object> mcpVars = new HashMap<>();
    mcpVars.put("companyName", "Acme OÜ");
    mcpVars.put(
        "boardMembers",
        "[{\"firstName\":\"Bart\",\"lastName\":\"Simpson\",\"personalCode\":\"39912312345\"}]");
    mcpVars.put("shareCapital", 2500.0);
    mcpVars.put("applicantFirstName", "Bart");
    mcpVars.put("applicantLastName", "Simpson");
    mcpVars.put("applicantAge", 30);

    // Before the fix this throws ProcessEngineException (PropertyNotFoundException:
    // Cannot resolve identifier 'additionalFounders') — the HTTP500 the user hit.
    taskService.complete(task.getId(), mcpVars);

    // Task_AutoDecide ran with the defaulted residency and auto-approved. autoDecision
    // is committed synchronously before the async B-card PDF job, so it is readable here
    // regardless of whether the job executor is running.
    Object autoDecision =
        processEngine
            .getHistoryService()
            .createHistoricVariableInstanceQuery()
            .processInstanceId(pi.getId())
            .variableName("autoDecision")
            .singleResult()
            .getValue();
    assertEquals("approve", autoDecision, "sole-founder adult with >= EUR 2500 must auto-approve");

    // And it did NOT land in the civil-servant review queue.
    long reviewTasks =
        taskService
            .createTaskQuery()
            .processInstanceId(pi.getId())
            .taskDefinitionKey("Task_ReviewBusinessRegistration")
            .count();
    assertEquals(0, reviewTasks, "auto-approved case must skip Business Register review");
  }
}
