package com.poc.cib7.identity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.cibseven.bpm.engine.identity.User;

/**
 * Single source of truth for which process variables on each service are "trusted identity" fields
 * — values we derive from the signed-in user's Keycloak profile instead of letting the applicant
 * type them.
 *
 * <p>Keyed by BPMN process definition key. Field names are deliberately NOT unified across services
 * ({@code businessRegistration} uses {@code applicantFirstName}/{@code applicantLastName}, {@code
 * vehicleRegistration} uses {@code firstName}/{@code lastName}, the two transport services use a
 * single {@code applicantName}); we keep each service's existing names and map them here.
 *
 * <p>Both halves of the feature read this map:
 *
 * <ul>
 *   <li>{@link IdentityPopulationListener} writes these variables at process start.
 *   <li>{@link IdentityValidationListener} re-derives them on completion of the applicant task and
 *       rejects any tampered value.
 * </ul>
 *
 * <p>Only fields Keycloak actually holds belong here (given/family name, email). Age, civil id,
 * personal code, residency, and profession stay applicant-entered and are intentionally absent.
 * Personal code can join this map once it exists as a Keycloak user attribute.
 */
public final class IdentityFieldRegistry {

  /** Which Keycloak profile attribute a process variable is sourced from / validated against. */
  public enum Source {
    GIVEN_NAME,
    FAMILY_NAME,
    FULL_NAME,
    EMAIL;

    /** The trusted value for this source from a Keycloak user; never null, always trimmed. */
    public String resolve(User user) {
      String first = nullToEmpty(user.getFirstName()).trim();
      String last = nullToEmpty(user.getLastName()).trim();
      return switch (this) {
        case GIVEN_NAME -> first;
        case FAMILY_NAME -> last;
        case FULL_NAME -> (first + " " + last).trim();
        case EMAIL -> nullToEmpty(user.getEmail()).trim();
      };
    }

    /** Email comparison is case-insensitive; names are exact after trimming. */
    public boolean matches(String trusted, String submitted) {
      String actual = submitted == null ? "" : submitted.trim();
      return this == EMAIL ? trusted.equalsIgnoreCase(actual) : trusted.equals(actual);
    }
  }

  private static final Map<String, Map<String, Source>> BINDINGS = new LinkedHashMap<>();

  static {
    BINDINGS.put(
        "vehicleRegistration",
        ordered(
            "firstName", Source.GIVEN_NAME,
            "lastName", Source.FAMILY_NAME,
            "applicantEmail", Source.EMAIL));
    BINDINGS.put(
        "businessRegistration",
        ordered(
            "applicantFirstName", Source.GIVEN_NAME,
            "applicantLastName", Source.FAMILY_NAME,
            "applicantEmail", Source.EMAIL));
    BINDINGS.put(
        "transportVehicleRegistration",
        ordered(
            "applicantName", Source.FULL_NAME,
            "applicantEmail", Source.EMAIL));
    BINDINGS.put(
        "transportLearningPermit",
        ordered(
            "applicantName", Source.FULL_NAME,
            "applicantEmail", Source.EMAIL));
  }

  private IdentityFieldRegistry() {}

  /** Bindings for a process definition key, or {@code null} if the service has none. */
  public static Map<String, Source> bindingsFor(String processDefinitionKey) {
    return BINDINGS.get(processDefinitionKey);
  }

  private static Map<String, Source> ordered(Object... pairs) {
    Map<String, Source> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put((String) pairs[i], (Source) pairs[i + 1]);
    }
    return Collections.unmodifiableMap(map);
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
