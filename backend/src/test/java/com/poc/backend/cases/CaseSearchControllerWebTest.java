package com.poc.backend.cases;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.poc.backend.security.CaseAccessService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for the case-card search endpoint: keyword ranking, the service/status filters,
 * the parameterless "newest cards" mode, and the per-case access filter — a card from a case the
 * caller may not access is silently dropped, same no-probing posture as the documents API.
 */
@WebMvcTest(controllers = CaseSearchController.class)
@AutoConfigureMockMvc(addFilters = false)
class CaseSearchControllerWebTest {

  @Autowired MockMvc mvc;
  @MockitoBean CaseCardRepository repository;
  @MockitoBean CaseAccessService caseAccess;

  private static CaseCard card(String pi, String service, String status, String summary) {
    return new CaseCard(pi, service, status, summary);
  }

  @Test
  void hitsFromInaccessibleCasesAreDropped() throws Exception {
    when(repository.findAllByOrderByUpdatedAtDesc())
        .thenReturn(
            List.of(
                card(
                    "pi-mine",
                    "transport-vehicle-registration",
                    "sent-back",
                    "Returned for corrections: missing insurance clearance."),
                card(
                    "pi-other",
                    "transport-vehicle-registration",
                    "sent-back",
                    "Someone else's case, also stuck on insurance.")));
    when(caseAccess.canAccessCase("pi-mine")).thenReturn(true);
    when(caseAccess.canAccessCase("pi-other")).thenReturn(false);

    mvc.perform(get("/api/cases/search").param("q", "stuck because of insurance"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(jsonPath("$.results[0].processInstanceId").value("pi-mine"))
        .andExpect(jsonPath("$.results[0].status").value("sent-back"))
        .andExpect(jsonPath("$.results[0].summary").isNotEmpty());
  }

  @Test
  void keywordOverlapOutranksRecency() throws Exception {
    // Repository order is newest-first: the weak match is newer, the strong
    // match older — ranking must put the stronger keyword overlap first.
    when(repository.findAllByOrderByUpdatedAtDesc())
        .thenReturn(
            List.of(
                card(
                    "pi-new-weak",
                    "transport-learning-permit",
                    "submitted",
                    "Permit application awaiting review."),
                card(
                    "pi-old-strong",
                    "transport-learning-permit",
                    "rejected",
                    "Permit application rejected: insurance requirements not met.")));
    when(caseAccess.canAccessCase(anyString())).thenReturn(true);

    mvc.perform(get("/api/cases/search").param("q", "permit rejected over insurance"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(2))
        .andExpect(jsonPath("$.results[0].processInstanceId").value("pi-old-strong"))
        .andExpect(jsonPath("$.results[1].processInstanceId").value("pi-new-weak"));
  }

  @Test
  void nonMatchingCardsAreDroppedWhenQueryGiven() throws Exception {
    when(repository.findAllByOrderByUpdatedAtDesc())
        .thenReturn(
            List.of(
                card(
                    "pi-1",
                    "transport-vehicle-registration",
                    "registered",
                    "Vehicle registration completed for VIN 123.")));
    when(caseAccess.canAccessCase(anyString())).thenReturn(true);

    mvc.perform(get("/api/cases/search").param("q", "medical assessment"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(0));
  }

  @Test
  void statusAndServiceFiltersApplyBeforeAccessChecks() throws Exception {
    when(repository.findAllByOrderByUpdatedAtDesc())
        .thenReturn(
            List.of(
                card("pi-permit", "transport-learning-permit", "rejected", "Permit rejected."),
                card(
                    "pi-vehicle",
                    "transport-vehicle-registration",
                    "registered",
                    "Vehicle registered.")));
    when(caseAccess.canAccessCase("pi-permit")).thenReturn(true);

    mvc.perform(
            get("/api/cases/search")
                .param("service", "transport-learning-permit")
                .param("status", "rejected"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(jsonPath("$.results[0].processInstanceId").value("pi-permit"));

    // The filtered-out card must not cost an engine round-trip.
    verify(caseAccess, never()).canAccessCase("pi-vehicle");
  }

  @Test
  void noParametersReturnsNewestAccessibleCards() throws Exception {
    when(repository.findAllByOrderByUpdatedAtDesc())
        .thenReturn(
            List.of(
                card("pi-newest", "transport-learning-permit", "submitted", "Just submitted."),
                card("pi-older", "transport-vehicle-registration", "registered", "All done.")));
    when(caseAccess.canAccessCase(anyString())).thenReturn(true);

    mvc.perform(get("/api/cases/search"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(2))
        .andExpect(jsonPath("$.results[0].processInstanceId").value("pi-newest"));
  }
}
