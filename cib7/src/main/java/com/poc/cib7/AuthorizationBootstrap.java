package com.poc.cib7;

import org.cibseven.bpm.engine.AuthorizationService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.authorization.Authorization;
import org.cibseven.bpm.engine.authorization.Permission;
import org.cibseven.bpm.engine.authorization.Permissions;
import org.cibseven.bpm.engine.authorization.ProcessDefinitionPermissions;
import org.cibseven.bpm.engine.authorization.Resource;
import org.cibseven.bpm.engine.authorization.Resources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Grants the {@code /applicant} and {@code /civil-servant} Keycloak groups the minimum engine
 * authorizations to do their respective jobs across ALL deployed process definitions.
 *
 * <p>Engine authorization is enabled (see {@code application.yaml}), so by default a new group has
 * no permissions and every {@code /engine-rest} call gets either 403'd or — for "is this resource
 * here?" queries — returns a misleading 500 with a "no matching process definition" message,
 * because the engine deliberately hides resources the caller can't read. The {@code /cib7-admin}
 * group is handled by the {@code cibseven-keycloak} plugin's {@code administratorGroupName};
 * everyone else needs grants here.
 *
 * <p>Resource id is widened to {@code "*"} for the process-definition grants (eng-review T9). The
 * spec-first promise is "the analyst writes one markdown, /service-builder produces all artifacts."
 * A per-definition grant here would mean a Java change for every new service, breaking that
 * promise. Engine authorization layers per-task assignee/candidate-group filters on top, so the
 * wildcard does NOT let a civil servant complete an applicant's task — it only lets them see and
 * work on tasks owned by a candidate group they belong to.
 *
 * <p>Idempotent: re-running creates no duplicate rows.
 */
@Component
public class AuthorizationBootstrap {

  private static final Logger LOG = LoggerFactory.getLogger(AuthorizationBootstrap.class);

  // The cibseven-keycloak plugin exposes Keycloak group IDs without the
  // leading slash, even when `useGroupPathAsCamundaGroupId: true` is set
  // (the path "/applicant" becomes the engine group id "applicant"). All
  // engine-side references — authorizations and BPMN candidateGroups —
  // must use this slash-less form to match.
  private static final String APPLICANT_GROUP = "applicant";
  private static final String CIVIL_SERVANT_GROUP = "civil-servant";

  private final ProcessEngine processEngine;

  public AuthorizationBootstrap(ProcessEngine processEngine) {
    this.processEngine = processEngine;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void grantAuthorizations() {
    AuthorizationService auth = processEngine.getAuthorizationService();

    grantApplicant(auth);
    grantCivilServant(auth);
  }

  /**
   * Applicants start, read, and complete their own process instances. Per-task assignee filters in
   * the BPMN keep them out of civil-servant tasks even with the wildcard resource id.
   */
  private void grantApplicant(AuthorizationService auth) {
    grant(
        auth,
        APPLICANT_GROUP,
        Resources.PROCESS_DEFINITION,
        "*",
        ProcessDefinitionPermissions.READ,
        ProcessDefinitionPermissions.CREATE_INSTANCE,
        ProcessDefinitionPermissions.READ_INSTANCE,
        ProcessDefinitionPermissions.READ_HISTORY,
        ProcessDefinitionPermissions.UPDATE_INSTANCE,
        ProcessDefinitionPermissions.READ_TASK,
        ProcessDefinitionPermissions.UPDATE_TASK);

    // CREATE on a process instance is required by POST /process-definition/key/{}/start.
    grant(auth, APPLICANT_GROUP, Resources.PROCESS_INSTANCE, "*", Permissions.CREATE);

    // Task READ/UPDATE on any task — the engine layers an implicit
    // assignee/candidate filter on top, so the applicant only sees their
    // own assigned tasks even though resourceId is "*".
    grant(auth, APPLICANT_GROUP, Resources.TASK, "*", Permissions.READ, Permissions.UPDATE);
  }

  /**
   * Civil servants don't START processes (applicants do), but they need to see every deployed
   * definition (so the worklist's left-pane filter and the read-only history view can resolve form
   * keys from BPMN XML), see every instance and its history, see every task, and complete the tasks
   * that route to the civil-servant candidate group via the BPMN. They also need to retry failed
   * jobs from the worklist's incident pane — UPDATE_INSTANCE on the process definition covers that.
   */
  private void grantCivilServant(AuthorizationService auth) {
    grant(
        auth,
        CIVIL_SERVANT_GROUP,
        Resources.PROCESS_DEFINITION,
        "*",
        ProcessDefinitionPermissions.READ,
        ProcessDefinitionPermissions.READ_INSTANCE,
        ProcessDefinitionPermissions.READ_HISTORY,
        ProcessDefinitionPermissions.UPDATE_INSTANCE,
        ProcessDefinitionPermissions.READ_TASK,
        ProcessDefinitionPermissions.UPDATE_TASK);

    // Task READ/UPDATE on any task — BPMN candidateGroups still gate which
    // tasks a civil servant can actually claim/complete.
    grant(auth, CIVIL_SERVANT_GROUP, Resources.TASK, "*", Permissions.READ, Permissions.UPDATE);
  }

  private void grant(
      AuthorizationService auth,
      String group,
      Resource resource,
      String resourceId,
      Permission... permissions) {
    boolean exists =
        !auth.createAuthorizationQuery()
            .resourceType(resource)
            .resourceId(resourceId)
            .groupIdIn(group)
            .list()
            .isEmpty();
    if (exists) {
      return;
    }
    Authorization authorization = auth.createNewAuthorization(Authorization.AUTH_TYPE_GRANT);
    authorization.setGroupId(group);
    authorization.setResource(resource);
    authorization.setResourceId(resourceId);
    for (Permission permission : permissions) {
      authorization.addPermission(permission);
    }
    auth.saveAuthorization(authorization);
    LOG.info(
        "Granted {} on {}:{} to group {}", permissions, resource.resourceName(), resourceId, group);
  }
}
