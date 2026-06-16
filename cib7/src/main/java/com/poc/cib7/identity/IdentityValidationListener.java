package com.poc.cib7.identity;

import java.util.Map;
import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.delegate.DelegateTask;
import org.cibseven.bpm.engine.delegate.TaskListener;
import org.cibseven.bpm.engine.identity.User;
import org.cibseven.bpm.engine.impl.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Complete-event task listener on applicant (Part A) user tasks. The SPA renders identity fields
 * read-only, but a disabled input is still forgeable by calling {@code
 * /engine-rest/task/{id}/complete} directly. This re-derives each trusted field from the caller's
 * Keycloak profile and aborts the completion with HTTP 400 ({@link BadUserRequestException}) if the
 * submitted/effective value was tampered. Civil-servant (Part B) tasks are not guarded — they are
 * simply not wired with this listener (see {@link IdentityBpmnParseListener}).
 *
 * <p>It reads the <em>effective</em> value (the value about to be committed), so it passes for both
 * the SPA (which resubmits the same read-only value) and the MCP path (which omits the field,
 * leaving the start-populated trusted value in place).
 */
public class IdentityValidationListener implements TaskListener {

  private static final Logger LOG = LoggerFactory.getLogger(IdentityValidationListener.class);

  @Override
  public void notify(DelegateTask delegateTask) {
    String key = Identities.processKey(delegateTask.getProcessDefinitionId());
    Map<String, IdentityFieldRegistry.Source> bindings = IdentityFieldRegistry.bindingsFor(key);
    if (bindings == null || bindings.isEmpty()) {
      return;
    }

    IdentityService identityService = Context.getProcessEngineConfiguration().getIdentityService();
    String userId = Identities.currentUserId(identityService);
    if (userId == null) {
      // No authenticated principal on the thread — we can't establish the trusted identity, so we
      // don't block (the engine's own authorization still gates the call). Normal applicant
      // completions always carry a Bearer, so this is an edge case (e.g. a system-driven complete).
      LOG.debug("No authenticated user completing {} task — skipping identity validation", key);
      return;
    }

    User user = identityService.createUserQuery().userId(userId).singleResult();
    if (user == null) {
      LOG.warn(
          "User '{}' not found in Keycloak — skipping identity validation for {}", userId, key);
      return;
    }

    bindings.forEach(
        (variable, source) -> {
          String trusted = source.resolve(user);
          Object submittedRaw = delegateTask.getVariable(variable);
          String submitted = submittedRaw == null ? "" : submittedRaw.toString();
          if (!source.matches(trusted, submitted)) {
            LOG.warn(
                "Rejected {} completion: '{}' submitted as '{}' but Keycloak says '{}' for user '{}'",
                key,
                variable,
                submitted,
                trusted,
                userId);
            throw new BadUserRequestException(
                "The field '"
                    + variable
                    + "' is set automatically from your verified account and cannot be changed.");
          }
        });
  }
}
