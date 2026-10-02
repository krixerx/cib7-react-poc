package com.poc.backend.cases;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.poc.backend.engine.EngineClient;
import com.poc.backend.engine.EngineClient.ProcessInstanceRef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Draft deletion through the real filter chains: only the starter may delete, only while no task
 * has been completed, and everyone else learns nothing about the case.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DraftCaseControllerTest {

  private static final String PI = "pi-draft";

  @Autowired MockMvc mvc;
  @MockitoBean EngineClient engine;
  @MockitoBean S3Client s3Client;
  @MockitoBean S3Presigner s3Presigner;

  @BeforeEach
  void setUp() {
    when(engine.getHistoricStartUserId(PI)).thenReturn("bart");
    when(engine.findActiveById(PI)).thenReturn(new ProcessInstanceRef(PI, "vehicleRegistration"));
    when(engine.finishedTaskCount(PI)).thenReturn(0L);
  }

  private static JwtRequestPostProcessor user(String name) {
    return jwt().jwt(j -> j.claim("preferred_username", name));
  }

  @Test
  void starterDeletesTheirDraft() throws Exception {
    mvc.perform(delete("/api/cases/{pi}", PI).with(user("bart"))).andExpect(status().isNoContent());

    verify(engine).deleteProcessInstance(PI);
  }

  @Test
  void anotherApplicantGets404AndNothingIsDeleted() throws Exception {
    mvc.perform(delete("/api/cases/{pi}", PI).with(user("lisa"))).andExpect(status().isNotFound());

    verify(engine, never()).deleteProcessInstance(anyString());
  }

  @Test
  void reviewerCannotDeleteAnApplicantsDraft() throws Exception {
    mvc.perform(
            delete("/api/cases/{pi}", PI)
                .with(
                    jwt()
                        .jwt(
                            j ->
                                j.claim("preferred_username", "homer")
                                    .claim(
                                        "realm_access",
                                        Map.of("roles", List.of("civil-servant", "cib7-admin"))))))
        .andExpect(status().isNotFound());

    verify(engine, never()).deleteProcessInstance(anyString());
  }

  @Test
  void submittedCaseIsRefused() throws Exception {
    when(engine.finishedTaskCount(PI)).thenReturn(1L);

    mvc.perform(delete("/api/cases/{pi}", PI).with(user("bart")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("not_a_draft"));

    verify(engine, never()).deleteProcessInstance(anyString());
  }

  @Test
  void endedCaseGets404() throws Exception {
    when(engine.findActiveById(PI)).thenReturn(null);

    mvc.perform(delete("/api/cases/{pi}", PI).with(user("bart"))).andExpect(status().isNotFound());

    verify(engine, never()).deleteProcessInstance(anyString());
  }

  @Test
  void deleteWithoutJwtIs401() throws Exception {
    mvc.perform(delete("/api/cases/{pi}", PI)).andExpect(status().isUnauthorized());

    verify(engine, never()).deleteProcessInstance(anyString());
  }

  @Test
  void draftsListsOnlyTheCallersUnsubmittedCases() throws Exception {
    when(engine.unfinishedInstanceIdsStartedBy("bart")).thenReturn(List.of(PI, "pi-submitted"));
    when(engine.finishedTaskCount("pi-submitted")).thenReturn(2L);

    mvc.perform(get("/api/cases/drafts").with(user("bart")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.processInstanceIds.length()").value(1))
        .andExpect(jsonPath("$.processInstanceIds[0]").value(PI));
  }
}
