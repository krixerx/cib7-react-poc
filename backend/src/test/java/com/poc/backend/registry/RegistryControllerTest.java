package com.poc.backend.registry;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.poc.backend.engine.EngineClient;
import java.time.Year;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The registry module through the real filter chains: the test pack's {@code vehicles} served
 * exactly like the former hand-written controller, and the negative cases of docs/security.md rule
 * 5 with a test-only {@code plates} entity that is internal only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "app.internal-task-token=" + RegistryControllerTest.TOKEN,
      "app.registry.locations=classpath*:registry/*.yaml,classpath*:registry-test/*.yaml",
      "app.registry.migrations=classpath:db/registry,classpath:db/registry-test"
    })
class RegistryControllerTest {

  static final String TOKEN = "test-internal-token";

  @Autowired MockMvc mvc;
  @MockitoBean S3Client s3Client;
  @MockitoBean S3Presigner s3Presigner;
  @MockitoBean EngineClient engine;

  @Test
  void vehiclesListKeepsTheFormerResponseShape() throws Exception {
    int age = Year.now().getValue() - 2019;
    mvc.perform(get("/api/public/registry/vehicles"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(10)))
        // Sorted by make: Audi first.
        .andExpect(jsonPath("$[0].make").value("Audi"))
        .andExpect(jsonPath("$[?(@.vin == 'WP0AB2A91KS123456')].model").value("911 Carrera"))
        .andExpect(jsonPath("$[?(@.vin == 'WP0AB2A91KS123456')].value").value(88000.0))
        .andExpect(jsonPath("$[?(@.vin == 'WP0AB2A91KS123456')].fuelType").value("Petrol"))
        .andExpect(jsonPath("$[?(@.vin == 'WP0AB2A91KS123456')].ageYears").value(age));
  }

  @Test
  void vehicleLookupByVin() throws Exception {
    mvc.perform(get("/api/public/registry/vehicles/VF15RBA0H55012345"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.make").value("Renault"))
        .andExpect(jsonPath("$.year").value(2015))
        .andExpect(jsonPath("$.value").value(4200.0));
  }

  @Test
  void missingOrMaliciousKeyIs404() throws Exception {
    mvc.perform(get("/api/public/registry/vehicles/NOPE")).andExpect(status().isNotFound());
    mvc.perform(get("/api/public/registry/vehicles/' or '1'='1")).andExpect(status().isNotFound());
  }

  @Test
  void unknownEntityIs404() throws Exception {
    mvc.perform(get("/api/public/registry/flyway_schema_history")).andExpect(status().isNotFound());
    mvc.perform(get("/api/public/registry/documents")).andExpect(status().isNotFound());
  }

  @Test
  void internalOnlyEntityIsInvisibleOnThePublicPath() throws Exception {
    mvc.perform(get("/api/public/registry/plates/123ABC")).andExpect(status().isNotFound());
    mvc.perform(get("/api/public/registry/plates")).andExpect(status().isNotFound());
  }

  @Test
  void internalEntityNeedsTheInternalToken() throws Exception {
    mvc.perform(get("/api/internal/registry/plates/123ABC")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/internal/registry/plates/123ABC").header("X-Internal-Token", TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holder").value("Homer"));
  }

  @Test
  void operationTheDescriptorDoesNotOfferIs404() throws Exception {
    // plates offers lookup only, not list.
    mvc.perform(get("/api/internal/registry/plates").header("X-Internal-Token", TOKEN))
        .andExpect(status().isNotFound());
    // vehicles is public only, so the internal path does not serve it.
    mvc.perform(get("/api/internal/registry/vehicles").header("X-Internal-Token", TOKEN))
        .andExpect(status().isNotFound());
  }
}
