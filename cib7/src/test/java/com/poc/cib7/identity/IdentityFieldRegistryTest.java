package com.poc.cib7.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.cib7.identity.IdentityFieldRegistry.Source;
import com.poc.cib7.policy.VariablePolicyRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Identity fields come from the pack's variable policies, not from core code. */
class IdentityFieldRegistryTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void theReferencePackDeclaresEachServicesIdentityFields() {
    assertEquals(
        Map.of(
            "firstName", Source.GIVEN_NAME,
            "lastName", Source.FAMILY_NAME,
            "applicantEmail", Source.EMAIL),
        IdentityFieldRegistry.bindingsFor("vehicleRegistration"));
    assertEquals(
        Map.of(
            "applicantFirstName", Source.GIVEN_NAME,
            "applicantLastName", Source.FAMILY_NAME,
            "applicantEmail", Source.EMAIL),
        IdentityFieldRegistry.bindingsFor("businessRegistration"));
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
