package com.poc.cib7;

import java.util.Arrays;
import java.util.List;
import org.cibseven.bpm.engine.AuthorizationService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.authorization.Authorization;
import org.cibseven.bpm.engine.authorization.Permission;
import org.cibseven.bpm.engine.authorization.Permissions;
import org.cibseven.bpm.engine.authorization.ProcessDefinitionPermissions;
import org.cibseven.bpm.engine.authorization.Resource;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.impl.persistence.entity.AuthorizationEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The only place group-level engine grants are created. Everything else an ordinary user can do
 * comes from per-resource grants the engine or our listeners create as a case runs.
 *
 * <p>Engine grants in CIB seven (as in Camunda 7) only ever add access. There is no implicit
 * "assignee only" filter on top of a wildcard, so a {@code TASK:*} grant really does open every
 * task. The model is therefore:
 *
 * <ul>
 *   <li><b>applicant</b>: may list and start services. Their own case becomes readable through the
 *       per-instance grants {@code InitiatorAuthorizationListener} creates at process start; their
 *       own tasks through the engine's default task authorizations for the assignee ({@code READ}
 *       plus {@code defaultUserPermissionNameForTask}, i.e. {@code UPDATE}).
 *   <li><b>civil-servant</b>: may read every case (instances, tasks, history) and retry failed
 *       jobs. They work only the tasks routed to a candidate group they belong to, through the
 *       engine's default group authorization on each such task; there is no {@code UPDATE_TASK} or
 *       {@code TASK:*} grant that would let them complete an applicant's task.
 *   <li><b>cib7-admin</b>: handled by the {@code cibseven-keycloak} plugin's {@code
 *       administratorGroupName}, not here.
 * </ul>
 *
 * <p>Process-definition grants use the wildcard resource id because a per-definition grant would
 * make every new spec-generated service a Java change; the wildcard is limited to what a role may
 * do on every case anyway.
 *
 * <p>Converges on every start: a missing grant is created, one with different permissions is
 * rewritten, and grants this class used to create but no longer should are deleted.
 */
@Component
public class AuthorizationBootstrap {

  private static final Logger LOG = LoggerFactory.getLogger(AuthorizationBootstrap.class);

  // The cibseven-keycloak plugin exposes Keycloak group IDs without the
  // leading slash, even when `useGroupPathAsCamundaGroupId: true` is set
  // (the path "/applicant" becomes the engine group id "applicant"). All
  // engine-side references — authorizations and BPMN candidateGroups —
  // must use this slash-less form to match.
  static final String APPLICANT_GROUP = "applicant";
  static final String CIVIL_SERVANT_GROUP = "civil-servant";

  private final ProcessEngine processEngine;

  public AuthorizationBootstrap(ProcessEngine processEngine) {
    this.processEngine = processEngine;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void grantAuthorizations() {
    AuthorizationService auth = processEngine.getAuthorizationService();

    grantApplicant(auth);
    grantCivilServant(auth);

    // Wildcard task grants from earlier versions of this class. H2 is
    // in-memory today, but a persistent database would keep them forever.
    revoke(auth, APPLICANT_GROUP, Resources.TASK, "*");
    revoke(auth, CIVIL_SERVANT_GROUP, Resources.TASK, "*");
  }

  /**
   * Applicants see the service catalogue and start cases. {@code PROCESS_INSTANCE:* CREATE} is what
   * {@code POST /process-definition/key/{key}/start} checks besides {@code CREATE_INSTANCE}; it
   * does not grant access to any existing instance.
   */
  private void grantApplicant(AuthorizationService auth) {
    grant(
        auth,
        APPLICANT_GROUP,
        Resources.PROCESS_DEFINITION,
        "*",
        ProcessDefinitionPermissions.READ,
        ProcessDefinitionPermissions.CREATE_INSTANCE);
    grant(auth, APPLICANT_GROUP, Resources.PROCESS_INSTANCE, "*", Permissions.CREATE);
  }

  /**
   * Civil servants read every case: definitions and BPMN XML for the worklist, runtime instances,
   * tasks and incidents, and the history behind the read-only case view. {@code RETRY_JOB} is the
   * narrowest permission {@code PUT /job/{id}/retries} accepts (the engine also accepts {@code
   * UPDATE_INSTANCE}, which would additionally allow changing variables and modifying instances).
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
        ProcessDefinitionPermissions.READ_TASK,
        ProcessDefinitionPermissions.RETRY_JOB);
  }

  private void grant(
      AuthorizationService auth,
      String group,
      Resource resource,
      String resourceId,
      Permission... permissions) {
    List<Authorization> existing = find(auth, group, resource, resourceId);
    Authorization authorization;
    if (existing.isEmpty()) {
      authorization = auth.createNewAuthorization(Authorization.AUTH_TYPE_GRANT);
      authorization.setGroupId(group);
      authorization.setResource(resource);
      authorization.setResourceId(resourceId);
    } else {
      authorization = existing.get(0);
      if (hasExactly(authorization, permissions)) {
        return;
      }
    }
    authorization.setPermissions(new Permission[0]);
    for (Permission permission : permissions) {
      authorization.addPermission(permission);
    }
    auth.saveAuthorization(authorization);
    LOG.info(
        "Granted {} on {}:{} to group {}",
        Arrays.toString(permissions),
        resource.resourceName(),
        resourceId,
        group);
  }

  private void revoke(AuthorizationService auth, String group, Resource resource, String id) {
    for (Authorization authorization : find(auth, group, resource, id)) {
      auth.deleteAuthorization(authorization.getId());
      LOG.info("Removed grant on {}:{} from group {}", resource.resourceName(), id, group);
    }
  }

  private static List<Authorization> find(
      AuthorizationService auth, String group, Resource resource, String resourceId) {
    return auth.createAuthorizationQuery()
        .authorizationType(Authorization.AUTH_TYPE_GRANT)
        .resourceType(resource)
        .resourceId(resourceId)
        .groupIdIn(group)
        .list();
  }

  /** Compares the stored bit mask, so a grant with extra permissions also counts as different. */
  private static boolean hasExactly(Authorization authorization, Permission[] permissions) {
    if (!(authorization instanceof AuthorizationEntity entity)) {
      return false;
    }
    int wanted = 0;
    for (Permission permission : permissions) {
      wanted |= permission.getValue();
    }
    return entity.getPermissions() == wanted;
  }
}
