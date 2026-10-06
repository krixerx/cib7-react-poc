package com.poc.cib7.identity;

import com.poc.cib7.policy.VariablePolicy;
import com.poc.cib7.policy.VariablePolicyRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.cibseven.bpm.engine.identity.User;

/**
 * Which process variables of each service are "trusted identity" fields: values derived from the
 * signed-in user's Keycloak profile instead of typed by the applicant.
 *
 * <p>The service pack declares them per process in the {@code identity} section of its {@code
 * variable-policy.json} (variable to {@code givenName}, {@code familyName} or {@code email}),
 * generated from the spec's variable write policy; field names differ per service. Read once from
 * the classpath, like the policies themselves.
 *
 * <p>Both halves of the feature read this map:
 *
 * <ul>
 *   <li>{@link IdentityPopulationListener} writes these variables at process start.
 *   <li>{@link IdentityValidationListener} re-derives them on completion of the applicant task and
 *       rejects any tampered value.
 * </ul>
 *
 * <p>Only attributes Keycloak actually holds can be sources (given/family name, email).
 */
public final class IdentityFieldRegistry {

  /** Which Keycloak profile attribute a process variable is sourced from / validated against. */
  public enum Source {
    GIVEN_NAME,
    FAMILY_NAME,
    EMAIL;

    /** The source for a policy's attribute name ({@link VariablePolicy#IDENTITY_SOURCES}). */
    static Source of(String attribute) {
      return switch (attribute) {
        case "givenName" -> GIVEN_NAME;
        case "familyName" -> FAMILY_NAME;
        case "email" -> EMAIL;
        default -> throw new IllegalArgumentException("Not an identity attribute: " + attribute);
      };
    }

    /** The trusted value for this source from a Keycloak user; never null, always trimmed. */
    public String resolve(User user) {
      String first = nullToEmpty(user.getFirstName()).trim();
      String last = nullToEmpty(user.getLastName()).trim();
      return switch (this) {
        case GIVEN_NAME -> first;
        case FAMILY_NAME -> last;
        case EMAIL -> nullToEmpty(user.getEmail()).trim();
      };
    }

    /** Email comparison is case-insensitive; names are exact after trimming. */
    public boolean matches(String trusted, String submitted) {
      String actual = submitted == null ? "" : submitted.trim();
      return this == EMAIL ? trusted.equalsIgnoreCase(actual) : trusted.equals(actual);
    }
  }

  /** Loaded on first use: the listeners are not Spring beans; the policies are on the classpath. */
  private static final class Holder {
    static final Map<String, Map<String, Source>> BINDINGS = fromPolicies(load());

    private static Collection<VariablePolicy> load() {
      try {
        return VariablePolicyRegistry.load(VariablePolicyRegistry.DEFAULT_LOCATION).values();
      } catch (IOException e) {
        throw new UncheckedIOException("variable policies are not readable", e);
      }
    }
  }

  /** Identity bindings per process definition key, from the policies' identity sections. */
  static Map<String, Map<String, Source>> fromPolicies(Collection<VariablePolicy> policies) {
    Map<String, Map<String, Source>> bindings = new LinkedHashMap<>();
    for (VariablePolicy policy : policies) {
      Map<String, Source> map = new LinkedHashMap<>();
      policy.identity().forEach((variable, attribute) -> map.put(variable, Source.of(attribute)));
      if (!map.isEmpty()) {
        bindings.put(policy.processDefinitionKey(), Collections.unmodifiableMap(map));
      }
    }
    return Collections.unmodifiableMap(bindings);
  }

  private IdentityFieldRegistry() {}

  /** Bindings for a process definition key, or {@code null} if the service has none. */
  public static Map<String, Source> bindingsFor(String processDefinitionKey) {
    return Holder.BINDINGS.get(processDefinitionKey);
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
