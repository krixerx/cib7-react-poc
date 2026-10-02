package com.poc.backend.security;

import com.poc.backend.engine.EngineClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

/**
 * Per-case authorization for the documents API.
 *
 * <p>Rule: {@code civil-servant} and {@code cib7-admin} realm roles may access any case that exists
 * in the engine's history; everyone else only the cases they started, where "started" is the
 * engine's own record — {@code startUserId} on the historic process instance (works for ended cases
 * too). Unknown instance ids resolve to no access, which callers surface as 404 so probing for case
 * ids leaks nothing.
 *
 * <p>This is deliberately a backend-side rule instead of forwarding the caller's Bearer to {@code
 * /engine-rest} and relying on a READ_INSTANCE check: that variant silently degrades to "any valid
 * token" if engine authorization is ever switched off, while this one is deterministic and unit
 * testable.
 */
@Service
public class CaseAccessService {

  /** Realm roles that may see any case (back-office reviewers + admins). */
  private static final Set<String> REVIEWER_ROLES = Set.of("civil-servant", "cib7-admin");

  private final EngineClient engine;

  public CaseAccessService(EngineClient engine) {
    this.engine = engine;
  }

  /** Whether the authenticated caller may access documents of this case. */
  public boolean canAccessCase(String processInstanceId) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof JwtAuthenticationToken jwt)) {
      return false;
    }
    if (realmRoles(jwt).stream().anyMatch(REVIEWER_ROLES::contains)) {
      return engine.historicProcessInstanceExists(processInstanceId);
    }
    String me = jwt.getToken().getClaimAsString("preferred_username");
    if (me == null || me.isBlank()) {
      return false;
    }
    String starter = engine.getHistoricStartUserId(processInstanceId);
    return me.equals(starter);
  }

  /**
   * Whether the authenticated caller started this case. Narrower than {@link #canAccessCase}:
   * reviewers may read every case but act as the applicant on none.
   */
  public boolean isCaseStarter(String processInstanceId) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof JwtAuthenticationToken jwt)) {
      return false;
    }
    String me = jwt.getToken().getClaimAsString("preferred_username");
    return me != null
        && !me.isBlank()
        && me.equals(engine.getHistoricStartUserId(processInstanceId));
  }

  /** Upper bound on the cases {@link #ownCaseIds} reads from the engine's history. */
  public static final int MAX_OWN_CASES = 1000;

  /**
   * Ids of the cases the caller started, running or ended. Deliberately the starter rule for every
   * role: a reviewer may read any case, but "my" cases are only the ones they applied for.
   */
  public List<String> ownCaseIds() {
    String me = callerUsername();
    return me == null ? List.of() : engine.instanceIdsStartedBy(me, MAX_OWN_CASES);
  }

  /** The caller's Keycloak username, or {@code null} without a JWT that names one. */
  public String callerUsername() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof JwtAuthenticationToken jwt)) {
      return null;
    }
    String me = jwt.getToken().getClaimAsString("preferred_username");
    return me == null || me.isBlank() ? null : me;
  }

  private static List<String> realmRoles(JwtAuthenticationToken jwt) {
    Map<String, Object> realmAccess = jwt.getToken().getClaimAsMap("realm_access");
    if (realmAccess == null) {
      return List.of();
    }
    Object roles = realmAccess.get("roles");
    if (roles instanceof List<?> list) {
      return list.stream().map(String::valueOf).toList();
    }
    return List.of();
  }
}
