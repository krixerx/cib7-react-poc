package com.poc.backend.security;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.poc.backend.engine.EngineClient;
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
 * endpoints need {@code X-Internal-Token} and a path outside the three prefixes is refused.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.internal-task-token=" + ApiEndpointClassesTest.TOKEN)
class ApiEndpointClassesTest {

  static final String TOKEN = "test-internal-token";

  private static final String CARD =
      "{\"processInstanceId\":\"pi-1\",\"service\":\"s\",\"status\":\"submitted\","
          + "\"summary\":\"x\"}";

  @Autowired MockMvc mvc;
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

  /** The fee quote is engine-only: no token, no fee (docs/security.md rule 5). */
  @Test
  void feeQuoteWithoutTokenIs401() throws Exception {
    mvc.perform(get("/api/internal/payments/quote/pi-1")).andExpect(status().isUnauthorized());
  }

  @Test
  void feeQuoteWithTokenIsThePacksFee() throws Exception {
    when(engine.findActiveById("pi-1"))
        .thenReturn(new EngineClient.ProcessInstanceRef("pi-1", "businessRegistration"));
    mvc.perform(get("/api/internal/payments/quote/pi-1").header("X-Internal-Token", TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(265.00))
        .andExpect(jsonPath("$.currency").value("EUR"));
    mvc.perform(get("/api/internal/payments/quote/pi-unknown").header("X-Internal-Token", TOKEN))
        .andExpect(status().isNotFound());
  }

  @Test
  void internalEndpointWithWrongTokenIs401() throws Exception {
    mvc.perform(
            post("/api/internal/cases/index")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CARD)
                .header("X-Internal-Token", "not-" + TOKEN))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void internalEndpointIgnoresAUserJwt() throws Exception {
    // A logged-in user is not the engine: a valid Bearer does not open /api/internal/**.
    mvc.perform(
            post("/api/internal/cases/index")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CARD)
                .with(jwt().jwt(j -> j.claim("preferred_username", "bart"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void internalEndpointWithTheTokenIsServed() throws Exception {
    mvc.perform(
            post("/api/internal/cases/index")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CARD)
                .header("X-Internal-Token", TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.indexed").value(true));
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
    mvc.perform(get("/api/public/registry/vehicles")).andExpect(status().isOk());
  }
}
