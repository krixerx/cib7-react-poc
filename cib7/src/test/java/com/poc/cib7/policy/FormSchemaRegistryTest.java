package com.poc.cib7.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Checks the service pack's generated value schemas against the variable policies they pair with,
 * and pins the confirmed rules with valid and invalid submissions. No Spring context.
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

  @Test
  void ownerVehicleRules() throws Exception {
    Schema schema = registry.forForm("vehicleRegistration", "owner-vehicle").orElseThrow();
    String ok =
        """
        {"age": 30, "objectId": "WP0AB2A91KS123456", "pendingIdDocument": null, "applicantEmail": "",
         "additionalOwners": [], "sendBackReason": ""}""";
    assertValid(schema, ok);
    assertInvalid(schema, ok.replace("\"age\": 30", "\"age\": 0"));
    assertInvalid(schema, ok.replace("WP0AB2A91KS123456", " "));
    // The VIN goes into the registry lookup URL path: nothing that could leave the segment.
    assertInvalid(schema, ok.replace("WP0AB2A91KS123456", "../../internal/documents"));
    assertInvalid(schema, ok.replace("WP0AB2A91KS123456", "WP0AB2A91KS12345%2F"));
    assertInvalid(schema, ok.replace("WP0AB2A91KS123456", "wp0ab2a91ks123456"));
    assertInvalid(schema, ok.replace("\"sendBackReason\": \"\"", "\"sendBackReason\": \"x\""));
    assertInvalid(
        schema,
        ok.replace(
            "\"pendingIdDocument\": null",
            "\"pendingIdDocument\": {\"pendingKey\": \"k\", \"filename\": \"a.exe\","
                + " \"contentType\": \"application/x-msdownload\"}"));
    // Co-owners need valid emails, and then the applicant needs one too.
    String withOwner =
        ok.replace(
            "\"additionalOwners\": []",
            "\"additionalOwners\": [{\"name\": \"Marge\", \"email\": \"marge@example.com\"}]");
    assertInvalid(schema, withOwner);
    assertValid(
        schema,
        withOwner.replace("\"applicantEmail\": \"\"", "\"applicantEmail\": \"bart@example.com\""));
    assertInvalid(
        schema,
        withOwner
            .replace("\"applicantEmail\": \"\"", "\"applicantEmail\": \"bart@example.com\"")
            .replace("marge@example.com", "marge"));
  }

  @Test
  void businessDetailsRules() throws Exception {
    Schema schema = registry.forForm("businessRegistration", "business-details").orElseThrow();
    String ok =
        """
        {"companyName": "Acme OÜ", "shareCapital": 2500, "applicantAge": 40,
         "applicantResidency": "citizen", "applicantEmail": "", "additionalFounders": [],
         "pendingAoaDocument": {"pendingKey": "pending/u/1/aoa.pdf", "filename": "aoa.pdf",
           "contentType": "application/pdf"},
         "boardMembers": [{"firstName": "Bart", "lastName": "S", "personalCode": "39001010000"}]}""";
    assertValid(schema, ok);
    assertInvalid(schema, ok.replace("2500", "2499.99"));
    assertInvalid(schema, ok.replace("39001010000", "3900101000"));
    assertInvalid(schema, ok.replace("\"citizen\"", "\"martian\""));
    assertInvalid(schema, ok.replace("\"applicantAge\": 40", "\"applicantAge\": 131"));
    assertInvalid(schema, ok.replace("\"Acme OÜ\"", "\"" + "x".repeat(201) + "\""));
    assertInvalid(
        schema,
        ok.replace(
            "[{\"firstName\": \"Bart\", \"lastName\": \"S\", \"personalCode\": \"39001010000\"}]",
            "[]"));
  }

  @Test
  void reviewNeedsAReasonOnlyWhenSendingBack() throws Exception {
    for (String form :
        List.of(
            "vehicleRegistration/vehicle-review",
            "businessRegistration/review-business-registration")) {
      String[] key = form.split("/");
      Schema schema = registry.forForm(key[0], key[1]).orElseThrow();
      assertValid(schema, "{\"decision\": \"approve\"}");
      assertValid(schema, "{\"decision\": \"sendback\", \"sendBackReason\": \"fix it\"}");
      assertInvalid(schema, "{\"decision\": \"sendback\", \"sendBackReason\": \" \"}");
      assertInvalid(schema, "{\"decision\": \"sendback\"}");
      assertInvalid(schema, "{\"decision\": \"reject\"}");
    }
  }

  @Test
  void schemaWithoutProcessAndFormStopsStartup() {
    assertThrows(
        IllegalStateException.class,
        () -> FormSchemaRegistry.load("classpath*:authz/invalid-schemas/*.json"));
  }

  private static void assertValid(Schema schema, String json) throws Exception {
    List<String> violations = FormSchemaRegistry.violations(schema, parse(json));
    assertEquals(List.of(), violations, json);
  }

  private static void assertInvalid(Schema schema, String json) throws Exception {
    assertFalse(FormSchemaRegistry.violations(schema, parse(json)).isEmpty(), json);
  }

  private static JsonNode parse(String json) throws Exception {
    return JSON.readTree(json);
  }
}
