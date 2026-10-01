package com.poc.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.poc.backend.engine.EngineClient;
import com.poc.backend.transport.PlateRegistrationRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The endpoint classes of docs/security.md rule 5, tested through the real filter chains: internal
 * endpoints need {@code X-Internal-Token}, the engine-only transport endpoints are gone from {@code
 * /api/public}, and a path outside the three prefixes is refused.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.internal-task-token=" + ApiEndpointClassesTest.TOKEN)
class ApiEndpointClassesTest {

  static final String TOKEN = "test-internal-token";

  private static final String CARD =
      "{\"processInstanceId\":\"pi-1\",\"service\":\"s\",\"status\":\"submitted\","
          + "\"summary\":\"x\"}";

  private static final String ALLOCATE =
      "{\"processInstanceId\":\"pi-1\",\"vin\":\"VIN1\",\"ownerName\":\"Bart\","
          + "\"vehicleCategory\":\"car\",\"plateOption\":\"random\"}";

  @Autowired MockMvc mvc;
  @Autowired PlateRegistrationRepository plates;
  @MockitoBean S3Client s3Client;
  @MockitoBean S3Presigner s3Presigner;
  @MockitoBean EngineClient engine;

  @Test
  void internalEndpointWithoutTokenIs401() throws Exception {
    mvc.perform(
            post("/api/internal/cases/index").contentType(MediaType.APPLICATION_JSON).content(CARD))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("invalid_internal_token"));
  }

  @Test
  void internalEndpointWithWrongTokenIs401() throws Exception {
    mvc.perform(
            get("/api/internal/transport/driver-clearance/{id}", "38001010000")
                .header("X-Internal-Token", "not-" + TOKEN))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void internalEndpointIgnoresAUserJwt() throws Exception {
    // A logged-in user is not the engine: a valid Bearer does not open /api/internal/**.
    mvc.perform(
            get("/api/internal/transport/vehicle-clearance/{vin}", "VIN1")
                .with(jwt().jwt(j -> j.claim("preferred_username", "bart"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void internalEndpointWithTheTokenIsServed() throws Exception {
    mvc.perform(
            get("/api/internal/transport/vehicle-clearance/{vin}", "VIN1")
                .header("X-Internal-Token", TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.vin").value("VIN1"));
  }

  @Test
  void engineOnlyTransportEndpointsAreNotPublic() throws Exception {
    long before = plates.count();

    mvc.perform(
            post("/api/public/transport/plates/allocate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ALLOCATE))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/public/transport/learning-permits/issue")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/public/transport/driver-clearance/{id}", "38001010000"))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/public/transport/vehicle-clearance/{vin}", "VIN1"))
        .andExpect(status().isNotFound());

    assertThat(plates.count()).isEqualTo(before);
  }

  @Test
  void documentEndpointsOfTheEngineAreNotUnderTheUserPrefixAnymore() throws Exception {
    // The old engine paths now fall into the JWT class; without a Bearer they are refused.
    mvc.perform(
            post("/api/documents/move-pending")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unmatchedApiPathIsDenied() throws Exception {
    mvc.perform(get("/api/admin/anything")).andExpect(status().isForbidden());
    mvc.perform(get("/api/internalx/cases")).andExpect(status().isForbidden());
    mvc.perform(get("/error")).andExpect(status().isForbidden());
  }

  @Test
  void unmatchedPathIsDeniedEvenWithAUserJwt() throws Exception {
    mvc.perform(get("/api/admin/anything").with(jwt())).andExpect(status().isForbidden());
  }

  @Test
  void reviewerGets404ForANonExistentCase() throws Exception {
    when(engine.historicProcessInstanceExists("pi-missing")).thenReturn(false);

    mvc.perform(
            get("/api/documents/{pi}", "pi-missing")
                .with(
                    jwt()
                        .jwt(
                            j ->
                                j.claim("preferred_username", "homer")
                                    .claim(
                                        "realm_access",
                                        Map.of("roles", List.of("civil-servant"))))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("not_found"));
  }

  @Test
  void publicReferenceDataStaysPublic() throws Exception {
    mvc.perform(get("/api/public/vehicle-registry/vehicles")).andExpect(status().isOk());
  }
}
