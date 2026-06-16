package com.poc.cib7.identity;

import java.util.Map;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.ExecutionListener;
import org.cibseven.bpm.engine.identity.User;
import org.cibseven.bpm.engine.impl.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process-start execution listener. When a signed-in applicant starts a service, it looks their
 * profile up in Keycloak (via the engine's {@link IdentityService}, backed by the cibseven-keycloak
 * provider) and writes the service's identity variables (name, email) so the applicant never has to
 * type what we already know. The SPA renders these read-only and they are re-validated on task
 * completion by {@link IdentityValidationListener}.
 *
 * <p>No-ops silently when there is no authenticated user (e.g. a process started by a system job
 * rather than a REST call) or the user cannot be resolved — the applicant form then falls back to
 * manual entry, exactly as before.
 */
public class IdentityPopulationListener implements ExecutionListener {

  private static final Logger LOG = LoggerFactory.getLogger(IdentityPopulationListener.class);

  @Override
  public void notify(DelegateExecution execution) {
    String key = Identities.processKey(execution.getProcessDefinitionId());
    Map<String, IdentityFieldRegistry.Source> bindings = IdentityFieldRegistry.bindingsFor(key);
    if (bindings == null || bindings.isEmpty()) {
      return;
    }

    IdentityService identityService = Context.getProcessEngineConfiguration().getIdentityService();
    String userId = Identities.currentUserId(identityService);
    if (userId == null) {
      LOG.debug("No authenticated user at start of {} — skipping identity prefill", key);
      return;
    }

    User user = identityService.createUserQuery().userId(userId).singleResult();
    if (user == null) {
      LOG.warn("User '{}' not found in Keycloak — skipping identity prefill for {}", userId, key);
      return;
    }

    bindings.forEach((variable, source) -> execution.setVariable(variable, source.resolve(user)));
    LOG.debug(
        "Prefilled {} identity variables for {} from Keycloak user '{}'",
        bindings.keySet(),
        key,
        userId);
  }
}
