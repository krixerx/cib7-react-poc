package com.poc.cib7.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.poc.cib7.keycloak.KeycloakIdentityProvider;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.cibseven.bpm.engine.AuthorizationException;
import org.cibseven.bpm.engine.HistoryService;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.authorization.Authorization;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Negative tests for docs/security.md rule 1, against the real engine configuration and the grants
 * {@code AuthorizationBootstrap} creates at startup.
 *
 * <p>Users are simulated with {@code IdentityService.setAuthentication(user, groups)}, which is
 * what the Keycloak REST filter does for each request; the engine's authorization checks only look
 * at that authentication, so no Keycloak is needed.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuthorizationModelTest {

  private static final List<String> APPLICANT = List.of("applicant");
  private static final List<String> CIVIL_SERVANT = List.of("civil-servant");

  @MockitoBean private KeycloakIdentityProvider keycloakIdentityProvider;

  @Autowired private ProcessEngine processEngine;

  private IdentityService identity;
  private RuntimeService runtime;
  private TaskService tasks;
  private HistoryService history;

  @BeforeEach
  void deploy() {
    identity = processEngine.getIdentityService();
    runtime = processEngine.getRuntimeService();
    tasks = processEngine.getTaskService();
    history = processEngine.getHistoryService();
    identity.clearAuthentication();
    if (processEngine
            .getRepositoryService()
            .createProcessDefinitionQuery()
            .processDefinitionKey("authzTest")
            .count()
        == 0) {
      processEngine
          .getRepositoryService()
          .createDeployment()
          .name("authz-test")
          .addClasspathResource("authz/initiator-authz-test.bpmn")
          .deploy();
    }
  }

  @AfterEach
  void logout() {
    identity.clearAuthentication();
  }

  @Test
  void historicInstancePermissionsAreEnabled() {
    ProcessEngineConfigurationImpl config =
        (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
    assertTrue(config.isEnableHistoricInstancePermissions());
  }

  @Test
  void noApplicationGroupHoldsAWildcardTaskGrant() {
    // The admin-user bootstrap gives the admin USER every resource; groups get none.
    assertEquals(
        0,
        processEngine
            .getAuthorizationService()
            .createAuthorizationQuery()
            .resourceType(Resources.TASK)
            .resourceId("*")
            .groupIdIn("applicant", "civil-servant")
            .count());
  }

  @Test
  void initiatorSeesOwnCaseAndOtherApplicantSeesNothing() {
    String pi = startAs("bart");
    Task bartTask =
        as("bart", APPLICANT, () -> tasks.createTaskQuery().processInstanceId(pi).singleResult());
    assertNotNull(bartTask, "bart sees his own task");

    as(
        "bart",
        APPLICANT,
        () -> {
          assertEquals(1, runtime.createProcessInstanceQuery().processInstanceId(pi).count());
          assertEquals(
              1, history.createHistoricProcessInstanceQuery().processInstanceId(pi).count());
          assertTrue(history.createHistoricProcessInstanceQuery().startedBy("bart").count() >= 1);
          assertTrue(
              history.createHistoricVariableInstanceQuery().processInstanceId(pi).count() > 0);
          assertTrue(
              history.createHistoricActivityInstanceQuery().processInstanceId(pi).count() > 0);
          assertEquals(1, history.createHistoricTaskInstanceQuery().processInstanceId(pi).count());
          assertTrue(history.createHistoricDetailQuery().processInstanceId(pi).count() > 0);
          assertEquals("bart", tasks.getVariable(bartTask.getId(), "initiator"));
          return null;
        });

    as(
        "lisa",
        APPLICANT,
        () -> {
          assertEquals(0, history.createHistoricProcessInstanceQuery().startedBy("bart").count());
          assertEquals(0, runtime.createProcessInstanceQuery().processInstanceId(pi).count());
          assertEquals(0, tasks.createTaskQuery().processInstanceId(pi).count());
          assertEquals(
              0, history.createHistoricProcessInstanceQuery().processInstanceId(pi).count());
          assertEquals(
              0, history.createHistoricVariableInstanceQuery().processInstanceId(pi).count());
          assertEquals(
              0, history.createHistoricActivityInstanceQuery().processInstanceId(pi).count());
          assertEquals(0, history.createHistoricTaskInstanceQuery().processInstanceId(pi).count());
          assertEquals(0, history.createHistoricDetailQuery().processInstanceId(pi).count());
          assertThrows(AuthorizationException.class, () -> runtime.getVariables(pi));
          assertThrows(AuthorizationException.class, () -> tasks.getVariables(bartTask.getId()));
          assertThrows(
              AuthorizationException.class, () -> tasks.complete(bartTask.getId(), Map.of("x", 1)));
          assertThrows(
              AuthorizationException.class, () -> tasks.setAssignee(bartTask.getId(), "lisa"));
          return null;
        });
  }

  @Test
  void civilServantReadsEveryCaseButCannotWorkApplicantTask() {
    String pi = startAs("bart");
    Task bartTask =
        as("bart", APPLICANT, () -> tasks.createTaskQuery().processInstanceId(pi).singleResult());

    as(
        "homer",
        CIVIL_SERVANT,
        () -> {
          assertEquals(1, runtime.createProcessInstanceQuery().processInstanceId(pi).count());
          assertEquals(1, tasks.createTaskQuery().processInstanceId(pi).count());
          assertTrue(
              history.createHistoricVariableInstanceQuery().processInstanceId(pi).count() > 0);
          assertThrows(AuthorizationException.class, () -> tasks.complete(bartTask.getId()));
          assertThrows(AuthorizationException.class, () -> tasks.claim(bartTask.getId(), "homer"));
          return null;
        });
  }

  @Test
  void applicantCannotWorkReviewTaskButCivilServantCan() {
    String pi = startAs("bart");
    as(
        "bart",
        APPLICANT,
        () -> {
          tasks.complete(tasks.createTaskQuery().processInstanceId(pi).singleResult().getId());
          return null;
        });

    Task review =
        as("bart", APPLICANT, () -> tasks.createTaskQuery().processInstanceId(pi).singleResult());
    assertNotNull(review, "the initiator can see the review step of their own case");
    assertEquals("Task_Review", review.getTaskDefinitionKey());

    as(
        "bart",
        APPLICANT,
        () -> {
          assertThrows(AuthorizationException.class, () -> tasks.complete(review.getId()));
          assertThrows(AuthorizationException.class, () -> tasks.claim(review.getId(), "bart"));
          assertThrows(
              AuthorizationException.class, () -> tasks.setAssignee(review.getId(), "bart"));
          return null;
        });
    as(
        "lisa",
        APPLICANT,
        () -> {
          assertEquals(0, tasks.createTaskQuery().taskId(review.getId()).count());
          return null;
        });

    as(
        "homer",
        CIVIL_SERVANT,
        () -> {
          tasks.setAssignee(review.getId(), "homer");
          tasks.complete(review.getId());
          return null;
        });
    assertEquals(0, runtime.createProcessInstanceQuery().processInstanceId(pi).count());
    as(
        "bart",
        APPLICANT,
        () -> {
          assertEquals(
              1,
              history.createHistoricProcessInstanceQuery().processInstanceId(pi).finished().count(),
              "the initiator still sees the finished case");
          return null;
        });
  }

  @Test
  void startWithoutAuthenticatedUserCreatesNoUserGrant() {
    String pi = runtime.startProcessInstanceByKey("authzTest", Map.of("initiator", "bart")).getId();
    assertEquals(
        0,
        processEngine
            .getAuthorizationService()
            .createAuthorizationQuery()
            .resourceType(Resources.PROCESS_INSTANCE)
            .resourceId(pi)
            .authorizationType(Authorization.AUTH_TYPE_GRANT)
            .count());
  }

  private String startAs(String user) {
    ProcessInstance pi = as(user, APPLICANT, () -> runtime.startProcessInstanceByKey("authzTest"));
    return pi.getId();
  }

  private <T> T as(String user, List<String> groups, Supplier<T> action) {
    identity.setAuthentication(user, groups);
    try {
      return action.get();
    } finally {
      identity.clearAuthentication();
    }
  }
}
