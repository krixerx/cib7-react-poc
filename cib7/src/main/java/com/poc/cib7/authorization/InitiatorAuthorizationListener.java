package com.poc.cib7.authorization;

import java.util.LinkedHashSet;
import java.util.Set;
import org.cibseven.bpm.engine.authorization.Authorization;
import org.cibseven.bpm.engine.authorization.HistoricProcessInstancePermissions;
import org.cibseven.bpm.engine.authorization.Permission;
import org.cibseven.bpm.engine.authorization.ProcessInstancePermissions;
import org.cibseven.bpm.engine.authorization.Resource;
import org.cibseven.bpm.engine.authorization.Resources;
import org.cibseven.bpm.engine.authorization.TaskPermissions;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.DelegateTask;
import org.cibseven.bpm.engine.delegate.ExecutionListener;
import org.cibseven.bpm.engine.delegate.TaskListener;
import org.cibseven.bpm.engine.impl.AuthorizationQueryImpl;
import org.cibseven.bpm.engine.impl.context.Context;
import org.cibseven.bpm.engine.impl.interceptor.CommandContext;
import org.cibseven.bpm.engine.impl.persistence.entity.AuthorizationEntity;
import org.cibseven.bpm.engine.impl.persistence.entity.ExecutionEntity;

/**
 * Gives the person who started a case read access to that one case, and nothing else.
 *
 * <p>Applicants hold no wildcard grant on instances, tasks or history (see {@code
 * AuthorizationBootstrap}), because engine grants only ever add access: a {@code TASK:*} or {@code
 * PROCESS_DEFINITION:* READ_INSTANCE} grant would show every applicant every other applicant's
 * case. Instead, two hooks create per-resource user grants:
 *
 * <ul>
 *   <li><b>Process start</b>: the authenticated user gets {@code PROCESS_INSTANCE:<id> READ} and
 *       {@code HISTORIC_PROCESS_INSTANCE:<id> READ}. With {@code enableHistoricInstancePermissions}
 *       on, the latter is what the engine checks for the instance's historic activities, tasks,
 *       variables and details, so the case-progress timeline works for the owner only.
 *   <li><b>User task creation</b>: every user holding a {@code PROCESS_INSTANCE} grant on the
 *       task's instance gets {@code TASK:<id> READ}, so "My processes" can tell that a case is
 *       under review. Read only: claiming and completing still need the engine's default assignee /
 *       candidate authorizations, which this never creates.
 * </ul>
 *
 * <p>No authenticated user (job executor, a system call) means no grant. A case started by a
 * service account gives that account read access to the case, which it needed to start it anyway.
 *
 * <p>Grants are written through the authorization manager with checks suspended, because the
 * applicant has no right to create authorizations and must not get one.
 */
public class InitiatorAuthorizationListener implements ExecutionListener, TaskListener {

  @Override
  public void notify(DelegateExecution execution) {
    CommandContext ctx = Context.getCommandContext();
    String userId = ctx == null ? null : ctx.getAuthenticatedUserId();
    if (userId == null) {
      return;
    }
    String processInstanceId = execution.getProcessInstanceId();
    String rootProcessInstanceId =
        execution instanceof ExecutionEntity entity ? entity.getRootProcessInstanceId() : null;
    ctx.runWithoutAuthorization(
        () -> {
          grant(
              ctx,
              userId,
              Resources.PROCESS_INSTANCE,
              processInstanceId,
              rootProcessInstanceId,
              ProcessInstancePermissions.READ);
          grant(
              ctx,
              userId,
              Resources.HISTORIC_PROCESS_INSTANCE,
              processInstanceId,
              rootProcessInstanceId,
              HistoricProcessInstancePermissions.READ);
          return null;
        });
  }

  @Override
  public void notify(DelegateTask task) {
    CommandContext ctx = Context.getCommandContext();
    String processInstanceId = task.getProcessInstanceId();
    if (ctx == null || processInstanceId == null) {
      return;
    }
    ctx.runWithoutAuthorization(
        () -> {
          for (String userId : instanceReaders(ctx, processInstanceId)) {
            if (!userId.equals(task.getAssignee())) {
              grant(ctx, userId, Resources.TASK, task.getId(), null, TaskPermissions.READ);
            }
          }
          return null;
        });
  }

  /**
   * Users holding a {@code PROCESS_INSTANCE} grant on the instance. The cache is searched as well
   * as the database because the start grant is still unflushed when the first task is created in
   * the same transaction.
   */
  private static Set<String> instanceReaders(CommandContext ctx, String processInstanceId) {
    Set<String> users = new LinkedHashSet<>();
    for (AuthorizationEntity cached :
        ctx.getDbEntityManager().getCachedEntitiesByType(AuthorizationEntity.class)) {
      if (cached.getAuthorizationType() == Authorization.AUTH_TYPE_GRANT
          && cached.getResourceType() == Resources.PROCESS_INSTANCE.resourceType()
          && processInstanceId.equals(cached.getResourceId())
          && cached.getUserId() != null) {
        users.add(cached.getUserId());
      }
    }
    AuthorizationQueryImpl query = new AuthorizationQueryImpl();
    query
        .resourceType(Resources.PROCESS_INSTANCE)
        .resourceId(processInstanceId)
        .authorizationType(Authorization.AUTH_TYPE_GRANT);
    for (Authorization stored :
        ctx.getAuthorizationManager().selectAuthorizationByQueryCriteria(query)) {
      if (stored.getUserId() != null) {
        users.add(stored.getUserId());
      }
    }
    return users;
  }

  /**
   * Adds {@code permission} to the user's grant on the resource, creating the grant if needed. The
   * table has a unique key on (type, user, resource type, resource id), so an existing row, cached
   * or stored, is extended rather than duplicated; dirty checking flushes the change.
   */
  private static void grant(
      CommandContext ctx,
      String userId,
      Resource resource,
      String resourceId,
      String rootProcessInstanceId,
      Permission permission) {
    AuthorizationEntity existing = findCached(ctx, userId, resource, resourceId);
    if (existing == null) {
      existing =
          ctx.getAuthorizationManager()
              .findAuthorizationByUserIdAndResourceId(
                  Authorization.AUTH_TYPE_GRANT, userId, resource, resourceId);
    }
    if (existing != null) {
      if (!existing.isPermissionGranted(permission)) {
        existing.addPermission(permission);
      }
      return;
    }
    AuthorizationEntity authorization = new AuthorizationEntity(Authorization.AUTH_TYPE_GRANT);
    authorization.setUserId(userId);
    authorization.setResource(resource);
    authorization.setResourceId(resourceId);
    authorization.setRootProcessInstanceId(rootProcessInstanceId);
    authorization.addPermission(permission);
    ctx.getAuthorizationManager().insert(authorization);
  }

  private static AuthorizationEntity findCached(
      CommandContext ctx, String userId, Resource resource, String resourceId) {
    for (AuthorizationEntity cached :
        ctx.getDbEntityManager().getCachedEntitiesByType(AuthorizationEntity.class)) {
      if (cached.getAuthorizationType() == Authorization.AUTH_TYPE_GRANT
          && cached.getResourceType() == resource.resourceType()
          && resourceId.equals(cached.getResourceId())
          && userId.equals(cached.getUserId())) {
        return cached;
      }
    }
    return null;
  }
}
