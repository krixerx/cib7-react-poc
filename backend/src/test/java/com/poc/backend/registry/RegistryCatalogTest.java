package com.poc.backend.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.poc.backend.registry.RegistryDescriptor.Access;
import com.poc.backend.registry.RegistryDescriptor.Operation;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** The closed set a registry descriptor must stay inside; anything else stops startup. */
class RegistryCatalogTest {

  private static final String VALID =
      """
      platform: 2
      entity: vehicles
      table: reg_vehicles
      key: vin
      fields:
        vin: { type: string }
        year: { type: integer }
        fuelType: { type: string }
      derived:
        ageYears: { yearsSince: year }
      operations:
        lookup: { access: public }
      """;

  @Test
  void theReferencePackDescriptorLoads() throws Exception {
    Map<String, RegistryDescriptor> loaded = RegistryCatalog.load("classpath*:registry/*.yaml");
    RegistryDescriptor vehicles = loaded.get("vehicles");
    assertEquals("reg_vehicles", vehicles.table());
    assertEquals("make", vehicles.sort());
    assertEquals(Access.PUBLIC, vehicles.access(Operation.LOOKUP).orElseThrow());
    assertEquals("fuel_type", vehicles.field("fuelType").column());
  }

  @Test
  void validDescriptorParses() {
    RegistryDescriptor d = RegistryCatalog.parse(yaml(VALID));
    assertEquals("vin", d.sort());
    assertEquals("year", d.derived().get(0).yearsSinceField());
    assertTrue(d.access(Operation.LIST).isEmpty());
  }

  @Test
  void identifiersThatCouldBreakSqlOrPathsAreRefused() {
    assertRefused(VALID.replace("table: reg_vehicles", "table: reg_x\"; drop table documents; --"));
    assertRefused(VALID.replace("table: reg_vehicles", "table: documents"));
    assertRefused(VALID.replace("entity: vehicles", "entity: ../internal"));
    assertRefused(
        VALID.replace("fuelType: { type: string }", "\"fuel\\\"Type\": { type: string }"));
  }

  @Test
  void anythingOutsideTheClosedSetIsRefused() {
    assertRefused(VALID.replace("access: public", "access: everyone"));
    assertRefused(VALID.replace("lookup: { access: public }", "delete: { access: public }"));
    assertRefused(VALID.replace("year: { type: integer }", "year: { type: blob }"));
    assertRefused(VALID.replace("{ yearsSince: year }", "{ yearsSince: vin }"));
    assertRefused(VALID.replace("{ yearsSince: year }", "{ sql: \"now()\" }"));
    assertRefused(VALID.replace("key: vin", "key: owner"));
    assertRefused(VALID.replace("platform: 2", "platform: 3"));
  }

  private static void assertRefused(String descriptor) {
    assertThrows(
        IllegalArgumentException.class, () -> RegistryCatalog.parse(yaml(descriptor)), descriptor);
  }

  private static Object yaml(String text) {
    return new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
  }
}
