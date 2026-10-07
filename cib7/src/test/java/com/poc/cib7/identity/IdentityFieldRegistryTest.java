package com.poc.cib7.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.cib7.policy.VariablePolicyRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Identity fields come from the pack's variable policies, not from core code. */
class IdentityFieldRegistryTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final Path ENGINE =
      Path.of(System.getProperty("services.pack.dir", "../packs/test/engine"));

  /**
   * Every field a form definition marks {@code identity} is bound in its process's policy, so the
   * engine fills it from the account at start and re-checks it on completion (docs/security.md rule
   * 2). An unbound one would be a name the client writes as it likes.
   */
  @Tag("pack")
  @Test
  void everyIdentityFieldOfAFormIsBoundToTheAccount() throws IOException {
    Path forms = ENGINE.resolveSibling("frontend").resolve("forms");
    List<String> problems = new ArrayList<>();
    try (Stream<Path> services = Files.list(ENGINE.resolve("processes"))) {
      for (Path service : services.filter(Files::isDirectory).sorted().toList()) {
        Path policyFile = service.resolve("variable-policy.json");
        if (!Files.exists(policyFile)) {
          continue;
        }
        JsonNode policy = JSON.readTree(policyFile.toFile());
        Map<String, ?> bound =
            IdentityFieldRegistry.bindingsFor(policy.path("processDefinitionKey").asText());
        for (String form : (Iterable<String>) () -> policy.path("forms").fieldNames()) {
          Path definition = forms.resolve(form + ".json");
          if (!Files.exists(definition)) {
            continue;
          }
          for (JsonNode field : JSON.readTree(definition.toFile()).path("fields")) {
            String name = field.path("name").asText();
            if (field.path("identity").asBoolean() && (bound == null || !bound.containsKey(name))) {
              problems.add(form + ": identity field " + name + " is not bound in the policy");
            }
          }
        }
      }
    }
    assertEquals(List.of(), problems);
    assertNull(IdentityFieldRegistry.bindingsFor("someOtherProcess"));
  }

  /** Only attributes the account holds can be a source; a reserved variable never can. */
  @Test
  void aPolicyWithAnUnknownSourceOrAReservedVariableIsRefused() throws Exception {
    for (String identity :
        new String[] {"{\"civilId\": \"personalCode\"}", "{\"initiator\": \"email\"}", "[]"}) {
      String policy = "{\"processDefinitionKey\": \"p\", \"identity\": " + identity + "}";
      assertThrows(
          IllegalStateException.class,
          () -> VariablePolicyRegistry.parse(JSON.readTree(policy), "test"),
          identity);
    }
  }
}
