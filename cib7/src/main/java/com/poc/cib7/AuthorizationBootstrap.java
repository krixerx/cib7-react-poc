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
 * Grants the {@code /applicant} Keycloak group the minimum engine
 * authorizations to start, read, and complete their own
 * {@code personRegistration} instances.
 *
 * <p>Engine authorization is enabled (see {@code application.yaml}), so by
 * default a new group has no permissions and would be 403-ed by every
 * {@code /engine-rest} call. The {@code /cib7-admin} group is handled by the
 * {@code cibseven-keycloak} plugin's {@code administratorGroupName}; this
 * bootstrap covers the much-narrower applicant role.
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
    private static final String PROCESS_KEY = "personRegistration";

    private final ProcessEngine processEngine;

    public AuthorizationBootstrap(ProcessEngine processEngine) {
        this.processEngine = processEngine;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void grantApplicantAuthorizations() {
        AuthorizationService auth = processEngine.getAuthorizationService();

        // On the personRegistration process definition: read it, create
        // instances, read those instances (runtime + history), and update
        // them (the loop-back to the applicant task needs UPDATE_INSTANCE
        // implicit on task completion).
        grant(auth, Resources.PROCESS_DEFINITION, PROCESS_KEY,
                ProcessDefinitionPermissions.READ,
                ProcessDefinitionPermissions.CREATE_INSTANCE,
                ProcessDefinitionPermissions.READ_INSTANCE,
                ProcessDefinitionPermissions.READ_HISTORY,
                ProcessDefinitionPermissions.UPDATE_INSTANCE,
                ProcessDefinitionPermissions.READ_TASK,
                ProcessDefinitionPermissions.UPDATE_TASK);

        // CREATE on a process instance is required by POST /process-definition/key/{}/start.
        grant(auth, Resources.PROCESS_INSTANCE, "*", Permissions.CREATE);

        // Task READ/UPDATE on any task — the engine layers an implicit
        // assignee/candidate filter on top, so the applicant only sees their
        // own assigned tasks even though resourceId is "*".
        grant(auth, Resources.TASK, "*", Permissions.READ, Permissions.UPDATE);
    }

    private void grant(AuthorizationService auth, Resource resource, String resourceId,
                       Permission... permissions) {
        boolean exists = !auth.createAuthorizationQuery()
                .resourceType(resource)
                .resourceId(resourceId)
                .groupIdIn(APPLICANT_GROUP)
                .list()
                .isEmpty();
        if (exists) {
            return;
        }
        Authorization authorization = auth.createNewAuthorization(Authorization.AUTH_TYPE_GRANT);
        authorization.setGroupId(APPLICANT_GROUP);
        authorization.setResource(resource);
        authorization.setResourceId(resourceId);
        for (Permission permission : permissions) {
            authorization.addPermission(permission);
        }
        auth.saveAuthorization(authorization);
        LOG.info("Granted {} on {}:{} to group {}",
                permissions, resource.resourceName(), resourceId, APPLICANT_GROUP);
    }
}
