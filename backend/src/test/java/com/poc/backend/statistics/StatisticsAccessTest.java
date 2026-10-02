package com.poc.backend.statistics;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.poc.backend.engine.EngineClient;
import com.poc.backend.security.RealmRoleAuthorities;
import com.poc.backend.statistics.StatisticsReport.Counts;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Who may read statistics, through the real filter chains: the {@code statistics-viewer} realm role
 * and nothing else. The role arrives in the token, so these tests map authorities with the same
 * {@link RealmRoleAuthorities} converter the chain uses.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatisticsAccessTest {

  @Autowired MockMvc mvc;
  @MockitoBean S3Client s3Client;
  @MockitoBean S3Presigner s3Presigner;
  @MockitoBean EngineClient engine;
  @MockitoBean StatisticsService statistics;

  private static RequestPostProcessor userWithRoles(String... roles) {
    return jwt()
        .jwt(
            j ->
                j.claim("preferred_username", "someone")
                    .claim("realm_access", Map.of("roles", List.of(roles))))
        .authorities(new RealmRoleAuthorities());
  }

  @Test
  void withoutTokenIs401() throws Exception {
    mvc.perform(get("/api/statistics").param("from", "2026-10-01").param("to", "2026-10-02"))
        .andExpect(status().isUnauthorized());
    verify(statistics, never()).report(any());
  }

  @Test
  void applicantIs403() throws Exception {
    mvc.perform(
            get("/api/statistics")
                .param("from", "2026-10-01")
                .param("to", "2026-10-02")
                .with(userWithRoles("applicant")))
        .andExpect(status().isForbidden());
    verify(statistics, never()).report(any());
  }

  @Test
  void civilServantRoleAloneIs403() throws Exception {
    // Statistics hang on their own role, so taking it away in Keycloak works even for a reviewer.
    mvc.perform(
            get("/api/statistics")
                .param("from", "2026-10-01")
                .param("to", "2026-10-02")
                .with(userWithRoles("civil-servant")))
        .andExpect(status().isForbidden());
    verify(statistics, never()).report(any());
  }

  @Test
  void statisticsViewerIsServed() throws Exception {
    Counts zero = new Counts(0, 0, 0, 0, 0);
    when(statistics.report(any()))
        .thenReturn(
            new StatisticsReport(
                LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-10-02"),
                "UTC",
                false,
                zero,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));

    mvc.perform(
            get("/api/statistics")
                .param("from", "2026-10-01")
                .param("to", "2026-10-02")
                .with(userWithRoles("statistics-viewer")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totals.started").value(0));
  }

  @Test
  void malformedFiltersAre400() throws Exception {
    mvc.perform(
            get("/api/statistics")
                .param("from", "2026-10-02")
                .param("to", "2026-10-01")
                .with(userWithRoles("statistics-viewer")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid_range"));
    mvc.perform(
            get("/api/statistics")
                .param("from", "2025-01-01")
                .param("to", "2026-10-01")
                .with(userWithRoles("statistics-viewer")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid_range"));
    mvc.perform(
            get("/api/statistics")
                .param("from", "2026-10-01")
                .param("to", "2026-10-01")
                .param("zone", "Mars/Olympus")
                .with(userWithRoles("statistics-viewer")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid_zone"));
    mvc.perform(
            get("/api/statistics")
                .param("from", "2026-10-01")
                .param("to", "2026-10-01")
                .param("service", "a\"; drop")
                .with(userWithRoles("statistics-viewer")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid_filter"));
    verify(statistics, never()).report(any());
  }
}
