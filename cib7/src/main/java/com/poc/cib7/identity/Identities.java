package com.poc.cib7.identity;

import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.impl.identity.Authentication;

/** Small shared helpers for the identity prefill / validation listeners. */
final class Identities {

  private Identities() {}

  /** Extract the BPMN process definition key from a definition id ({@code "key:version:id"}). */
  static String processKey(String processDefinitionId) {
    if (processDefinitionId == null) {
      return null;
    }
    int colon = processDefinitionId.indexOf(':');
    return colon < 0 ? processDefinitionId : processDefinitionId.substring(0, colon);
  }

  /** The user id bound to the current request thread, or {@code null} if none is set. */
  static String currentUserId(IdentityService identityService) {
    Authentication authentication = identityService.getCurrentAuthentication();
    return authentication == null ? null : authentication.getUserId();
  }
}
