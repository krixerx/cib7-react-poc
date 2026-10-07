package com.poc.cib7.policy;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Checks the service pack's generated value schemas against the variable policies they pair with,
 * and that a schema without its process and form stops the startup. No Spring context.
 */
class FormSchemaRegistryTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final FormSchemaRegistry registry =
      new FormSchemaRegistry(new String[] {"classpath*:processes/*/schemas/*.json"});

  FormSchemaRegistryTest() throws Exception {}

  @Tag("pack")
  @Test
  void everySchemaBelongsToAPolicyForm() throws Exception {
    Map<String, VariablePolicy> policies =
        VariablePolicyRegistry.load("classpath*:processes/*/variable-policy.json");
    for (VariablePolicy policy : policies.values()) {
      assertTrue(
          registry.forStart(policy.processDefinitionKey()).isPresent(),
          policy.processDefinitionKey() + " has no start schema");
      for (String form : policy.forms().keySet()) {
        assertTrue(
            registry.forForm(policy.processDefinitionKey(), form).isPresent(),
            policy.processDefinitionKey() + "/" + form + " has no value schema");
      }
    }
  }

  // The rules each form's values must keep are its spec's Submission examples,
  // run by SubmissionExamplesTest (a pack check).

  @Test
  void schemaWithoutProcessAndFormStopsStartup() {
    assertThrows(
        IllegalStateException.class,
        () -> FormSchemaRegistry.load("classpath*:authz/invalid-schemas/*.json"));
  }
}
