package com.poc.cib7.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.cib7.ReservedBeansPlugin;
import com.poc.cib7.links.CapabilityLinks;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Loads every {@code variable-policy.json} at startup and answers "may a client write variable x
 * here?" for {@link VariableWritePolicyFilter}.
 *
 * <p>A process definition without a policy file gets no entry, which the filter treats as "no
 * client may write any variable": a new service whose builder run forgot the policy fails closed,
 * with a log line naming the key, instead of accepting anything. A malformed file, a duplicate key
 * or a policy that lists a name no client may ever write ({@link #NEVER_WRITABLE}) stops the engine
 * from starting, so a bad policy cannot ship.
 */
@Component
public class VariablePolicyRegistry {

  /** Where the pack's policies sit on the classpath. */
  public static final String DEFAULT_LOCATION = "classpath*:processes/*/variable-policy.json";

  private static final Logger LOG = LoggerFactory.getLogger(VariablePolicyRegistry.class);

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Names no policy may list whatever the spec says: the reserved configuration beans, the
   * initiator the engine sets from the authenticated user, and the state the core itself writes for
   * every pack: the co-signing round and party a capability link is bound to (docs/security.md rule
   * 3) and the payment facts the backend sets only on a signed provider callback (rule 4).
   */
  static final Set<String> NEVER_WRITABLE;

  static {
    Set<String> names = new LinkedHashSet<>(ReservedBeansPlugin.RESERVED_NAMES);
    names.add("initiator");
    names.add(CapabilityLinks.ROUND_VARIABLE);
    names.add("partyId");
    names.add("applicantToken");
    names.add("paymentReceived");
    names.add("paymentReference");
    names.add("paidAmount");
    NEVER_WRITABLE = Set.copyOf(names);
  }

  private final Map<String, VariablePolicy> policies;
  private final Set<String> reportedMissing = ConcurrentHashMap.newKeySet();

  @Autowired
  public VariablePolicyRegistry(
      @Value("${app.variable-policy.locations:" + DEFAULT_LOCATION + "}") String[] locations)
      throws IOException {
    this.policies = load(locations);
  }

  /** Builds a registry from already parsed policies; for tests. */
  VariablePolicyRegistry(Map<String, VariablePolicy> policies) {
    this.policies = Map.copyOf(policies);
  }

  /** The policy for a process definition key, logging once per key when there is none. */
  public Optional<VariablePolicy> forProcess(String processDefinitionKey) {
    VariablePolicy policy =
        processDefinitionKey == null ? null : policies.get(processDefinitionKey);
    if (policy == null && reportedMissing.add(String.valueOf(processDefinitionKey))) {
      LOG.warn(
          "No variable-policy.json for process definition '{}': clients may not write any of its"
              + " variables. Run the service builder to generate the policy.",
          processDefinitionKey);
    }
    return Optional.ofNullable(policy);
  }

  /** Every loaded policy, keyed by process definition key. */
  public Map<String, VariablePolicy> all() {
    return policies;
  }

  public static Map<String, VariablePolicy> load(String... locations) throws IOException {
    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    Map<String, VariablePolicy> loaded = new LinkedHashMap<>();
    for (String location : locations) {
      for (Resource resource : resolver.getResources(location.trim())) {
        VariablePolicy policy;
        try (InputStream in = resource.getInputStream()) {
          policy = parse(JSON.readTree(in), resource.getDescription());
        }
        if (loaded.putIfAbsent(policy.processDefinitionKey(), policy) != null) {
          throw new IllegalStateException(
              "Two variable policies for process definition '"
                  + policy.processDefinitionKey()
                  + "' (second: "
                  + resource.getDescription()
                  + ")");
        }
        LOG.info(
            "Variable policy for '{}': start {}, forms {}",
            policy.processDefinitionKey(),
            policy.start(),
            policy.forms().keySet());
      }
    }
    return loaded;
  }

  public static VariablePolicy parse(JsonNode root, String source) {
    JsonNode key = root.get("processDefinitionKey");
    if (key == null || !key.isTextual() || key.asText().isBlank()) {
      throw new IllegalStateException(source + ": processDefinitionKey is required");
    }
    Set<String> start = names(root.get("start"), source + " start");
    Map<String, Set<String>> forms = new LinkedHashMap<>();
    JsonNode formsNode = root.get("forms");
    if (formsNode != null && !formsNode.isNull()) {
      if (!formsNode.isObject()) {
        throw new IllegalStateException(source + ": forms must be an object");
      }
      Iterator<Map.Entry<String, JsonNode>> fields = formsNode.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> form = fields.next();
        forms.put(
            VariablePolicy.formId(form.getKey()),
            names(form.getValue(), source + " form " + form.getKey()));
      }
    }
    Map<String, String> identity = new LinkedHashMap<>();
    JsonNode identityNode = root.get("identity");
    if (identityNode != null && !identityNode.isNull()) {
      if (!identityNode.isObject()) {
        throw new IllegalStateException(source + ": identity must be an object");
      }
      Iterator<Map.Entry<String, JsonNode>> fields = identityNode.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        String attribute = entry.getValue().asText("");
        if (!VariablePolicy.IDENTITY_SOURCES.contains(attribute)) {
          throw new IllegalStateException(
              source
                  + ": identity."
                  + entry.getKey()
                  + " must be one of "
                  + VariablePolicy.IDENTITY_SOURCES);
        }
        if (NEVER_WRITABLE.contains(entry.getKey())) {
          throw new IllegalStateException(
              source + ": '" + entry.getKey() + "' is reserved and cannot be an identity variable");
        }
        identity.put(entry.getKey(), attribute);
      }
    }
    return new VariablePolicy(key.asText(), start, forms, identity);
  }

  private static Set<String> names(JsonNode node, String where) {
    Set<String> names = new LinkedHashSet<>();
    if (node == null || node.isNull()) {
      return names;
    }
    if (!node.isArray()) {
      throw new IllegalStateException(where + ": must be an array of variable names");
    }
    for (JsonNode element : node) {
      if (!element.isTextual() || element.asText().isBlank()) {
        throw new IllegalStateException(where + ": every entry must be a variable name");
      }
      String name = element.asText();
      if (NEVER_WRITABLE.contains(name)) {
        throw new IllegalStateException(
            where + ": '" + name + "' is reserved and can never be written by a client");
      }
      names.add(name);
    }
    return names;
  }
}
