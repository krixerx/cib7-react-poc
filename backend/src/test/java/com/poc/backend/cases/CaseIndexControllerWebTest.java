package com.poc.backend.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for the case-card ingest endpoint: validation, the id-equals-processInstanceId
 * upsert contract, and the best-effort posture (a storage failure answers 200 with {@code indexed:
 * false}, never an error the BPMN task would surface as an incident).
 */
@WebMvcTest(controllers = CaseIndexController.class)
@AutoConfigureMockMvc(addFilters = false)
class CaseIndexControllerWebTest {

  @Autowired MockMvc mvc;
  @MockitoBean CaseCardRepository repository;

  private static final String VALID =
      """
      {
        "processInstanceId": "pi-1",
        "service": "transport-vehicle-registration",
        "status": "submitted",
        "summary": "Vehicle registration application submitted by Homer."
      }
      """;

  @Test
  void missingFieldsAreBadRequest() throws Exception {
    mvc.perform(
            post("/api/documents/index-case")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processInstanceId\": \"pi-1\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("bad_request"));
  }

  @Test
  void cardIsStoredWithProcessInstanceIdAsPrimaryKey() throws Exception {
    mvc.perform(
            post("/api/documents/index-case")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.indexed").value(true));

    ArgumentCaptor<CaseCard> captor = ArgumentCaptor.forClass(CaseCard.class);
    verify(repository).save(captor.capture());
    CaseCard card = captor.getValue();
    assertThat(card.getProcessInstanceId()).isEqualTo("pi-1");
    assertThat(card.getService()).isEqualTo("transport-vehicle-registration");
    assertThat(card.getStatus()).isEqualTo("submitted");
    assertThat(card.getSummary()).contains("Homer");
    assertThat(card.getUpdatedAt()).isNotNull();
  }

  @Test
  void oversizedSummaryIsTruncatedNotRejected() throws Exception {
    String longSummary = "insurance ".repeat(1000).trim();
    mvc.perform(
            post("/api/documents/index-case")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "processInstanceId": "pi-long",
                      "service": "transport-vehicle-registration",
                      "status": "submitted",
                      "summary": "%s"
                    }
                    """
                        .formatted(longSummary)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.indexed").value(true));

    ArgumentCaptor<CaseCard> captor = ArgumentCaptor.forClass(CaseCard.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getSummary()).hasSize(CaseCard.SUMMARY_MAX_LENGTH);
  }

  @Test
  void storeFailureIsBestEffortNotAnError() throws Exception {
    doThrow(new RuntimeException("H2 down")).when(repository).save(any());

    mvc.perform(
            post("/api/documents/index-case")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.indexed").value(false));
  }
}
